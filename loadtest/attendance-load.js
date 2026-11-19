// Load test: a class checking in to attendance at the same time, the way the real pages do it, for each way
// a lecturer can run attendance (-e METHOD=...):
//
//   TYPED         students type the code shown on the projector. Lecturer: session page (class list every
//                 5 s, code every 2 s) and the projector screen (code every 2 s).
//   READ_ALOUD    the lecturer reads the code out; every check-in waits for approval. Lecturer: session page
//                 (list 5 s, code 2 s), approving the waiting check-ins about once a minute.
//   STUDENT_SCAN  students scan the class QR code on the projector or on a helper's phone. Lecturer: session
//                 page and projector (2 s); helpers' phones refresh the QR every 2 s.
//   STAFF_SCAN    the lecturer and helpers scan each student's changing QR code. Each student's phone first
//                 gets its QR key (after the location and phone checks), then draws the code every 10 s and
//                 refreshes the card every 5 s until scanned. Lecturer: session page (5 s) and the scanner page
//                 (5 s); helpers' scanner pages refresh every 5 s. Scanners each take SCAN_SECONDS per student.
//                 (k6 can't pass a code from one simulated person to another, so the scan request is sent from
//                 the student's own script, with the scanner's sign-in or helper link: the server sees the same.)
//
//   Every student: arrives over ARRIVE seconds, opens the attendance card ("is a class open?"), sends their
//   location a couple of times while the phone settles, checks in the method's way, then keeps the card open:
//   "is a class open?" every 15 s and location every 20 s (60 s once it fits).
//   Bystanders (optional): other students sitting on their dashboards, whose card asks every 60 s.
//
// Run (PowerShell, from this folder):
//   k6 run -e LECTURER_USER=... -e LECTURER_PASS=... -e COURSE_ID=52 -e STUDENTS=200 -e METHOD=STAFF_SCAN -e HELPERS=2 attendance-load.js
//
// Settings (-e NAME=value):
//   BASE           server address (default https://examsbackendfrantfurt.onrender.com)
//   CSV            student accounts, "username,password" per line (default students.csv)
//   LECTURER_USER, LECTURER_PASS  a lecturer who teaches COURSE_ID. Use a test account nobody is signed
//                  in to: signing in here signs it out everywhere else.
//   COURSE_ID      the course the class is for (its department must take attendance per course)
//   METHOD         TYPED (default), READ_ALOUD, STUDENT_SCAN or STAFF_SCAN (see above)
//   HELPERS        helpers for the QR methods (0–10, default 0): phones showing the class QR, or scanning
//   SCAN_SECONDS   STAFF_SCAN: seconds a scanner takes per student (default 3)
//   STUDENTS       how many students check in (default 300)
//   BYSTANDERS     extra students only sitting on their dashboard (default 0); CSV rows after STUDENTS
//   ARRIVE         seconds over which students arrive (default 180; check-in stays open 15 min by default)
//   MINUTES        how long the class runs in the test (default 10)
//   LAT, LNG       where the made-up classroom is (default a spot in Accra); students are placed inside it
//   SESSION_ID     use a class that is already running instead of starting one (then it is left running)
//   KEEP           "yes" leaves the class running at the end (default: the test deletes the class it started)
//   ALLOW_REAL_STUDENTS  "yes" to run against a course with real students on its list (default: refused)
//   NEW_PASSWORD   the test students' password after their first sign-in (default LoadTest#2026)
//   PIN_IP         connect to this address instead of asking the network's DNS (see exam-load.js)

import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
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

// How each method starts the class (server's method + code display)
const METHODS = {
  TYPED: { method: 'STUDENT_CODE', display: 'PROJECTOR' },
  READ_ALOUD: { method: 'STUDENT_CODE', display: 'READ_ALOUD' },
  STUDENT_SCAN: { method: 'STUDENT_SCAN', display: 'PROJECTOR' },
  STAFF_SCAN: { method: 'STAFF_SCAN', display: 'PROJECTOR' },
};
const METHOD = String(__ENV.METHOD || 'TYPED').toUpperCase();
const QR = METHOD === 'STUDENT_SCAN' || METHOD === 'STAFF_SCAN';
const HELPERS = Math.max(0, Math.min(10, Number(__ENV.HELPERS || 0)));
const SCAN_SECONDS = Number(__ENV.SCAN_SECONDS || 3);

const students = new SharedArray('students', () => {
  const rows = open(__ENV.CSV || './students.csv').split(/\r?\n/)
    .map(l => l.trim()).filter(Boolean)
    .map(l => l.split(/[,;\t]/).map(s => s.trim().replace(/^"|"$/g, '')));
  if (rows.length && /pass/i.test(rows[0][1] || '')) rows.shift();
  return rows.filter(r => r[0] && r[1]).map(r => ({ username: r[0], password: r[1] }));
});

if (!METHODS[METHOD]) throw new Error(`METHOD must be TYPED, READ_ALOUD, STUDENT_SCAN or STAFF_SCAN (not ${METHOD}).`);
if (HELPERS > 0 && !QR) throw new Error('Helpers are only for the QR methods (STUDENT_SCAN, STAFF_SCAN).');
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

// ── Who is on the page, per method ──────────────────────────────────────────

const run = `${MINUTES + 5}m`;
const one = exec => ({ executor: 'per-vu-iterations', exec, vus: 1, iterations: 1, maxDuration: run });
const scenarios = {
  students: { executor: 'per-vu-iterations', exec: 'student', vus: STUDENTS, iterations: 1, maxDuration: run },
  sessionPage: one('sessionPage'),                                   // class list every 5 s (approvals when read aloud)
};
if (METHOD !== 'STAFF_SCAN') scenarios.codePanel = one('codePanel');            // the session page's code, every 2 s
if (METHOD === 'TYPED' || METHOD === 'STUDENT_SCAN') scenarios.projector = one('projector');   // projector screen, every 2 s
if (METHOD === 'STAFF_SCAN') scenarios.scannerPage = one('scannerPage');        // lecturer's scanner page, every 5 s
if (HELPERS > 0) scenarios.helperPhones = { executor: 'per-vu-iterations', exec: 'helperPhone', vus: HELPERS, iterations: 1, maxDuration: run };
if (BYSTANDERS > 0) scenarios.bystanders = { executor: 'per-vu-iterations', exec: 'bystander', vus: BYSTANDERS, iterations: 1, maxDuration: run };

export const options = {
  scenarios,
  setupTimeout: '120s',
  teardownTimeout: '120s',
  thresholds: {
    'http_req_failed{kind:attendance}': ['rate<0.02'],
    'http_req_duration{kind:attendance}': ['p(95)<3000'],
    [`http_req_duration{step:${METHOD === 'STAFF_SCAN' ? 'scan' : 'check-in'}}`]: ['p(95)<3000'],
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
const helperIp = i => `10.79.1.${i + 1}`;

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

/** The method a running class uses, in this script's words. */
const methodOf = d => d.method === 'STUDENT_CODE' ? (d.codeDisplay === 'READ_ALOUD' ? 'READ_ALOUD' : 'TYPED') : d.method;

// ── Before: the lecturer signs in, starts the class and adds the helpers ────

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
      codeDisplay: METHODS[METHOD].display, method: METHODS[METHOD].method,
      durationMinutes: Math.max(15, MINUTES + 5), topic: `Load test (${METHOD})`, secondCheck: false,
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
  const d = json(http.get(`${ATT}/manage/sessions/${sessionId}`, h)) || {};

  // Never run against a real class: ending it would mark every real student absent
  const real = (d.students || []).filter(x => !/^LOADTEST/i.test(String(x.indexNo || '')));
  if (real.length > 0 && String(__ENV.ALLOW_REAL_STUDENTS || '').toLowerCase() !== 'yes') {
    if (started) http.del(`${ATT}/manage/sessions/${sessionId}`, null, h);   // started by mistake: leave no trace
    throw new Error(`Course ${COURSE_ID} has ${real.length} real students on its list (e.g. ${real[0].indexNo}). `
      + 'Use a test course whose list has only LOADTEST students. ' + (started ? 'The class this test started has been deleted.' : 'The running class was left alone.'));
  }
  if (methodOf(d) !== METHOD) {
    throw new Error(`The class already running for this course uses ${methodOf(d)}, not ${METHOD}. `
      + `End it on the attendance page, or run with -e METHOD=${methodOf(d)}.`);
  }

  // Helpers (QR methods): each gets a link, used here by its token
  const helpers = [];
  for (let i = 0; i < HELPERS; i++) {
    const added = http.post(`${ATT}/manage/sessions/${sessionId}/helpers`, JSON.stringify({ label: `Load test helper ${i + 1}` }), h);
    if (added.status !== 200) throw new Error(`Adding helper ${i + 1} failed: HTTP ${added.status} ${String(added.body).slice(0, 200)}`);
    helpers.push({ id: json(added, 'id'), token: json(added, 'token') });
  }

  if (d.phase !== 'CHECK_IN') console.warn(`The class is in phase ${d.phase}: check-in isn't open, so check-ins will be refused.`);
  console.log(`Class ${sessionId} (${METHOD}${HELPERS ? `, ${HELPERS} helpers` : ''}): ${d.title || ''}, ${d.rosterSize} students on the list, `
    + `check-in closes in ${d.phaseSecondsLeft} s.`);
  if (METHOD === 'STAFF_SCAN') {
    const scanSeconds = Math.ceil(STUDENTS / (1 + HELPERS)) * SCAN_SECONDS + 20;
    console.log(`Scanning ${STUDENTS} students with ${1 + HELPERS} scanner(s) takes about ${Math.round(scanSeconds / 60)} min.`);
    if (scanSeconds > MINUTES * 60) console.warn(`That is longer than the ${MINUTES}-minute test: add helpers (-e HELPERS=2) or minutes.`);
  }
  if (d.phaseSecondsLeft != null && d.phaseSecondsLeft < ARRIVE + 30)
    console.warn(`Check-in closes in ${d.phaseSecondsLeft} s but students arrive over ${ARRIVE} s: lower ARRIVE.`);
  return { token, sessionId, started, helpers, startedAt: Date.now(), endAt: Date.now() + MINUTES * 60 * 1000 };
}

// ── Staff pages and helpers' phones ─────────────────────────────────────────

function every(data, seconds, fn) {
  while (Date.now() < data.endAt) { fn(); sleep(seconds); }
}

/** The session page's class list; read aloud, the lecturer also approves the waiting check-ins about once a minute. */
export function sessionPage(data) {
  const h = step => ({ headers: headers(LECTURER_IP, data.token), tags: { kind: 'attendance', step } });
  let nextApproval = Date.now() + 60000;
  every(data, 5, () => {
    note(http.get(`${ATT}/manage/sessions/${data.sessionId}`, h('class-list')), 'class list');
    if (METHOD === 'READ_ALOUD' && Date.now() >= nextApproval) {
      note(http.post(`${ATT}/manage/sessions/${data.sessionId}/approve-pending`, null, h('approve')), 'approve waiting check-ins');
      nextApproval = Date.now() + 60000;
    }
  });
}

/** The code on the session page (every method but staff scanning). */
export function codePanel(data) {
  const h = { headers: headers(LECTURER_IP, data.token), tags: { kind: 'attendance', step: 'code-panel' } };
  every(data, 2, () => note(http.get(`${ATT}/manage/sessions/${data.sessionId}/code`, h), 'session page code'));
}

/** The projector screen: the code, or the class QR code. */
export function projector(data) {
  const h = { headers: headers(LECTURER_IP, data.token), tags: { kind: 'attendance', step: 'projector' } };
  every(data, 2, () => note(http.get(`${ATT}/manage/sessions/${data.sessionId}/code`, h), 'projector'));
}

/** The lecturer's scanner page (staff scanning). */
export function scannerPage(data) {
  const h = { headers: headers(LECTURER_IP, data.token), tags: { kind: 'attendance', step: 'scanner-page' } };
  every(data, 5, () => note(http.get(`${ATT}/manage/sessions/${data.sessionId}/code`, h), 'scanner page'));
}

/** A helper's phone: showing the class QR (refreshed every 2 s) or the scanner page (every 5 s). No sign-in: a link. */
export function helperPhone(data) {
  const i = exec.scenario.iterationInTest;
  const helper = data.helpers[i];
  if (!helper) return;
  const h = { headers: headers(helperIp(i)), tags: { kind: 'attendance', step: 'helper-page' } };
  every(data, METHOD === 'STUDENT_SCAN' ? 2 : 5, () => note(http.get(`${ATT}/scan/${helper.token}`, h), 'helper page'));
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

/** The student's changing QR code, drawn exactly as the phone does (verified against the server's AttendanceQr). */
function studentQr(k, offsetMs) {
  const step = Math.floor((Date.now() + offsetMs) / 10000);
  const sig = crypto.hmac('sha256', encoding.b64decode(k.key, 'rawurl'), `${k.sessionId}.${k.studentId}.${step}`, 'binary');
  return `A1.${k.sessionId}.${k.studentId}.${step}.${encoding.b64encode(sig, 'rawurl').slice(0, 16)}`;
}

export function student(data) {
  const idx = exec.scenario.iterationInTest;
  sleep(Math.random() * ARRIVE);
  const token = studentLogin(idx);
  if (!token) return;
  const ip = studentIp(idx);
  const tag = step => ({ headers: headers(ip, token), tags: { kind: 'attendance', step } });
  const deviceId = deviceIdOf(idx);
  const sid = data.sessionId;
  const lecturer = { headers: headers(LECTURER_IP, data.token), tags: { kind: 'helper' } };   // test-only reads, outside the thresholds

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

  const done = METHOD === 'STAFF_SCAN' ? beScanned(idx, data, tag, deviceId, lecturer) : checkInByCode(idx, data, tag, deviceId, lecturer);
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

/**
 * TYPED / READ_ALOUD: type the code (read here with the lecturer's sign-in). STUDENT_SCAN: scan the class QR on
 * the projector or on a helper's phone, which carries the code and whose screen it was. A code that changed in
 * the meantime is refused as expired, so the student tries the new one.
 */
function checkInByCode(idx, data, tag, deviceId, lecturer) {
  const sid = data.sessionId;
  for (let attempt = 0; attempt < 3; attempt++) {
    let code, source;
    if (METHOD === 'STUDENT_SCAN') {
      const screen = Math.floor(Math.random() * (data.helpers.length + 1));    // 0 = projector, else a helper's phone
      const qr = screen === 0
        ? json(http.get(`${ATT}/manage/sessions/${sid}/code`, lecturer), 'qr')
        : json(http.get(`${ATT}/scan/${data.helpers[screen - 1].token}`, { headers: headers(helperIp(screen - 1)), tags: { kind: 'helper' } }), 'qr');
      const p = String(qr || '').split('.');                                   // AQ1.<session>.<check>.<code>.<source>
      if (p.length !== 5) { sleep(2); continue; }
      code = p[3];
      source = p[4];
      sleep(0.5 + Math.random());                                               // camera reads it, page sends it
    } else {
      code = json(http.get(`${ATT}/manage/sessions/${sid}/code`, lecturer), 'code');
      if (!code) { sleep(2); continue; }
      sleep(1 + Math.random() * 2);                                             // typing
    }
    const res = http.post(`${ATT}/student/check-in`,
      JSON.stringify({ sessionId: sid, code, ...(source ? { source } : {}), ...reading('good'), deviceId }), tag('check-in'));
    if (!note(res, 'check in')) return false;
    const r = json(res) || {};
    if (r.ok) { checkedIn.add(1); return true; }
    if (r.reason === 'EXPIRED_CODE' || r.reason === 'WRONG_CODE') { codeRetries.add(1); continue; }
    refused.add(1);
    stepsFailed.add(1);
    console.warn(`check in ${students[idx].username} refused: ${r.reason} ${String(r.message || '').slice(0, 120)}`);
    return false;
  }
  return false;
}

/**
 * STAFF_SCAN: the phone gets its QR key (location and phone checks), shows the changing code and refreshes the
 * card every 5 s while waiting. Scanners (lecturer + helpers) each take SCAN_SECONDS per student, so student n
 * is scanned by scanner n mod scanners, in turn n div scanners.
 */
function beScanned(idx, data, tag, deviceId, lecturer) {
  const sid = data.sessionId;
  const keyRes = http.post(`${ATT}/student/qr-key`, JSON.stringify({ sessionId: sid, ...reading('good'), deviceId }), tag('qr-key'));
  if (!note(keyRes, 'get QR key')) return false;
  const k = json(keyRes) || {};
  if (!k.ok || !k.key) {
    refused.add(1);
    stepsFailed.add(1);
    console.warn(`QR key for ${students[idx].username} refused: ${k.reason} ${String(k.message || '').slice(0, 120)}`);
    return false;
  }
  const offsetMs = (k.serverTime || Date.now()) - Date.now();

  const scanners = 1 + data.helpers.length;
  const scanner = idx % scanners;
  const turnAt = data.startedAt + 20000 + Math.floor(idx / scanners) * SCAN_SECONDS * 1000;
  while (Date.now() < turnAt && Date.now() < data.endAt) {                       // the QR screen refreshes the card
    sleep(Math.min(5, Math.max(0.5, (turnAt - Date.now()) / 1000)));
    if (Date.now() < turnAt) note(http.get(`${ATT}/student/open`, tag('open')), 'open classes');
  }

  for (let attempt = 0; attempt < 2; attempt++) {
    const code = studentQr(k, offsetMs);
    const res = scanner === 0
      ? http.post(`${ATT}/manage/sessions/${sid}/scan`, JSON.stringify({ code }),
          { headers: headers(LECTURER_IP, data.token), tags: { kind: 'attendance', step: 'scan' } })
      : http.post(`${ATT}/scan/${data.helpers[scanner - 1].token}`, JSON.stringify({ code }),
          { headers: headers(helperIp(scanner - 1)), tags: { kind: 'attendance', step: 'scan' } });
    if (!note(res, 'scan student')) return false;
    const r = json(res) || {};
    if (r.ok) { checkedIn.add(1); return true; }
    if (r.outcome === 'EXPIRED') { codeRetries.add(1); sleep(1); continue; }
    refused.add(1);
    stepsFailed.add(1);
    console.warn(`scan of ${students[idx].username} refused: ${r.outcome} ${String(r.message || '').slice(0, 120)}`);
    return false;
  }
  return false;
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

// ── After: how many got in, then delete the class ───────────────────────────

export function teardown(data) {
  const h = { headers: headers(LECTURER_IP, data.token), tags: { kind: 'setup' } };
  const d = json(http.get(`${ATT}/manage/sessions/${data.sessionId}`, h)) || {};
  console.log(`Class ${data.sessionId} (${METHOD}) counts: ${JSON.stringify(d.counts || {})} of ${d.rosterSize} on the list.`);
  if (data.started && String(__ENV.KEEP || '').toLowerCase() !== 'yes') {
    // Deleted rather than ended, so test meetings don't pile up in the course's attendance and reports
    const gone = http.del(`${ATT}/manage/sessions/${data.sessionId}`, null, h);
    console.log(gone.status >= 200 && gone.status < 300 ? `Class ${data.sessionId} deleted.` : `Deleting class ${data.sessionId} failed: HTTP ${gone.status}`);
  }
}
