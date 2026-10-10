package com.exam.service.reports;

import com.exam.helper.IndexNumberRange;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.TheoryGradingJob;
import com.exam.model.examops.RemarkRequest;
import com.exam.service.examops.TimetableService;
import com.exam.service.reports.ReportQueries.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/** Exams: integrity (proctoring, voided attempts, re-marks), AI marking, and how many sit at once. */
@Service
@Transactional(readOnly = true)
public class ExamReports {

    /** Proctoring event type written when a quiz is submitted automatically. */
    static final String AUTO_SUBMIT = "auto-submit";
    /** Candidates sitting at once from which the exam load counts as high / very high. */
    static final int HIGH_LOAD = 150, VERY_HIGH_LOAD = 300;

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private TimetableService timetableService;

    // ── Exam integrity ───────────────────────────────────────────────────────

    public ReportResult examIntegrity(ReportFilters f) {
        Map<String, String> t = support.terms();
        String student = t.get("student");
        ReportResult r = new ReportResult("exam-integrity", "Exam integrity",
                "Proctoring violations, automatic submissions, voided attempts and re-mark requests, quiz by quiz.");
        Lookups l = support.lookups();
        Period p = support.period(f);
        support.periodScope(r, p);
        support.placeScope(r, f, l);

        Map<Long, StudentRow> students = queries.students().stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        List<AttemptRow> attempts = queries.attempts(p).stream().filter(a -> l.quizMatches(a.quizId(), f)).toList();
        List<ResultRow> results = queries.quizResults(p).stream().filter(x -> l.quizMatches(x.quizId(), f)).toList();
        List<EventRow> events = queries.proctoringEvents(p).stream().filter(e -> l.quizMatches(e.quizId(), f)).toList();
        List<RemarkRow> remarks = queries.remarks(p).stream().filter(x -> l.quizMatches(x.quizId(), f)).toList();

        Set<Long> quizIds = new TreeSet<>();
        attempts.forEach(a -> quizIds.add(a.quizId()));
        results.forEach(x -> quizIds.add(x.quizId()));
        events.forEach(e -> quizIds.add(e.quizId()));

        Map<Long, List<AttemptRow>> attemptsByQuiz = attempts.stream().collect(Collectors.groupingBy(AttemptRow::quizId));
        Map<Long, List<ResultRow>> resultsByQuiz = results.stream().collect(Collectors.groupingBy(ResultRow::quizId));
        Map<Long, List<EventRow>> eventsByQuiz = events.stream().collect(Collectors.groupingBy(EventRow::quizId));
        Map<Long, Long> remarksByQuiz = remarks.stream().collect(Collectors.groupingBy(RemarkRow::quizId, Collectors.counting()));

        Table tb = r.table("quizzes", "Quizzes")
                .col("date", "Date", DATE).text("course", t.get("course")).text("quiz", "Quiz").text("lecturer", t.get("lecturer"))
                .text("proctoring", "Proctoring").col("sat", student + "s who sat", INT).col("flagged", student + "s with violations", INT)
                .col("flaggedShare", "Share flagged", PERCENT).col("violations", "Violations", INT)
                .col("autoSubmitted", "Auto-submitted", INT).col("voided", "Voided attempts", INT).col("remarks", "Re-mark requests", INT);
        long totalSat = 0, totalViolations = 0, totalAuto = 0;
        Set<Long> flaggedStudents = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long qid : quizIds) {
            Quiz q = l.quiz(qid);
            Set<Long> sat = new HashSet<>();
            attemptsByQuiz.getOrDefault(qid, List.of()).stream().filter(a -> a.status() == AttemptStatus.SUBMITTED).forEach(a -> sat.add(a.studentId()));
            resultsByQuiz.getOrDefault(qid, List.of()).forEach(x -> sat.add(x.studentId()));
            List<EventRow> ev = eventsByQuiz.getOrDefault(qid, List.of());
            List<EventRow> violations = ev.stream().filter(e -> !AUTO_SUBMIT.equals(e.type())).toList();
            Set<Long> flagged = violations.stream().map(EventRow::studentId).collect(Collectors.toSet());
            long auto = ev.stream().filter(e -> AUTO_SUBMIT.equals(e.type())).map(EventRow::studentId).distinct().count();
            long voided = attemptsByQuiz.getOrDefault(qid, List.of()).stream().filter(a -> a.status() == AttemptStatus.VOIDED).count();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", q != null && q.getQuizDate() != null ? day(q.getQuizDate())
                    : ev.stream().map(EventRow::occurredAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).map(ReportSupport::day).orElse(null));
            row.put("course", q == null || q.getCategory() == null ? null : q.getCategory().getCourseCode());
            row.put("quiz", q == null ? "Quiz " + qid : q.getTitle());
            row.put("lecturer", name(Lookups.lecturerOf(q)));
            row.put("proctoring", q != null && Boolean.TRUE.equals(q.getProctoringEnabled()) ? "On" : "Off");
            row.put("sat", sat.size());
            row.put("flagged", flagged.size());
            row.put("flaggedShare", pct(flagged.size(), sat.size()));
            row.put("violations", violations.size());
            row.put("autoSubmitted", auto);
            row.put("voided", voided);
            row.put("remarks", remarksByQuiz.getOrDefault(qid, 0L));
            rows.add(row);
            totalSat += sat.size();
            totalViolations += violations.size();
            totalAuto += auto;
            flaggedStudents.addAll(flagged);
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> Objects.toString(m.get("date"), "")).reversed());
        rows.forEach(tb::add);

        Table types = r.table("types", "Violation types").text("type", "Type").col("events", "Events", INT)
                .col("students", student + "s", INT).chart("type", "events");
        events.stream().filter(e -> !AUTO_SUBMIT.equals(e.type()))
                .collect(Collectors.groupingBy(EventRow::type)).entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<EventRow>> e) -> e.getValue().size()).reversed())
                .forEach(e -> types.add(typeName(e.getKey()), e.getValue().size(),
                        e.getValue().stream().map(EventRow::studentId).distinct().count()));

        Table top = r.table("students", student + "s with the most violations")
                .subtitle("Top 100 in the period.")
                .text("studentId", t.get("studentId")).text("name", "Name").text("programme", t.get("program"))
                .col("quizzes", "Quizzes affected", INT).col("violations", "Violations", INT).col("autoSubmitted", "Auto-submitted", INT);
        Map<Long, List<EventRow>> byStudent = events.stream().collect(Collectors.groupingBy(EventRow::studentId));
        byStudent.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(x -> !AUTO_SUBMIT.equals(x.type())))
                .sorted(Comparator.comparing((Map.Entry<Long, List<EventRow>> e) ->
                        e.getValue().stream().filter(x -> !AUTO_SUBMIT.equals(x.type())).count()).reversed())
                .limit(100)
                .forEach(e -> {
                    StudentRow s = students.get(e.getKey());
                    List<EventRow> ev = e.getValue();
                    top.add(s == null ? null : s.username(), s == null ? null : s.name(), s == null ? null : l.programName(s.programId()),
                            ev.stream().filter(x -> !AUTO_SUBMIT.equals(x.type())).map(EventRow::quizId).distinct().count(),
                            ev.stream().filter(x -> !AUTO_SUBMIT.equals(x.type())).count(),
                            ev.stream().filter(x -> AUTO_SUBMIT.equals(x.type())).map(EventRow::quizId).distinct().count());
                });

        Table voided = r.table("voided", "Voided attempts")
                .subtitle("Attempts cancelled by staff so the " + student.toLowerCase() + " could sit again.")
                .col("voidedOn", "Voided on", DATETIME).text("studentId", t.get("studentId")).text("name", "Name")
                .text("course", t.get("course")).text("quiz", "Quiz").text("by", "Voided by").text("reason", "Reason")
                .emptyText("No attempts were voided.");
        attempts.stream().filter(a -> a.status() == AttemptStatus.VOIDED)
                .sorted(Comparator.comparing((AttemptRow a) -> a.voidedAt() == null ? LocalDateTime.MIN : a.voidedAt()).reversed())
                .forEach(a -> {
                    StudentRow s = students.get(a.studentId());
                    Quiz q = l.quiz(a.quizId());
                    voided.add(minute(a.voidedAt()), s == null ? null : s.username(), s == null ? null : s.name(),
                            q == null || q.getCategory() == null ? null : q.getCategory().getCourseCode(), q == null ? null : q.getTitle(),
                            a.voidedBy(), a.voidReason());
                });

        Table rm = r.table("remarks", "Re-mark requests").text("outcome", "Outcome").col("requests", "Requests", INT)
                .col("change", "Average score change", DECIMAL).col("days", "Average days to answer", DECIMAL);
        for (RemarkRequest.Status s : RemarkRequest.Status.values()) {
            List<RemarkRow> g = remarks.stream().filter(x -> x.status() == s).toList();
            rm.add(switch (s) { case PENDING -> "Waiting for an answer"; case RESOLVED -> "Re-marked"; case REJECTED -> "Turned down"; },
                    g.size(),
                    round1OrNull(g.stream().filter(x -> x.scoreBefore() != null && x.scoreAfter() != null)
                            .mapToDouble(x -> x.scoreAfter().subtract(x.scoreBefore()).doubleValue()).average()),
                    round1OrNull(g.stream().filter(x -> x.createdAt() != null && x.respondedAt() != null)
                            .mapToDouble(x -> Duration.between(x.createdAt(), x.respondedAt()).toHours() / 24.0).average()));
        }

        long pending = remarks.stream().filter(x -> x.status() == RemarkRequest.Status.PENDING).count();
        long voidedCount = attempts.stream().filter(a -> a.status() == AttemptStatus.VOIDED).count();
        r.stat("Quizzes", quizIds.size())
         .stat(student + "s who sat", totalSat, "Counted once per quiz", null)
         .stat(student + "s with violations", flaggedStudents.size(), null, flaggedStudents.isEmpty() ? null : "warn")
         .stat("Violations", totalViolations)
         .stat("Auto-submitted", totalAuto)
         .stat("Voided attempts", voidedCount)
         .stat("Re-marks waiting", pending, null, pending > 0 ? "warn" : null);
        r.note("Violations are what the exam page reported (leaving full screen, switching tabs, …). Auto-submitted counts "
                + student.toLowerCase() + "s whose quiz was submitted for them after too many violations.");
        return r;
    }

    static String typeName(String type) {
        if (type == null || type.isBlank()) return "Unknown";
        String s = type.replace('-', ' ').replace('_', ' ').trim();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── AI marking ───────────────────────────────────────────────────────────

    public ReportResult aiMarking(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("ai-marking", "AI marking",
                "Theory answers marked by AI: how many were marked, failed or are still waiting, and scripts "
                        + t.get("lecturer").toLowerCase() + "s have not reviewed yet.");
        Lookups l = support.lookups();
        Period p = support.period(f);
        support.periodScope(r, p);
        support.placeScope(r, f, l);

        Map<Long, StudentRow> students = queries.students().stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        List<JobRow> jobs = queries.gradingJobs(p).stream().filter(j -> l.quizMatches(j.quizId(), f)).toList();
        List<ResultRow> results = queries.quizResults(p).stream().filter(x -> l.quizMatches(x.quizId(), f)).toList();
        Map<Long, List<JobRow>> jobsByQuiz = jobs.stream().collect(Collectors.groupingBy(JobRow::quizId));
        Set<Long> aiQuizzes = new TreeSet<>(jobsByQuiz.keySet());
        results.stream().filter(x -> x.evaluationMethod() != null && !x.evaluationMethod().isBlank()).forEach(x -> aiQuizzes.add(x.quizId()));
        Map<Long, List<ResultRow>> resultsByQuiz = results.stream().filter(x -> aiQuizzes.contains(x.quizId()))
                .collect(Collectors.groupingBy(ResultRow::quizId));

        Table tb = r.table("quizzes", "Quizzes")
                .text("course", t.get("course")).text("quiz", "Quiz").text("lecturer", t.get("lecturer")).text("provider", "AI provider")
                .col("scripts", "Scripts", INT).col("jobs", "Sent for marking", INT).col("done", "Marked", INT)
                .col("failed", "Failed", INT).col("waiting", "Still marking", INT).col("tries", "Average tries", DECIMAL)
                .col("unreviewed", "Not yet reviewed", INT);
        long unreviewedTotal = 0;
        for (Long qid : aiQuizzes) {
            Quiz q = l.quiz(qid);
            List<JobRow> js = jobsByQuiz.getOrDefault(qid, List.of());
            List<ResultRow> rs = resultsByQuiz.getOrDefault(qid, List.of());
            long unreviewed = rs.stream().filter(x -> !x.reviewed()).count();
            unreviewedTotal += unreviewed;
            tb.add(q == null || q.getCategory() == null ? null : q.getCategory().getCourseCode(), q == null ? "Quiz " + qid : q.getTitle(),
                    name(Lookups.lecturerOf(q)), q == null || q.getLlmProvider() == null ? null : q.getLlmProvider().name(),
                    rs.size(), js.size(), count(js, TheoryGradingJob.Status.DONE), count(js, TheoryGradingJob.Status.FAILED),
                    count(js, TheoryGradingJob.Status.PENDING) + count(js, TheoryGradingJob.Status.RUNNING),
                    round1OrNull(js.stream().mapToInt(JobRow::tries).average()), unreviewed);
        }

        Table prov = r.table("providers", "By AI provider").text("provider", "Provider").col("quizzes", "Quizzes", INT)
                .col("jobs", "Sent for marking", INT).col("done", "Marked", INT).col("failed", "Failed", INT)
                .col("failureRate", "Failure rate", PERCENT).chart("provider", "jobs");
        jobs.stream().collect(Collectors.groupingBy(j -> {
            Quiz q = l.quiz(j.quizId());
            return q == null || q.getLlmProvider() == null ? "Not set" : q.getLlmProvider().name();
        }, TreeMap::new, Collectors.toList())).forEach((name, js) -> {
            long failed = count(js, TheoryGradingJob.Status.FAILED);
            prov.add(name, js.stream().map(JobRow::quizId).distinct().count(), js.size(), count(js, TheoryGradingJob.Status.DONE),
                    failed, pct(failed, js.size()));
        });

        Table failed = r.table("failed", "Failed marking")
                .subtitle(t.get("lecturer") + "s can retry these from the quiz's review page.")
                .col("queued", "Sent", DATETIME).text("course", t.get("course")).text("quiz", "Quiz")
                .text("studentId", t.get("studentId")).text("name", "Name").col("tries", "Tries", INT).text("error", "Last error")
                .emptyText("Nothing failed.");
        jobs.stream().filter(j -> j.status() == TheoryGradingJob.Status.FAILED)
                .sorted(Comparator.comparing((JobRow j) -> j.createdAt() == null ? LocalDateTime.MIN : j.createdAt()).reversed())
                .forEach(j -> {
                    Quiz q = l.quiz(j.quizId());
                    StudentRow s = students.get(j.studentId());
                    failed.add(minute(j.createdAt()), q == null || q.getCategory() == null ? null : q.getCategory().getCourseCode(),
                            q == null ? null : q.getTitle(), s == null ? null : s.username(), s == null ? null : s.name(), j.tries(), j.lastError());
                });

        long done = count(jobs, TheoryGradingJob.Status.DONE), fail = count(jobs, TheoryGradingJob.Status.FAILED);
        long waiting = count(jobs, TheoryGradingJob.Status.PENDING) + count(jobs, TheoryGradingJob.Status.RUNNING);
        r.stat("Sent for marking", jobs.size())
         .stat("Marked", done, null, "good")
         .stat("Failed", fail, null, fail > 0 ? "bad" : null)
         .stat("Still marking", waiting, null, waiting > 0 ? "warn" : null)
         .stat("Failure rate", AcademicReports.fmtPct(pct(fail, jobs.size())))
         .stat("Scripts not yet reviewed", unreviewedTotal, null, unreviewedTotal > 0 ? "warn" : null);
        r.note("Scripts = submitted quizzes with AI-marked theory answers. \"Not yet reviewed\" counts scripts a "
                + t.get("lecturer").toLowerCase() + " has not marked as reviewed.");
        r.note("When a " + t.get("lecturer").toLowerCase() + " changes an AI mark the AI's original mark is replaced, "
                + "so how often AI marks are changed cannot be shown yet.");
        return r;
    }

    private static long count(List<JobRow> jobs, TheoryGradingJob.Status s) {
        return jobs.stream().filter(j -> j.status() == s).count();
    }

    // ── Exam absentees ───────────────────────────────────────────────────────

    public ReportResult examAbsentees(ReportFilters f) {
        Map<String, String> t = support.terms();
        String student = t.get("student");
        ReportResult r = new ReportResult("exam-absentees", "Exam absentees",
                "For each quiz that has taken place: who was expected, who sat it, who started but never submitted, and who did not turn up.");
        Lookups l = support.lookups();
        Period p = support.period(f);
        support.periodScope(r, p);
        support.placeScope(r, f, l);
        Long sessionId = f.sessionId() != null ? f.sessionId() : support.currentSession().getId();
        LocalDate today = LocalDate.now();

        Map<Long, Set<Long>> sat = new HashMap<>();
        Map<Long, Map<Long, LocalDateTime>> started = new HashMap<>();
        Map<Long, LocalDateTime> firstActivity = new HashMap<>();
        for (AttemptRow a : queries.attempts(Period.allTime())) {
            if (a.status() == AttemptStatus.SUBMITTED) sat.computeIfAbsent(a.quizId(), k -> new HashSet<>()).add(a.studentId());
            else if (a.status() == AttemptStatus.IN_PROGRESS) started.computeIfAbsent(a.quizId(), k -> new HashMap<>()).put(a.studentId(), a.startedAt());
            if (a.startedAt() != null) firstActivity.merge(a.quizId(), a.startedAt(), (x, y) -> x.isBefore(y) ? x : y);
        }
        for (ResultRow x : queries.quizResults(Period.allTime())) {
            sat.computeIfAbsent(x.quizId(), k -> new HashSet<>()).add(x.studentId());
            if (x.submittedAt() != null) firstActivity.merge(x.quizId(), x.submittedAt(), (a, b) -> a.isBefore(b) ? a : b);
        }

        List<StudentRow> active = queries.students().stream().filter(StudentRow::enabled).toList();
        Map<Long, StudentRow> byId = active.stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        Map<Long, Set<Long>> registered = registeredIn(sessionId);

        record Sitting(Quiz quiz, LocalDate date, List<StudentRow> expected, List<StudentRow> absent, List<StudentRow> inProgress,
                       int sat, String basis) {}
        List<Sitting> sittings = new ArrayList<>();
        for (Quiz q : l.quizzes.values()) {
            if (!l.courseMatches(q.getCategory(), f)) continue;
            LocalDate date = q.getQuizDate() != null ? q.getQuizDate()
                    : firstActivity.containsKey(q.getqId()) ? firstActivity.get(q.getqId()).toLocalDate() : null;
            if (date == null || date.isAfter(today) || !p.contains(date)) continue;
            Expected e = expected(q, registered, active, byId, l, t);
            List<StudentRow> who = e.students().stream().filter(s -> f.programId() == null || f.programId().equals(s.programId())).toList();
            Set<Long> didSit = sat.getOrDefault(q.getqId(), Set.of());
            Map<Long, LocalDateTime> open = started.getOrDefault(q.getqId(), Map.of());
            List<StudentRow> inProgress = who.stream().filter(s -> !didSit.contains(s.id()) && open.containsKey(s.id())).toList();
            List<StudentRow> absent = who.stream().filter(s -> !didSit.contains(s.id()) && !open.containsKey(s.id())).toList();
            sittings.add(new Sitting(q, date, who, absent, inProgress, (int) who.stream().filter(s -> didSit.contains(s.id())).count(), e.basis()));
        }
        sittings.sort(Comparator.comparing(Sitting::date).reversed().thenComparing(s -> Objects.toString(s.quiz().getTitle(), "")));

        Sitting one = f.quizId() == null ? null
                : sittings.stream().filter(s -> f.quizId().equals(s.quiz().getqId())).findFirst().orElse(null);
        if (f.quizId() != null && one == null) {
            Quiz q = l.quiz(f.quizId());
            r.scope("Quiz: " + (q == null ? "not found" : q.getTitle()));
            r.table("absent", "Did not sit").text("studentId", t.get("studentId")).text("name", "Name")
                    .emptyText("This quiz has not taken place in this period, so nobody can be absent from it yet.");
        }
        if (one != null) {
            Quiz q = one.quiz();
            r.scope("Quiz: " + (q.getCategory() == null ? "" : Objects.toString(q.getCategory().getCourseCode(), "") + " ") + q.getTitle()
                    + " (" + day(one.date()) + ")");
            Map<Long, LocalDateTime> open = started.getOrDefault(q.getqId(), Map.of());
            Table absent = r.table("absent", "Did not sit").text("studentId", t.get("studentId")).text("name", "Name")
                    .text("programme", t.get("program")).col("level", t.get("level"), INT).text("phone", "Phone").text("email", "Email")
                    .emptyText("Everyone expected sat this quiz.");
            one.absent().stream().sorted(Comparator.comparing(s -> Objects.toString(s.username(), "")))
                    .forEach(s -> absent.add(s.username(), s.name(), l.programName(s.programId()), s.level(), s.phone(), s.email()));
            Table prog = r.table("inProgress", "Started but never submitted").text("studentId", t.get("studentId")).text("name", "Name")
                    .text("programme", t.get("program")).col("level", t.get("level"), INT).col("started", "Started", DATETIME)
                    .text("phone", "Phone").emptyText("Nobody left this quiz unfinished.");
            one.inProgress().stream().sorted(Comparator.comparing(s -> Objects.toString(s.username(), "")))
                    .forEach(s -> prog.add(s.username(), s.name(), l.programName(s.programId()), s.level(), minute(open.get(s.id())), s.phone()));
        }

        Table tb = r.table("quizzes", "Quizzes")
                .col("date", "Date", DATE).text("course", t.get("course")).text("quiz", "Quiz").text("lecturer", t.get("lecturer"))
                .col("expected", "Expected", INT).col("sat", "Sat", INT).col("inProgress", "Never submitted", INT)
                .col("absent", "Absent", INT).col("absentShare", "Absent share", PERCENT).text("basis", "Expected from")
                .drill("quizId", "quizId", "List who did not sit this quiz")
                .emptyText("No quizzes took place in this period.");
        long expected = 0, satTotal = 0, absentTotal = 0, unfinished = 0;
        for (Sitting s : sittings) {
            Quiz q = s.quiz();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day(s.date()));
            row.put("course", q.getCategory() == null ? null : q.getCategory().getCourseCode());
            row.put("quiz", q.getTitle());
            row.put("lecturer", name(Lookups.lecturerOf(q)));
            row.put("expected", s.expected().size());
            row.put("sat", s.sat());
            row.put("inProgress", s.inProgress().size());
            row.put("absent", s.absent().size());
            row.put("absentShare", pct(s.absent().size(), s.expected().size()));
            row.put("basis", s.basis());
            row.put("quizId", q.getqId());
            tb.add(row);
            expected += s.expected().size();
            satTotal += s.sat();
            absentTotal += s.absent().size();
            unfinished += s.inProgress().size();
        }

        r.stat("Quizzes", sittings.size())
         .stat("Expected", expected, "Counted once per quiz", null)
         .stat("Sat", satTotal, null, "good")
         .stat("Absent", absentTotal, null, absentTotal > 0 ? "warn" : null)
         .stat("Never submitted", unfinished, null, unfinished > 0 ? "warn" : null)
         .stat("Absence rate", AcademicReports.fmtPct(pct(absentTotal, expected)));
        r.note("Expected = " + student.toLowerCase() + "s registered for the " + t.get("course").toLowerCase() + " in the "
                + (f.sessionId() != null ? "chosen" : "current") + " session (within the quiz's index-number range); when nobody registered, active "
                + student.toLowerCase() + "s of its " + t.get("program").toLowerCase() + "s at its " + t.get("level").toLowerCase() + ".");
        r.note("Only quizzes dated today or earlier are listed. Click a quiz to see who did not sit it, with phone numbers to follow up.");
        return r;
    }

    // ── Exam load ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public ReportResult examLoad(ReportFilters f, User actor) {
        Map<String, String> t = support.terms();
        String candidates = "candidates";
        ReportResult r = new ReportResult("exam-load", "Exam schedule load",
                "Scheduled quizzes and how many " + candidates + " are expected to be writing at the same time, day by day.");
        Lookups l = support.lookups();
        LocalDate from = f.from() != null ? f.from() : LocalDate.now();
        LocalDate to = f.to() != null ? f.to() : from.plusDays(30);
        support.periodScope(r, Period.of(from, to, null));
        support.placeScope(r, f, l);

        Map<String, Object> tt = timetableService.timetable(actor, from, to, f.departmentId(), f.programId(),
                f.level() == null ? null : String.valueOf(f.level()));
        List<Map<String, Object>> items = (List<Map<String, Object>>) tt.get("items");

        List<StudentRow> active = queries.students().stream().filter(StudentRow::enabled).toList();
        Map<Long, StudentRow> byId = active.stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        Map<Long, Set<Long>> registered = registeredIn(support.currentSession().getId());

        List<Slot> slots = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Quiz q = l.quiz((Long) it.get("quizId"));
            if (q == null) continue;
            Expected e = expected(q, registered, active, byId, l, t);
            slots.add(new Slot(it, (LocalDateTime) it.get("start"), (LocalDateTime) it.get("end"), e.students().size(), e.basis()));
        }
        slots.sort(Comparator.comparing(Slot::start));

        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        Table ex = r.table("exams", "Scheduled quizzes")
                .col("date", "Date", DATE).text("starts", "Starts").text("ends", "Ends").text("course", t.get("course"))
                .text("quiz", "Quiz").text("lecturer", t.get("lecturer")).col("expected", "Expected " + candidates, INT)
                .text("basis", "Based on").col("overlapping", "Running at the same time", INT)
                .col("peak", "Most writing at once", INT).text("published", "Published")
                .emptyText("No quizzes with a date and start time in this period.");
        for (Slot s : slots) {
            List<Slot> overlap = slots.stream().filter(o -> o != s && o.start().isBefore(s.end()) && s.start().isBefore(o.end())).toList();
            int peak = peakWithin(slots, s.start(), s.end());
            ex.add(day(s.start()), hm.format(s.start()), hm.format(s.end()), s.item().get("courseCode"), s.item().get("title"),
                    s.item().get("lecturer"), s.expected(), s.basis(), overlap.size(), peak,
                    Boolean.TRUE.equals(s.item().get("published")) ? "Yes" : Boolean.TRUE.equals(s.item().get("autoOpen")) ? "Opens automatically" : "No");
        }

        Table days = r.table("days", "Daily peaks")
                .col("date", "Date", DATE).col("exams", "Quizzes", INT).col("peak", "Most writing at once", INT)
                .text("window", "When").text("atPeak", "Quizzes running then").text("load", "Load").chart("date", "peak");
        int highestPeak = 0, busyDays = 0;
        String highestDay = null;
        Map<LocalDate, List<Slot>> byDay = slots.stream().collect(Collectors.groupingBy(s -> s.start().toLocalDate(), TreeMap::new, Collectors.toList()));
        for (Map.Entry<LocalDate, List<Slot>> e : byDay.entrySet()) {
            List<Slot> ds = e.getValue();
            // Sweep the day's start and end times; candidates writing at each moment
            TreeSet<LocalDateTime> marks = new TreeSet<>();
            ds.forEach(s -> { marks.add(s.start()); marks.add(s.end()); });
            int best = -1;
            LocalDateTime bestFrom = null, bestTo = null;
            for (LocalDateTime m : marks) {
                LocalDateTime next = marks.higher(m);
                if (next == null) break;
                int n = runningAt(ds, m);
                if (n > best) { best = n; bestFrom = m; bestTo = next; }
            }
            LocalDateTime at = bestFrom;
            List<Slot> running = at == null ? List.of() : ds.stream().filter(s -> !s.start().isAfter(at) && s.end().isAfter(at)).toList();
            int peak = Math.max(best, 0);
            days.add(day(e.getKey()), ds.size(), peak, at == null ? null : hm.format(bestFrom) + "–" + hm.format(bestTo),
                    running.stream().map(s -> Objects.toString(s.item().get("courseCode"), Objects.toString(s.item().get("title"), "")))
                            .collect(Collectors.joining(", ")),
                    peak >= VERY_HIGH_LOAD ? "Very high" : peak >= HIGH_LOAD ? "High" : "Normal");
            if (peak > highestPeak) { highestPeak = peak; highestDay = day(e.getKey()); }
            if (peak >= HIGH_LOAD) busyDays++;
        }

        r.stat("Quizzes scheduled", slots.size())
         .stat("Days with quizzes", byDay.size())
         .stat("Most writing at once", highestPeak, highestDay, highestPeak >= VERY_HIGH_LOAD ? "bad" : highestPeak >= HIGH_LOAD ? "warn" : null)
         .stat("Days at " + HIGH_LOAD + "+ at once", busyDays, null, busyDays > 0 ? "warn" : null)
         .stat("Clashes", tt.get("clashCount"), "Same " + t.get("program").toLowerCase() + " and " + t.get("level").toLowerCase() + " at the same time", null);
        r.note("Expected " + candidates + " = " + t.get("student").toLowerCase() + "s registered for the " + t.get("course").toLowerCase()
                + " this session (within the quiz's index-number range); when nobody has registered yet, active "
                + t.get("student").toLowerCase() + "s of its " + t.get("program").toLowerCase() + "s at its " + t.get("level").toLowerCase() + ".");
        r.note("Load is High from " + HIGH_LOAD + " " + candidates + " writing at once (where slowdowns were seen before) and Very high from "
                + VERY_HIGH_LOAD + ". Only quizzes with a date and start time appear.");
        return r;
    }

    /** Who should sit a quiz, and on what basis. */
    record Expected(List<StudentRow> students, String basis) {}

    /** Course id → students registered for it in the session. */
    Map<Long, Set<Long>> registeredIn(Long sessionId) {
        Map<Long, Set<Long>> out = new HashMap<>();
        for (RegistrationRow x : queries.registrations())
            if (sessionId != null && sessionId.equals(x.sessionId())) out.computeIfAbsent(x.courseId(), k -> new HashSet<>()).add(x.studentId());
        return out;
    }

    /**
     * Students registered for the quiz's course in the session; when nobody has registered, active
     * students of its programmes at the course's level. Either way, only those in the quiz's index-number range.
     */
    Expected expected(Quiz q, Map<Long, Set<Long>> registered, List<StudentRow> active, Map<Long, StudentRow> byId,
                              Lookups l, Map<String, String> t) {
        Category c = q.getCategory();
        Set<Long> regs = c == null ? Set.of() : registered.getOrDefault(c.getCid(), Set.of());
        List<StudentRow> who;
        String basis;
        if (!regs.isEmpty()) {
            who = regs.stream().map(byId::get).filter(Objects::nonNull).toList();
            basis = "Registered";
        } else {
            Set<Long> programmes = q.getPrograms() != null && !q.getPrograms().isEmpty()
                    ? q.getPrograms().stream().map(pr -> pr.getId()).collect(Collectors.toSet()) : l.courseProgramIds(c);
            int level = c == null ? 0 : levelNumber(c.getLevel());
            who = active.stream().filter(s -> s.programId() != null && programmes.contains(s.programId())
                    && (level == 0 || (s.level() != null && s.level() == level))).toList();
            basis = t.get("program") + " & " + t.get("level").toLowerCase();
        }
        return new Expected(who.stream()
                .filter(s -> IndexNumberRange.contains(q.getIndexRangeStart(), q.getIndexRangeEnd(), s.username())).toList(), basis);
    }

    private record Slot(Map<String, Object> item, LocalDateTime start, LocalDateTime end, int expected, String basis) {}

    /** Candidates writing at moment m. */
    private static int runningAt(List<Slot> slots, LocalDateTime m) {
        return slots.stream().filter(s -> !s.start().isAfter(m) && s.end().isAfter(m)).mapToInt(Slot::expected).sum();
    }

    /** Most candidates writing at any moment in [from, to): the count only changes when a quiz starts. */
    private static int peakWithin(List<Slot> slots, LocalDateTime from, LocalDateTime to) {
        int best = runningAt(slots, from);
        for (Slot s : slots)
            if (s.start().isAfter(from) && s.start().isBefore(to)) best = Math.max(best, runningAt(slots, s.start()));
        return best;
    }
}
