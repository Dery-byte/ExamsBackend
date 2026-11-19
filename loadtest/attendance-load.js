// Load test: a class checking in to attendance at the same time, the way the real pages do it.
//
//   Lecturer (a test account): starts a class for the course at a made-up spot, then its session page
//   refreshes the class list every 5 s and the projector refreshes the code every 2 s.
//   Students: arrive over ARRIVE seconds, open the attendance card (it asks "is a class open?"), send
//   their location a couple of times while the phone settles, type the code showing, check in, then keep
//   the card open: "is a class open?" every 15 s and their location every 20 s (60 s once it fits).
//   Bystanders (optional): other students sitting on their dashboards, whose card asks every 60 s.
//
// Run (PowerShell, from this folder):
//   k6 run -e LECTURER_USER=... -e LECTURER_PASS=... -e COURSE_ID=52 -e STUDENTS=300 attendance-load.js
//
// Settings (-e NAME=value):
//   BASE           server address (default https://examsbackendfrantfurt.onrender.com)
//   CSV            student accounts, "username,password" per line (default students.csv)
//   LECTURER_USER, LECTURER_PASS  a lecturer who teaches COURSE_ID. Use a test account nobody is signed
//                  in to: signing in here signs it out everywhere else.
//   COURSE_ID      the course the class is for (its department must take attendance per course)
//   STUDENTS       how many students check in (default 300)
//   BYSTANDERS     extra students only sitting on their dashboard (default 0); CSV rows after STUDENTS
//   ARRIVE         seconds over which students arrive (default 180; check-in stays open 15 min by default)
//   MINUTES        how long the class runs in the test (default 10)
//   LAT, LNG       where the made-up classroom is (default a spot in Accra); students are placed inside it
//   SESSION_ID     use a class that is already running instead of starting one (then it is left running)
//   KEEP           "yes" leaves the class running at the end (default: the test ends it)
//   NEW_PASSWORD   the test students' password after their first sign-in (default LoadTest#2026)
//   PIN_IP         connect to this address instead of asking the network's DNS (see exam-load.js)

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const BASE = (__ENV.BASE || 'https://examsbackendfrantfurt.onrender.com').replace(/\/$/, '');
const AUTH = `${BASE}/api/v1/auth`;
const ATT = `${BASE}/api/attendance`;
const COURSE_ID = __ENV.COURSE_ID;
const STUDENTS = Number(__ENV.STUDENTS || 300);
const BYSTANDERS = Number(__ENV.BYSTANDERS || 0);
const ARRIVE = Number(__ENV.ARRIVE || 180);
const MINUTES = Number(__ENV.MINUTES || 10);
const LAT = Number(__ENV.LAT || 5.6505);
const LNG = Number(__ENV.LNG || -0.1871);
const NEW_PASSWORD = __ENV.NEW_PASSWORD || 'LoadTest#2026';

const students = new SharedArray('students', () => {
  const rows = open(__ENV.CSV || './students.csv').split(/\r?\n/)
    .map(l => l.trim()).filter(Boolean)
    .map(l => l.split(/[,;\t]/).map(s => s.trim().replace(/^"|"$/g, '')));
  if (rows.length && /pass/i.test(rows[0][1] || '')) rows.shift();
  return rows.filter(r => r[0] && r[1]).map(r => ({ username: r[0], password: r[1] }));
});

if (!__ENV.LECTURER_USER || !__ENV.LECTURER_PASS) throw new Error('Set the test lecturer: -e LECTURER_USER=... -e LECTURER_PASS=...');
if (!COURSE_ID && !__ENV.SESSION_ID) throw new Error('Set the course: -e COURSE_ID=52');
if (students.length < STUDENTS + BYSTANDERS)
  throw new Error(`students.csv has ${students.length} accounts; this run needs ${STUDENTS + BYSTANDERS}.`);

const serverErrors = new Counter('server_errors_5xx');
const networkErrors = new Counter('network_errors_not_server');
const stepsFailed = new Counter('attendance_steps_failed');
const checkedIn = new Counter('checked_in');
const refused = new Counter('check_in_refused');
const codeRetries = new Counter('code_expired_retried');

const run = `${MINUTES + 5}m`;
const scenarios = {
  students: { executor: 'per-vu-iterations', exec: 'student', vus: STUDENTS, iterations: 1, maxDuration: run },
  lecturerList: { executor: 'per-vu-iterations', exec: 'lecturerList', vus: 1, iterations: 1, maxDuration: run },
  projector: { executor: 'per-vu-iterations', exec: 'projector', vus: 1, iterations: 1, maxDuration: run },
};
if (BYSTANDERS > 0) scenarios.bystanders = { executor: 'per-vu-iterations', exec: 'bystander', vus: BYSTANDERS, iterations: 1, maxDuration: run };

export const options = {
  scenarios,
  setupTimeout: '120s',
  teardownTimeout: '120s',
  thresholds: {
    'http_req_failed{kind:attendance}': ['rate<0.02'],
    'http_req_duration{kind:attendance}': ['p(95)<3000'],
    'http_req_duration{step:check-in}': ['p(95)<3000'],
    'http_req_duration{step:class-list}': ['p(95)<3000'],
    server_errors_5xx: ['count<5'],
    network_errors_not_server: ['count<5'],
    attendance_steps_failed: ['count<5'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
  ...(__ENV.PIN_IP ? { hosts: { [BASE.replace(/^https?:\/\//, '').replace(/[:/].*$/, '')]: __ENV.PIN_IP } } : {}),
};

// Each simulated person gets a made-up address, so the per-address sign-in limit doesn't refuse a whole class
function headers(ip, token) {
  const h = { 'Content-Type': 'application/json', 'X-Forwarded-For': ip };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}
const studentIp = idx => `10.78.${(idx >> 8) & 255}.${idx & 255}`;
const LECTURER_IP = '10.79.0.1';

function note(res, what, counted = true) {
  if (res.status >= 500) serverErrors.add(1);
  if (res.status === 0) networkErrors.add(1);
  const ok = check(res, { [`${what} ok`]: r => r.status >= 200 && r.status < 300 });
  if (!ok) {
    if (counted) stepsFailed.add(1);
    console.warn(`${what} → HTTP ${res.status} ${String(res.body || '').slice(0, 160)}`);
  }
  return ok;
}

const json = (res, path) => { try { return path ? res.json(path) : res.json(); } catch (e) { return undefined; } };

function signIn(username, password, ip) {
  return http.post(`${AUTH}/authenticate`, JSON.stringify({ username, password }),
    { headers: headers(ip), tags: { kind: 'signin' }, responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
}

/** Same as exam-load.js: the test password first; a dropped connection is retried, never taken as a wrong password. */
function studentLogin(idx) {
  const s = students[idx];
  const ip = studentIp(idx);
  let res = signIn(s.username, NEW_PASSWORD, ip);
  for (let i = 0; i < 3 && (res.status === 0 || res.status >= 500); i++) {
    if (res.status === 0) networkErrors.add(1); else serverErrors.add(1);
    sleep(3 + Math.random() * 4);
    res = signIn(s.username, NEW_PASSWORD, ip);
  }
  if (res.status === 200) return json(res, 'token');
  if (res.status === 0 || res.status >= 500) { note(res, `sign in ${s.username}`); return null; }
  res = signIn(s.username, s.password, ip);
  if (!note(res, `sign in ${s.username}`)) return null;
  const token = json(res, 'token');
  note(http.put(`${AUTH}/updatepassword`, JSON.stringify({ password: NEW_PASSWORD, currentPassword: s.password }),
    { headers: headers(ip, token), tags: { kind: 'signin' } }), `set test password for ${s.username}`);
  return token;
}

// ── Before: the lecturer signs in and starts the class ───────────────────────

export function setup() {
  const res = signIn(__ENV.LECTURER_USER, __ENV.LECTURER_PASS, LECTURER_IP);
  if (res.status !== 200) throw new Error(`Lecturer sign-in failed: HTTP ${res.status} ${String(res.body).slice(0, 200)}`);
  const token = json(res, 'token');
  const h = { headers: headers(LECTURER_IP, token), tags: { kind: 'setup' } };

  let sessionId = __ENV.SESSION_ID ? Number(__ENV.SESSION_ID) : null;
  let started = false;
  if (!sessionId) {
    const body = {
      scope: 'COURSE', courseId: Number(COURSE_ID), latitude: LAT, longitude: LNG, accuracy: 10,
      codeDisplay: 'PROJECTOR', durationMinutes: Math.max(15, MINUTES + 5), topic: 'Load test', secondCheck: false, method: 'STUDENT_CODE',
    };
    const opened = http.post(`${ATT}/manage/sessions`, JSON.stringify(body), h);
    if (opened.status === 200) {
      sessionId = json(opened, 'id');
      started = true;
    } else if (/already running/i.test(opened.body || '')) {
      // A class for this course is still running (an earlier test?): use it
      const live = (json(http.get(`${ATT}/manage/sessions`, h)) || []).find(x => x.live && String(x.courseId) === String(COURSE_ID));
      if (!live) throw new Error(`A class for course ${COURSE_ID} is running but couldn't be found: end it on the attendance page, or pass -e SESSION_ID=...`);
      sessionId = live.id;
      console.log(`Using the class already running for course ${COURSE_ID} (session ${sessionId}).`);
    } else {
      throw new Error(`Starting the class failed: HTTP ${opened.status} ${String(opened.body).slice(0, 300)}`);
    }
  }
  const detail = http.get(`${ATT}/manage/sessions/${sessionId}`, h);
  const d = json(detail) || {};
  if (d.phase !== 'CHECK_IN') console.warn(`The class is in phase ${d.phase}: check-in isn't open, so check-ins will be refused.`);
  console.log(`Class ${sessionId}: ${d.title || ''}, ${d.rosterSize} students on the list, check-in closes in ${d.phaseSecondsLeft} s.`);
  if (d.phaseSecondsLeft != null && d.phaseSecondsLeft < ARRIVE + 30)
    console.warn(`Check-in closes in ${d.phaseSecondsLeft} s but students arrive over ${ARRIVE} s: lower ARRIVE.`);
  return { token, sessionId, started, endAt: Date.now() + MINUTES * 60 * 1000 };
}

// ── Lecturer: the session page and the projector ────────────────────────────

export function lecturerList(data) {
  const h = { headers: headers(LECTURER_IP, data.token), tags: { kind: 'attendance', step: 'class-list' } };
  while (Date.now() < data.endAt) {
    note(http.get(`${ATT}/manage/sessions/${data.sessionId}`, h), 'class list');
    sleep(5);
  }
}

export function projector(data) {
  const h = { headers: headers(LECTURER_IP, data.token), tags: { kind: 'attendance', step: 'projector' } };
  while (Date.now() < data.endAt) {
    note(http.get(`${ATT}/manage/sessions/${data.sessionId}/code`, h), 'projector');
    sleep(2);
  }
}

// ── Students ────────────────────────────────────────────────────────────────

/** A spot inside the room: within about 8 m of the centre, with a phone-like accuracy. */
function reading(goodness) {
  const r = 7e-5;
  return {
    latitude: LAT + (Math.random() * 2 - 1) * r,
    longitude: LNG + (Math.random() * 2 - 1) * r,
    accuracy: goodness === 'rough' ? 30 + Math.random() * 15 : 8 + Math.random() * 12,
  };
}

const deviceIdOf = idx => `lt-${students[idx].username}-phone`;   // the same "phone" every run (one phone per student)

export function student(data) {
  const idx = exec.scenario.iterationInTest;
  sleep(Math.random() * ARRIVE);
  const token = studentLogin(idx);
  if (!token) return;
  const ip = studentIp(idx);
  const tag = step => ({ headers: headers(ip, token), tags: { kind: 'attendance', step } });
  const deviceId = deviceIdOf(idx);
  const sid = data.sessionId;

  // The card opens: is a class open for me? (Registers for the course if the test student isn't on its list.)
  let openRes = http.get(`${ATT}/student/open`, tag('open'));
  if (!note(openRes, 'open classes')) return;
  if (!(json(openRes, 'sessions') || []).some(x => x.sessionId === sid)) {
    if (COURSE_ID) {
      note(http.post(`${AUTH}/registerCourse`, JSON.stringify({ category: { cid: Number(COURSE_ID) } }),
        { headers: headers(ip, token), tags: { kind: 'setup' } }), `register ${students[idx].username} for the course`);
      openRes = http.get(`${ATT}/student/open`, tag('open'));
    }
    if (!(json(openRes, 'sessions') || []).some(x => x.sessionId === sid)) {
      stepsFailed.add(1);
      console.warn(`${students[idx].username} doesn't see class ${sid} (not on its list?)`);
      return;
    }
  }

  // The phone settles: a rough reading, then a better one a few seconds later
  const locate = goodness => {
    const res = http.post(`${ATT}/student/locate`, JSON.stringify({ sessionId: sid, ...reading(goodness), deviceId }), tag('locate'));
    note(res, 'send location');
    return json(res, 'fit');
  };
  locate('rough');
  sleep(4 + Math.random() * 4);
  let fit = locate('good');

  // Type the code showing on the projector (read here with the lecturer's sign-in), then check in.
  // A code that changed while being typed is refused as expired, so the student types the new one.
  let done = false;
  for (let attempt = 0; attempt < 3 && !done; attempt++) {
    const code = json(http.get(`${ATT}/manage/sessions/${sid}/code`,
      { headers: headers(LECTURER_IP, data.token), tags: { kind: 'helper' } }), 'code');
    if (!code) { sleep(2); continue; }
    sleep(1 + Math.random() * 2);                        // typing
    const res = http.post(`${ATT}/student/check-in`,
      JSON.stringify({ sessionId: sid, code, ...reading('good'), deviceId }), tag('check-in'));
    if (!note(res, 'check in')) return;
    const r = json(res) || {};
    if (r.ok) { checkedIn.add(1); done = true; break; }
    if (r.reason === 'EXPIRED_CODE' || r.reason === 'WRONG_CODE') { codeRetries.add(1); continue; }
    refused.add(1);
    stepsFailed.add(1);
    console.warn(`check in ${students[idx].username} refused: ${r.reason} ${String(r.message || '').slice(0, 120)}`);
    break;
  }
  if (!done) return;

  // The card stays open for the rest of the class
  let nextOpen = Date.now() + 15000;
  let nextLocate = Date.now() + (fit === 'INSIDE' ? 60000 : 20000);
  while (Date.now() < data.endAt) {
    sleep(1 + Math.random());
    if (Date.now() >= nextOpen) { note(http.get(`${ATT}/student/open`, tag('open')), 'open classes'); nextOpen = Date.now() + 15000; }
    if (Date.now() >= nextLocate) { fit = locate('good'); nextLocate = Date.now() + (fit === 'INSIDE' ? 60000 : 20000); }
  }
}

/** Students elsewhere with the dashboard open: their attendance card asks every 60 s. */
export function bystander(data) {
  const idx = STUDENTS + exec.scenario.iterationInTest;
  sleep(Math.random() * 60);
  const token = studentLogin(idx);
  if (!token) return;
  const h = { headers: headers(studentIp(idx), token), tags: { kind: 'attendance', step: 'dashboard' } };
  while (Date.now() < data.endAt) {
    note(http.get(`${ATT}/student/open`, h), 'dashboard card');
    sleep(55 + Math.random() * 10);
  }
}

// ── After: how many got in, then end the class ──────────────────────────────

export function teardown(data) {
  const h = { headers: headers(LECTURER_IP, data.token), tags: { kind: 'setup' } };
  const d = json(http.get(`${ATT}/manage/sessions/${data.sessionId}`, h)) || {};
  console.log(`Class ${data.sessionId} counts: ${JSON.stringify(d.counts || {})} of ${d.rosterSize} on the list.`);
  if (data.started && String(__ENV.KEEP || '').toLowerCase() !== 'yes') {
    const ended = http.post(`${ATT}/manage/sessions/${data.sessionId}/end`, null, h);
    console.log(ended.status === 200 ? `Class ${data.sessionId} ended.` : `Ending class ${data.sessionId} failed: HTTP ${ended.status}`);
  }
}
