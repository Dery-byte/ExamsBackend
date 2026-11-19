# Exam load test

`exam-load.js` acts like a class of students sitting one quiz: they sign in over a minute, open the quiz, save an answer every 8 to 20 seconds, save the timer once a minute, then submit. It can also have extra students downloading a course file at the same time. The 2026-11-18 outage (database connection pool exhausted) is thought to have come from those downloads.

It runs against the live server, so run it when no real quiz is on.

## 1. One-time setup

1. **Install k6** in PowerShell, then close and reopen PowerShell:
   ```
   winget install k6 --source winget
   ```
2. **Test students.** Import `loadtest-students-import.csv` (in this folder) in Data Tools → Import → Students, with **"Email each new user" unticked**. It creates `LOADTEST001` to `LOADTEST300` in program `CSC`, level 200, semester 1, all with password `Load#Test1`. Upload the file as it is: opening and saving it in Excel drops the leading 0 of the phone numbers. `students.csv` already lists the same accounts for the script.
   Imported students must change their password before they can do anything, so on its first run the script changes each one to `LoadTest#2026` (`-e NEW_PASSWORD=...` to choose another). To sign in as a test student yourself after that, use `LoadTest#2026`.
   Each simulated student needs its own account: signing in again signs the other session out.
3. **Test quiz.** Make a quiz in the test course with **objective questions only** (theory would send every answer to the paid AI). Give it about 30 questions, a duration longer than the test (e.g. 30 min), and open it now. Its number (`QUIZ_ID`) is in the address bar when you open it. Each run uses up one attempt per student, so for a second run make a new quiz or allow more attempts.
4. **Test file (for the download test).** In the test course's materials, add a PDF of 5 to 15 MB and make it visible to students. To get its `FILE_ID`, sign in as a test student, press F12 → Network, download the file, and copy the id from the request `.../materials/download/<FILE_ID>?u=...`.

## 2. Before you run

- Deploy the fix and set `DB_LEAK_DETECTION_MS=20000` in Render.
- Open the Render **Logs** and **Metrics** tabs to watch during the run.

## 3. Run in three steps (PowerShell, in this folder)

```
cd D:\Projects\ExamProject\ExamServer_Springboot\loadtest

# a) Smoke test: 10 students for 3 minutes. Checks the setup works.
k6 run -e QUIZ_ID=123 -e STUDENTS=10 -e MINUTES=3 exam-load.js

# b) A full class: 150 students for 10 minutes. (Needs a fresh quiz or more attempts.)
k6 run -e QUIZ_ID=124 -e STUDENTS=150 -e MINUTES=10 exam-load.js

# c) The 17:00 situation: 150 in the quiz + 30 others downloading the file.
k6 run -e QUIZ_ID=125 -e STUDENTS=150 -e DOWNLOADERS=30 -e FILE_ID=abc123 -e MINUTES=10 exam-load.js
```

**Rule-breaking.** By default 30% of the quiz students break the rules (`-e VIOLATORS=50` for more, `0` for none), each 1 to "max violations" times. The script reads the quiz's own violation setup from the exam, as the page does, and prints it at the start (`Quiz 52 violation setup: …`):

| Quiz setting | What each violation sends |
|---|---|
| None | nothing |
| Temporary lock (DELAY_ONLY) | the event + a suspension, longer each time by the multiplier; never submits |
| Auto-submit (AUTOSUBMIT_ONLY) | the event only (a warning); the last one auto-submits |
| Lock, then submit (DELAY_AND_AUTOSUBMIT) | the event + a suspension; the last one auto-submits |

During a suspension the student stays signed in but can't answer (the page hides the questions), so no answers are saved; what is left is reported every 5 s (if suspensions pause while the student is away, the default), and a third of students reload the page part-way. To try another setup without changing the quiz: `-e VIOLATION_ACTION=DELAY_ONLY` (and `MAX_VIOLATIONS`, `DELAY`, `MULTIPLIER`). Afterwards the quiz's proctoring report shows the test students' violations.

Step (c) needs at least 180 rows in `students.csv`. The downloads use your internet: a 10-minute run can pull 1 to 2 GB on a fast line. Use a small file or fewer `DOWNLOADERS` if data costs money.

## 4. Reading the result

At the end k6 prints a summary. The run **passed** if every threshold has a ✓:

| Line | Good |
|---|---|
| `http_req_failed{kind:exam}` | under 2% |
| `http_req_duration{kind:exam}` p(95) | under 3 s |
| `server_errors_5xx` | under 5 |
| `exam_steps_failed` | under 5 |

During the run, the Render log should show **no** `Connection is not available, request timed out` errors. If you see `Apparent connection leak detected`, copy it with the 20 lines below it: that names the code still holding connections.

Any request that fails is also printed in the k6 output (`save answer → HTTP 500 ...`).

## 5. Afterwards

Delete the test quizzes and their attempts, and deactivate the test students (or keep them for the next test).

## Attendance test (`attendance-load.js`)

Acts like a class checking in at the same moment:
- **Lecturer:** a test lecturer starts a class for the course at a made-up spot. Their session page refreshes the class list every 5 s and the projector refreshes the code every 2 s.
- **Students:** they arrive over 3 minutes and open the attendance card, which asks whether a class is open. Each sends their location a couple of times, types the code on the projector and checks in from inside the room on their own (made-up) phone. The card then stays open, asking every 15 s and sending location every 20–60 s.
- **Bystanders (optional, `-e BYSTANDERS=200`):** students elsewhere sitting on their dashboard, whose card asks every 60 s.

**Before the first run:**
- **Test lecturer:** a lecturer account that teaches the test course and that nobody is signed in to. Signing in here signs it out everywhere else.
- **Attendance switched on** for the test course's department, set to take attendance per course.
- **Test students:** each gets its "phone" linked (`lt-LOADTEST001-phone` …). Phone links are reset on the session page, or left as they are for the next run.

```
# Smoke test: 10 students, 3 minutes
k6 run -e PIN_IP=216.24.57.16 -e LECTURER_USER=... -e LECTURER_PASS=... -e COURSE_ID=52 -e STUDENTS=10 -e ARRIVE=30 -e MINUTES=3 attendance-load.js

# A full class of 300 checking in
k6 run -e PIN_IP=216.24.57.16 -e LECTURER_USER=... -e LECTURER_PASS=... -e COURSE_ID=52 -e STUDENTS=300 -e MINUTES=10 attendance-load.js

# 200 checking in, while 100 others sit on their dashboards (the 300 test accounts are shared between them)
k6 run -e PIN_IP=216.24.57.16 -e LECTURER_USER=... -e LECTURER_PASS=... -e COURSE_ID=52 -e STUDENTS=200 -e BYSTANDERS=100 -e MINUTES=10 attendance-load.js
```

The test ends the class it started (`-e KEEP=yes` leaves it running). The end of the output prints the class's counts.

**The run passes if every threshold has a ✓:**
- `check-in` and `class-list` p(95) under 3 s;
- `server_errors_5xx` and `attendance_steps_failed` under 5.

`checked_in` should equal STUDENTS. `code_expired_retried` counts students whose code changed while they typed it and who then typed the new one. That's normal and not a failure.
