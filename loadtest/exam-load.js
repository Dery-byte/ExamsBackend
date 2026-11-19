// Load test: a class of students sitting one quiz, optionally while other students download course files.
//
// Run (PowerShell, from this folder):
//   k6 run -e QUIZ_ID=123 -e STUDENTS=150 -e MINUTES=10 exam-load.js
//   k6 run -e QUIZ_ID=123 -e STUDENTS=150 -e DOWNLOADERS=30 -e FILE_ID=<id> -e MINUTES=10 exam-load.js
//
// Settings (-e NAME=value):
//   BASE         server address (default https://examsbackendfrantfurt.onrender.com)
//   CSV          student accounts file, "username,password" per line (default students.csv)
//   QUIZ_ID      the test quiz (objective questions only, so no AI marking is paid for)
//   STUDENTS     how many students sit the quiz (default: every row in the CSV)
//   MINUTES      how long each student stays in the quiz (default 10)
//   ARRIVE       seconds over which students arrive and sign in (default 60)
//   DOWNLOADERS  extra students who keep downloading a course file (default 0); they use the CSV
//                rows AFTER the quiz students, because signing in again signs the other session out
//   FILE_ID      the course-material file they download (see README)
//   SUBMIT       "no" leaves the attempts open instead of submitting them (default yes)
//   NEW_PASSWORD the password each imported test student is given on first sign-in (default LoadTest#2026)
//   COURSE_ID    the test course's number: any test student not registered for it registers once
//                (needs "Students register for their own courses" switched on for the department)
//   QUIZ_PASSWORD the quiz's password, if it has one
//   VIOLATORS    percent of the quiz students who break the rules (switch tabs, screenshots…) (default 30).
//                Each breaks them 1 to "max violations" times at random moments. What happens then follows
//                the quiz's own setup, read from the exam like the real page does:
//                  NONE                  nothing is sent (the page ignores violations)
//                  DELAY_ONLY            a suspension every time, growing by the multiplier; never submits
//                  AUTOSUBMIT_ONLY       a warning (nothing sent but the event); the last one auto-submits
//                  DELAY_AND_AUTOSUBMIT  a suspension each time; the last one auto-submits
//                While suspended the student stays signed in but the page shows no questions, and (when the Super Admin set suspensions to
//                pause while the student is away) reports what is left every 5 s.
//   VIOLATION_ACTION, MAX_VIOLATIONS, DELAY, MULTIPLIER  override the quiz's settings for this run only

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const BASE = (__ENV.BASE || 'https://examsbackendfrantfurt.onrender.com').replace(/\/$/, '');
const AUTH = `${BASE}/api/v1/auth`;
const API = `${BASE}/api`;
const QUIZ_ID = __ENV.QUIZ_ID;
const MINUTES = Number(__ENV.MINUTES || 10);
const ARRIVE = Number(__ENV.ARRIVE || 60);
const DOWNLOADERS = Number(__ENV.DOWNLOADERS || 0);
const FILE_ID = __ENV.FILE_ID;
const SUBMIT = (__ENV.SUBMIT || 'yes').toLowerCase() !== 'no';
const NEW_PASSWORD = __ENV.NEW_PASSWORD || 'LoadTest#2026';   // what each test student's password becomes
const COURSE_ID = __ENV.COURSE_ID;          // the test course: students not yet registered for it register themselves once
const QUIZ_PASSWORD = __ENV.QUIZ_PASSWORD;  // the quiz's password, if it has one
const VIOLATORS = __ENV.VIOLATORS !== undefined ? Number(__ENV.VIOLATORS) : 30;
const VIOLATION_TYPES = ['tab-switch', 'window-blur', 'fullscreen-exit', 'screenshot'];

const students = new SharedArray('students', () => {
  const rows = open(__ENV.CSV || './students.csv').split(/\r?\n/)
    .map(l => l.trim()).filter(Boolean)
    .map(l => l.split(/[,;\t]/).map(s => s.trim().replace(/^"|"$/g, '')));
  if (rows.length && /pass/i.test(rows[0][1] || '')) rows.shift();   // header row
  return rows.filter(r => r[0] && r[1]).map(r => ({ username: r[0], password: r[1] }));
});

const STUDENTS = Number(__ENV.STUDENTS || (students.length - DOWNLOADERS));

if (!QUIZ_ID) throw new Error('Set the quiz: -e QUIZ_ID=123');
if (DOWNLOADERS > 0 && !FILE_ID) throw new Error('Set the file to download: -e FILE_ID=...');
if (students.length < STUDENTS + DOWNLOADERS)
  throw new Error(`students.csv has ${students.length} accounts; this run needs ${STUDENTS + DOWNLOADERS} (one per simulated student).`);

const poolTimeouts = new Counter('server_errors_5xx');
const examFailures = new Counter('exam_steps_failed');
const networkErrors = new Counter('network_errors_not_server');
const violationsSent = new Counter('violations');
const suspensions = new Counter('suspensions');
const autoSubmits = new Counter('auto_submits');

const scenarios = {
  exam: { executor: 'per-vu-iterations', exec: 'exam', vus: STUDENTS, iterations: 1, maxDuration: `${MINUTES + 10}m` },
};
if (DOWNLOADERS > 0) {
  scenarios.downloads = { executor: 'per-vu-iterations', exec: 'downloads', vus: DOWNLOADERS, iterations: 1, maxDuration: `${MINUTES + 10}m` };
}

export const options = {
  scenarios,
  thresholds: {
    // The test FAILS if any of these is broken
    'http_req_failed{kind:exam}': ['rate<0.02'],          // under 2% of exam requests fail
    'http_req_duration{kind:exam}': ['p(95)<3000'],        // 95% of exam requests answer within 3 s
    server_errors_5xx: ['count<5'],
    network_errors_not_server: ['count<5'],              // if this fails, the test computer's network is the problem
    exam_steps_failed: ['count<5'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
  // -e PIN_IP=216.24.57.16 connects straight to that address instead of asking the network's DNS
  // (a local DNS/firewall answered 192.0.2.1 for the server mid-test). The server name is still sent.
  ...(__ENV.PIN_IP ? { hosts: { [BASE.replace(/^https?:\/\//, '').replace(/[:/].*$/, '')]: __ENV.PIN_IP } } : {}),
};

// Each simulated student gets its own made-up address, so the per-address sign-in limit
// (30 per 10 minutes) doesn't refuse a whole class that comes from this one computer.
function headers(idx, token) {
  const h = { 'Content-Type': 'application/json', 'X-Forwarded-For': `10.77.${(idx >> 8) & 255}.${idx & 255}` };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

function note(res, what, kind) {
  if (res.status >= 500) poolTimeouts.add(1);
  if (res.status === 0) networkErrors.add(1);   // never reached the server (DNS, firewall, no internet)
  const ok = check(res, { [`${what} ok`]: r => r.status >= 200 && r.status < 300 });
  if (!ok) {
    if (kind === 'exam') examFailures.add(1);
    console.warn(`${what} → HTTP ${res.status} ${String(res.body || '').slice(0, 160)}`);
  }
  return ok;
}

function tryLogin(idx, password) {
  return http.post(`${AUTH}/authenticate`, JSON.stringify({ username: students[idx].username, password }),
    { headers: headers(idx), tags: { kind: 'signin' }, responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
}

// Imported students must choose a new password before they can do anything (the server answers 403
// PASSWORD_CHANGE_REQUIRED). The first run signs in with the CSV password and sets NEW_PASSWORD;
// later runs sign in with NEW_PASSWORD straight away, so no wrong-password attempts pile up
// (5 wrong ones lock an account for a while).
function login(idx, kind) {
  const s = students[idx];
  let res = tryLogin(idx, NEW_PASSWORD);
  // A dropped connection says nothing about the password: try the same one again rather than falling
  // back to the CSV password, which is wrong once it has been changed (and counts towards the lockout).
  for (let i = 0; i < 3 && (res.status === 0 || res.status >= 500); i++) {
    if (res.status === 0) networkErrors.add(1); else poolTimeouts.add(1);
    console.warn(`sign in ${s.username} → HTTP ${res.status}, trying again`);
    sleep(3 + Math.random() * 4);
    res = tryLogin(idx, NEW_PASSWORD);
  }
  if (res.status === 200) return res.json('token');
  if (res.status === 0 || res.status >= 500) { note(res, `sign in ${s.username}`, kind); return null; }

  res = tryLogin(idx, s.password);
  if (!note(res, `sign in ${s.username}`, kind)) return null;
  const token = res.json('token');
  const changed = http.put(`${AUTH}/updatepassword`, JSON.stringify({ password: NEW_PASSWORD, currentPassword: s.password }),
    { headers: headers(idx, token), tags: { kind: 'signin' } });
  note(changed, `set test password for ${s.username}`, kind);
  return token;
}

// Registers this student for the test course (only called after the server said they aren't registered,
// because registering twice makes two rows).
function register(idx, token) {
  if (!COURSE_ID) {
    console.warn(`${students[idx].username} is not registered for the course: add -e COURSE_ID=<number> so the script registers them`);
    return false;
  }
  const r = http.post(`${AUTH}/registerCourse`, JSON.stringify({ category: { cid: Number(COURSE_ID) } }),
    { headers: headers(idx, token), tags: { kind: 'setup' } });
  return note(r, `register ${students[idx].username} for the course`, 'setup');
}

// Starts the attempt like the exam page: registers for the course and enters the quiz password when needed.
function beginQuiz(idx, token) {
  const h = headers(idx, token);
  const tryBegin = () => http.post(`${AUTH}/quiz-attempts/${QUIZ_ID}/begin`, null,
    { headers: h, tags: { kind: 'setup' }, responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
  let r = tryBegin();
  if (r.status === 403 && /not enrolled/i.test(r.body) && register(idx, token)) r = tryBegin();
  if (r.status === 403 && /password/i.test(r.body)) {
    if (!QUIZ_PASSWORD) console.warn('The quiz has a password: add -e QUIZ_PASSWORD=...');
    else {
      note(http.post(`${AUTH}/quiz/${QUIZ_ID}/unlock`, JSON.stringify({ password: QUIZ_PASSWORD }),
        { headers: h, tags: { kind: 'exam', step: 'unlock' } }), 'unlock quiz', 'exam');
      r = tryBegin();
    }
  }
  return note(r, 'begin', 'exam');
}

export function exam() {
  const idx = exec.scenario.iterationInTest;            // 0 .. STUDENTS-1
  sleep(Math.random() * ARRIVE);                         // students don't all arrive in the same second

  const token = login(idx, 'exam');
  if (!token) return;
  const p = { headers: headers(idx, token), tags: { kind: 'exam' } };
  const tag = step => ({ headers: p.headers, tags: { kind: 'exam', step } });

  if (!beginQuiz(idx, token)) return;
  const session = http.get(`${AUTH}/quiz-attempts/${QUIZ_ID}/session`, tag('session'));
  if (!note(session, 'load exam', 'exam')) return;

  let data = {};
  try { data = session.json(); } catch (e) { /* counted above if not 2xx */ }
  const questions = (data.questions || []).filter(q => q.option1);
  let remaining = Number(data.timer && data.timer.remainingTime) || MINUTES * 60;

  http.patch(`${AUTH}/quiz-timer/saveRemainingTime/${QUIZ_ID}`, JSON.stringify({ remainingTime: remaining }), tag('timer'));

  const answers = {};
  const end = Date.now() + MINUTES * 60 * 1000;
  let nextCheckpoint = Date.now() + 60000;

  // Some students break the rules, at random moments after the first 30 s, and the quiz's setup decides what follows
  const r = rules(data);
  if (idx === 0) console.log(`Quiz ${QUIZ_ID} violation setup: ${r.action}, max ${r.max}, delay ${r.delay} s ×${r.multiplier}, `
    + `suspensions ${r.pauses ? 'pause' : 'keep running'} while away`);
  const planned = r.action !== 'NONE' && Math.random() * 100 < VIOLATORS ? 1 + Math.floor(Math.random() * r.max) : 0;
  const violationAt = [];
  for (let i = 0; i < planned; i++) violationAt.push(Date.now() + (30 + Math.random() * Math.max(30, MINUTES * 60 - 90)) * 1000);
  violationAt.sort((a, b) => a - b);
  let violationCount = 0;

  while (Date.now() < end) {
    sleep(8 + Math.random() * 12);                       // a student answers every 8–20 s
    if (violationAt.length && Date.now() >= violationAt[0]) {
      violationAt.shift();
      violationCount++;
      const outcome = violate(tag, violationCount, end, r);
      if (outcome === 'submitted') { autoSubmits.add(1); break; }
      if (outcome === 'locked') continue;                // a suspended student can't answer meanwhile (still signed in)
    }
    if (questions.length) {
      const q = questions[Math.floor(Math.random() * questions.length)];
      const opts = ['option1', 'option2', 'option3', 'option4'].map(k => q[k]).filter(Boolean);
      const pick = opts[Math.floor(Math.random() * opts.length)];
      answers[q.quesId] = [pick];
      note(http.post(`${AUTH}/quiz-progress/update-batch`,
        JSON.stringify([{ questionId: q.quesId, option: pick, checked: true, quizId: Number(QUIZ_ID) }]), tag('answer')),
        'save answer', 'exam');
    }
    if (Date.now() >= nextCheckpoint) {                  // timer checkpoint about once a minute
      remaining = Math.max(0, remaining - 60);
      note(http.patch(`${AUTH}/quiz-timer/saveRemainingTime/${QUIZ_ID}`, JSON.stringify({ remainingTime: remaining }), tag('timer')),
        'timer checkpoint', 'exam');
      nextCheckpoint = Date.now() + 60000;
    }
  }

  if (!SUBMIT) return;
  const objective = (data.questions || []).map(q => ({ quesId: q.quesId, givenAnswer: answers[q.quesId] || [''] }));
  note(http.post(`${AUTH}/eval-quiz/${QUIZ_ID}`, JSON.stringify(objective), tag('submit')), 'submit', 'exam');
  note(http.post(`${AUTH}/quiz-attempts/${QUIZ_ID}/finish`, null, tag('finish')), 'finish', 'exam');
}

/** The quiz's violation setup, from the exam the page loads (same defaults as the page), unless overridden. */
function rules(data) {
  const q = data.quiz || {};
  const pausesWhileAway = data.violationDelay ? data.violationDelay.pausesWhileAway : undefined;
  return {
    action: String(__ENV.VIOLATION_ACTION || q.violationAction || 'NONE').toUpperCase(),
    max: Math.max(1, Number(__ENV.MAX_VIOLATIONS || q.maxViolations || 3)),
    delay: Number(__ENV.DELAY || q.delaySeconds || 30),
    multiplier: Number(__ENV.MULTIPLIER || q.delayMultiplier || 1.5),
    pauses: pausesWhileAway === undefined || pausesWhileAway === null ? true : !!pausesWhileAway,
  };
}

/**
 * One violation, sent the way the exam page sends it (useQuizProtection.handleViolation): the event, which
 * also stores the count, then what the quiz's setup says. Returns 'submitted', 'locked' or 'warned'.
 * During a suspension the student stays signed in, the page shows no questions, reports what is left every
 * 5 s (when suspensions pause while away), and clears it at the end. A third include a page reload.
 */
function violate(tag, n, end, r) {
  const event = (type, number) => note(http.post(`${API}/proctoring/events`,
    JSON.stringify({ quizId: Number(QUIZ_ID), type, ...(number ? { violationNumber: number } : {}) }), tag('violation')),
    'record violation', 'exam');
  const saveLockout = (seconds, what) => note(http.post(`${AUTH}/quiz-timer/saveViolation-delay/${QUIZ_ID}`,
    JSON.stringify({ violationDelayTime: seconds }), tag('lockout')), what, 'exam');

  violationsSent.add(1);
  event(VIOLATION_TYPES[Math.floor(Math.random() * VIOLATION_TYPES.length)], n);
  const submits = r.action === 'AUTOSUBMIT_ONLY' || r.action === 'DELAY_AND_AUTOSUBMIT';
  if (submits && n >= r.max) {
    event('auto-submit');
    sleep(5);                                            // the page counts down before submitting
    return 'submitted';
  }
  if (r.action === 'AUTOSUBMIT_ONLY') return 'warned';  // a warning on screen only; the student carries on

  suspensions.add(1);
  const length = n > 1 ? Math.round(r.delay * Math.pow(r.multiplier, n - 1)) : r.delay;
  let left = Math.max(1, Math.min(length, Math.max(5, Math.floor((end - Date.now()) / 1000))));
  saveLockout(left, 'start suspension');
  const reloadAt = Math.floor(left / 2);
  let reloaded = Math.random() >= 1 / 3;
  while (left > 0) {
    const step = Math.min(5, left);
    sleep(step);
    left -= step;
    if (!reloaded && left > 0 && left <= reloadAt) {
      reloaded = true;
      note(http.get(`${AUTH}/quiz-attempts/${QUIZ_ID}/session`, tag('session')), 'reload during suspension', 'exam');
      note(http.get(`${AUTH}/quiz-timer/getViolation-delay/${QUIZ_ID}`, tag('lockout')), 'read suspension after reload', 'exam');
    }
    if (left > 0 && r.pauses) saveLockout(left, 'suspension progress');
  }
  saveLockout(0, 'end suspension');
  return 'locked';
}

export function downloads() {
  const idx = STUDENTS + exec.scenario.iterationInTest;  // rows after the quiz students
  sleep(Math.random() * ARRIVE);
  const token = login(idx, 'download');
  if (!token) return;
  const p = { headers: headers(idx, token), tags: { kind: 'download' } };
  const end = Date.now() + MINUTES * 60 * 1000;
  while (Date.now() < end) {
    const getLink = () => http.post(`${API}/materials/student/files/${FILE_ID}/link?inline=false`, null,
      { headers: p.headers, tags: { kind: 'download' }, responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
    let link = getLink();
    if (link.status === 403 && /registered/i.test(link.body) && register(idx, token)) link = getLink();
    if (note(link, 'download link', 'download')) {
      const url = link.json('url');
      const file = http.get(`${API.replace(/\/api$/, '')}${url.startsWith('/api') ? url : '/api' + url}`,
        { headers: { 'X-Forwarded-For': p.headers['X-Forwarded-For'] }, responseType: 'none', timeout: '600s', tags: { kind: 'download', step: 'file' } });
      note(file, 'download file', 'download');
    }
    sleep(5 + Math.random() * 10);
  }
}
