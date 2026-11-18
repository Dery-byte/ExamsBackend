// Load test: a class of students sitting one quiz, optionally while other students download course files.
//
// Run (PowerShell, from this folder):
//   k6 run -e QUIZ_ID=123 -e STUDENTS=150 -e MINUTES=10 exam-load.js
//   k6 run -e QUIZ_ID=123 -e STUDENTS=150 -e DOWNLOADERS=30 -e FILE_ID=<id> -e MINUTES=10 exam-load.js
//
// Settings (-e NAME=value):
//   BASE         server address (default https://examsbackend.onrender.com)
//   CSV          student accounts file, "username,password" per line (default students.csv)
//   QUIZ_ID      the test quiz (objective questions only, so no AI marking is paid for)
//   STUDENTS     how many students sit the quiz (default: every row in the CSV)
//   MINUTES      how long each student stays in the quiz (default 10)
//   ARRIVE       seconds over which students arrive and sign in (default 60)
//   DOWNLOADERS  extra students who keep downloading a course file (default 0); they use the CSV
//                rows AFTER the quiz students, because signing in again signs the other session out
//   FILE_ID      the course-material file they download (see README)
//   SUBMIT       "no" leaves the attempts open instead of submitting them (default yes)

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const BASE = (__ENV.BASE || 'https://examsbackend.onrender.com').replace(/\/$/, '');
const AUTH = `${BASE}/api/v1/auth`;
const API = `${BASE}/api`;
const QUIZ_ID = __ENV.QUIZ_ID;
const MINUTES = Number(__ENV.MINUTES || 10);
const ARRIVE = Number(__ENV.ARRIVE || 60);
const DOWNLOADERS = Number(__ENV.DOWNLOADERS || 0);
const FILE_ID = __ENV.FILE_ID;
const SUBMIT = (__ENV.SUBMIT || 'yes').toLowerCase() !== 'no';

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
    exam_steps_failed: ['count<5'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

// Each simulated student gets its own made-up address, so the per-address sign-in limit
// (30 per 10 minutes) doesn't refuse a whole class that comes from this one computer.
function headers(idx, token) {
  const h = { 'Content-Type': 'application/json', 'X-Forwarded-For': `10.77.${(idx >> 8) & 255}.${idx & 255}` };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

function note(res, what, kind) {
  if (res.status >= 500 || res.status === 0) poolTimeouts.add(1);
  const ok = check(res, { [`${what} ok`]: r => r.status >= 200 && r.status < 300 });
  if (!ok) {
    if (kind === 'exam') examFailures.add(1);
    console.warn(`${what} → HTTP ${res.status} ${String(res.body || '').slice(0, 160)}`);
  }
  return ok;
}

function login(idx, kind) {
  const s = students[idx];
  const res = http.post(`${AUTH}/authenticate`, JSON.stringify({ username: s.username, password: s.password }),
    { headers: headers(idx), tags: { kind, step: 'login' } });
  if (!note(res, `sign in ${s.username}`, kind)) return null;
  try { return res.json('token'); } catch (e) { return null; }
}

export function exam() {
  const idx = exec.scenario.iterationInTest;            // 0 .. STUDENTS-1
  sleep(Math.random() * ARRIVE);                         // students don't all arrive in the same second

  const token = login(idx, 'exam');
  if (!token) return;
  const p = { headers: headers(idx, token), tags: { kind: 'exam' } };
  const tag = step => ({ headers: p.headers, tags: { kind: 'exam', step } });

  if (!note(http.post(`${AUTH}/quiz-attempts/${QUIZ_ID}/begin`, null, tag('begin')), 'begin', 'exam')) return;
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
  while (Date.now() < end) {
    sleep(8 + Math.random() * 12);                       // a student answers every 8–20 s
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
      nextCheckpoint += 60000;
    }
  }

  if (!SUBMIT) return;
  const objective = (data.questions || []).map(q => ({ quesId: q.quesId, givenAnswer: answers[q.quesId] || [''] }));
  note(http.post(`${AUTH}/eval-quiz/${QUIZ_ID}`, JSON.stringify(objective), tag('submit')), 'submit', 'exam');
  note(http.post(`${AUTH}/quiz-attempts/${QUIZ_ID}/finish`, null, tag('finish')), 'finish', 'exam');
}

export function downloads() {
  const idx = STUDENTS + exec.scenario.iterationInTest;  // rows after the quiz students
  sleep(Math.random() * ARRIVE);
  const token = login(idx, 'download');
  if (!token) return;
  const p = { headers: headers(idx, token), tags: { kind: 'download' } };
  const end = Date.now() + MINUTES * 60 * 1000;
  while (Date.now() < end) {
    const link = http.post(`${API}/materials/student/files/${FILE_ID}/link?inline=false`, null, p);
    if (note(link, 'download link', 'download')) {
      const url = link.json('url');
      const file = http.get(`${API.replace(/\/api$/, '')}${url.startsWith('/api') ? url : '/api' + url}`,
        { headers: { 'X-Forwarded-For': p.headers['X-Forwarded-For'] }, responseType: 'none', timeout: '600s', tags: { kind: 'download', step: 'file' } });
      note(file, 'download file', 'download');
    }
    sleep(5 + Math.random() * 10);
  }
}
