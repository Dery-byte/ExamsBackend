package com.exam.service.reports;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.GradeBand;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.repository.UserRepository;
import com.exam.service.academic.AcademicRecordService;
import com.exam.service.academic.GradingService;
import com.exam.service.comms.NotificationService;
import com.exam.service.reports.ReportQueries.*;
import com.exam.service.reports.Standings.Graded;
import com.exam.service.reports.Standings.Standing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/** Students, courses and results: department comparison, headcount, registration, publication, standing, moderation. */
@Service
@Transactional(readOnly = true)
public class AcademicReports {

    static final List<String> SHEET_STATUSES = List.of("DRAFT", "ACTIVE", "SUBMITTED", "APPROVED", "PUBLISHED");
    /** Audit actions that change a marks sheet; the latest one dates the sheet's last change. */
    static final List<String> SHEET_ACTIONS = List.of("Created marks sheet", "Updated marks sheet", "Saved marks",
            "Submitted marks sheet", "Approved marks sheet", "Published marks sheet", "Returned marks sheet for corrections",
            "Scheduled results release", "Cancelled scheduled results release", "Enrolled students into marks sheet",
            "Synced system marks", "Added marks sheet section", "Deleted marks sheet section");
    /** Sheet statuses whose results are checked for moderation: still awaiting approval, or already official. */
    static final Set<String> MODERATED = Set.of("SUBMITTED", "APPROVED", "PUBLISHED");

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private Standings standings;
    @Autowired private AcademicRecordService records;
    @Autowired private GradingService gradingService;
    @Autowired private UserRepository userRepository;

    // ── Department comparison ────────────────────────────────────────────────

    public ReportResult departmentComparison(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("department-comparison", "Department comparison",
                "Every department side by side: people, quizzes, official results and marks sheets.");
        Lookups l = support.lookups();
        Period p = support.period(f);
        support.sessionScope(r, f);
        support.periodScope(r, p);

        List<StudentRow> students = queries.students().stream().filter(StudentRow::enabled).toList();
        List<User> lecturers = userRepository.findByRole(Role.LECTURER).stream().filter(User::isEnabled).toList();
        List<ResultRow> results = queries.quizResults(p).stream().filter(x -> x.percentage() != null).toList();
        List<MarkRow> marks = queries.gradedMarks(AcademicRecordService.STAFF_VISIBLE).stream()
                .filter(m -> f.sessionId() == null || f.sessionId().equals(m.sessionId())).toList();
        List<SheetRow> sheets = queries.sheets().stream()
                .filter(s -> f.sessionId() == null || f.sessionId().equals(s.sessionId())).toList();
        Map<Long, Standing> standing = support.isSchool() ? Map.of() : standings.all();

        Table tb = r.table("departments", "Departments")
                .text("department", "Department").col("programmes", t.get("program") + "s", INT)
                .col("students", t.get("student") + "s", INT).col("lecturers", t.get("lecturer") + "s", INT)
                .col("perLecturer", t.get("student") + "s per " + t.get("lecturer").toLowerCase(), DECIMAL)
                .col("courses", t.get("courses"), INT).col("quizzes", "Quizzes taken", INT).col("attempts", "Quiz results", INT)
                .col("quizAverage", "Average quiz score", PERCENT).col("quizPass", "Quiz pass rate", PERCENT)
                .col("results", "Official results", INT).col("resultPass", "Official pass rate", PERCENT);
        if (!support.isSchool()) tb.col("cgpa", "Average CGPA", DECIMAL);
        tb.col("sheets", "Marks sheets", INT).col("published", "Sheets published", PERCENT);

        long allPass = 0, allResults = 0, allMarkPass = 0, allMarks = 0;
        String best = null; Double bestRate = null;
        for (Department d : l.sortedDepartments()) {
            Long id = d.getId();
            List<StudentRow> ds = students.stream().filter(s -> id.equals(l.studentDept(s))).toList();
            long lect = lecturers.stream().filter(u -> NotificationService.inDepartment(u, d)).count();
            long programmes = l.programs.values().stream().filter(pr -> pr.getDepartment() != null && id.equals(pr.getDepartment().getId())).count();
            long courses = l.courses.values().stream().filter(c -> l.courseDepts(c).contains(id)).count();
            List<ResultRow> dr = results.stream().filter(x -> l.courseDepts(l.courseOfQuiz(x.quizId())).contains(id)).toList();
            long passes = dr.stream().filter(x -> x.percentage() >= PASS_MARK).count();
            List<MarkRow> dm = marks.stream().filter(m -> id.equals(l.deptOfProgram(m.programId()))).toList();
            long markPasses = dm.stream().filter(m -> records.passed(m.grade())).count();
            List<SheetRow> dsh = sheets.stream().filter(s -> id.equals(l.deptOfProgram(s.programId()))).toList();
            long published = dsh.stream().filter(s -> "PUBLISHED".equals(s.status())).count();
            OptionalDouble cgpa = ds.stream().map(s -> standing.get(s.id())).filter(x -> x != null && x.cgpa() != null)
                    .mapToDouble(x -> x.cgpa().doubleValue()).average();
            Double markRate = pct(markPasses, dm.size());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("department", d.getName());
            row.put("programmes", programmes);
            row.put("students", ds.size());
            row.put("lecturers", lect);
            row.put("perLecturer", lect == 0 ? null : round1((double) ds.size() / lect));
            row.put("courses", courses);
            row.put("quizzes", dr.stream().map(ResultRow::quizId).distinct().count());
            row.put("attempts", dr.size());
            row.put("quizAverage", round1OrNull(dr.stream().mapToDouble(ResultRow::percentage).average()));
            row.put("quizPass", pct(passes, dr.size()));
            row.put("results", dm.size());
            row.put("resultPass", markRate);
            if (!support.isSchool()) row.put("cgpa", cgpa.isPresent() ? BigDecimal.valueOf(cgpa.getAsDouble()).setScale(2, RoundingMode.HALF_UP) : null);
            row.put("sheets", dsh.size());
            row.put("published", pct(published, dsh.size()));
            tb.add(row);

            allPass += passes; allResults += dr.size(); allMarkPass += markPasses; allMarks += dm.size();
            if (markRate != null && (bestRate == null || markRate > bestRate)) { bestRate = markRate; best = d.getName(); }
        }
        tb.chart("department", allMarks > 0 ? "resultPass" : "quizPass");

        r.stat("Departments", l.departments.size())
         .stat("Active " + t.get("student").toLowerCase() + "s", students.size())
         .stat("Active " + t.get("lecturer").toLowerCase() + "s", lecturers.size())
         .stat("Quiz pass rate", fmtPct(pct(allPass, allResults)), allResults + " quiz results", null)
         .stat("Official pass rate", fmtPct(pct(allMarkPass, allMarks)), allMarks + " official results", null);
        if (best != null) r.stat("Highest official pass rate", best, fmtPct(bestRate), "good");

        r.note("Quiz figures cover quizzes on each department's " + t.get("courses").toLowerCase()
                + " taken in the period. A " + t.get("course").toLowerCase() + " offered by several departments counts in each.");
        r.note("A quiz score of " + (int) PASS_MARK + "% or more is a pass. Official results are approved or published marks-sheet results"
                + (f.sessionId() == null ? "." : " for the session."));
        if (!support.isSchool()) r.note("Average CGPA uses every approved and published result to date.");
        return r;
    }

    // ── Student headcount ────────────────────────────────────────────────────

    public ReportResult studentHeadcount(ReportFilters f) {
        Map<String, String> t = support.terms();
        String students = t.get("student") + "s";
        ReportResult r = new ReportResult("student-headcount", students + " by programme and level",
                "How many " + students.toLowerCase() + " each programme has at each level and " + t.get("semester").toLowerCase()
                        + ", and records that need fixing.");
        Lookups l = support.lookups();
        support.placeScope(r, f, l);
        r.scope("As at: " + day(LocalDate.now()));

        List<StudentRow> all = queries.students().stream().filter(s -> l.studentMatches(s, f)).toList();
        int periods = Math.max(support.isSchool() ? 3 : 2, all.stream().map(StudentRow::semester).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).max().orElse(0));
        periods = Math.min(periods, 4);

        Table byLevel = r.table("levels", t.get("program") + " and " + t.get("level").toLowerCase())
                .text("department", "Department").text("programme", t.get("program")).col("level", t.get("level"), INT);
        for (int i = 1; i <= periods; i++) byLevel.col("s" + i, support.semesterName(i), INT);
        byLevel.col("noSemester", t.get("semester") + " not set", INT).col("active", "Active", INT)
                .col("deactivated", "Deactivated", INT).col("total", "Total", INT);

        Map<List<Object>, List<StudentRow>> groups = all.stream().filter(s -> s.programId() != null)
                .collect(Collectors.groupingBy(s -> Arrays.asList(s.programId(), s.level())));
        List<List<Object>> keys = new ArrayList<>(groups.keySet());
        keys.sort(Comparator.comparing((List<Object> k) -> Objects.toString(l.deptName(l.deptOfProgram((Long) k.get(0))), ""))
                .thenComparing(k -> Objects.toString(l.programName((Long) k.get(0)), ""))
                .thenComparing(k -> k.get(1) == null ? Integer.MAX_VALUE : (Integer) k.get(1)));
        for (List<Object> k : keys) {
            List<StudentRow> g = groups.get(k);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("department", l.deptName(l.deptOfProgram((Long) k.get(0))));
            row.put("programme", l.programName((Long) k.get(0)));
            row.put("level", k.get(1));
            List<StudentRow> active = g.stream().filter(StudentRow::enabled).toList();
            for (int i = 1; i <= periods; i++) {
                int sem = i;
                row.put("s" + i, active.stream().filter(s -> s.semester() != null && s.semester() == sem).count());
            }
            row.put("noSemester", active.stream().filter(s -> s.semester() == null || s.semester() < 1).count());
            row.put("active", active.size());
            row.put("deactivated", g.size() - active.size());
            row.put("total", g.size());
            byLevel.add(row);
        }

        Table byDept = r.table("departments", "By department")
                .text("department", "Department").col("programmes", t.get("program") + "s with " + students.toLowerCase(), INT)
                .col("active", "Active", INT).col("deactivated", "Deactivated", INT).col("total", "Total", INT)
                .col("share", "Share of active", PERCENT).chart("department", "active");
        long activeTotal = all.stream().filter(StudentRow::enabled).count();
        Map<Long, List<StudentRow>> perDept = all.stream().filter(s -> l.studentDept(s) != null)
                .collect(Collectors.groupingBy(l::studentDept));
        for (Department d : l.sortedDepartments()) {
            List<StudentRow> g = perDept.getOrDefault(d.getId(), List.of());
            if (g.isEmpty() && f.departmentId() != null) continue;
            long active = g.stream().filter(StudentRow::enabled).count();
            byDept.add(d.getName(), g.stream().map(StudentRow::programId).filter(Objects::nonNull).distinct().count(),
                    active, g.size() - active, g.size(), pct(active, activeTotal));
        }
        long noDept = all.stream().filter(s -> l.studentDept(s) == null).count();
        if (noDept > 0) {
            long active = all.stream().filter(s -> l.studentDept(s) == null && s.enabled()).count();
            byDept.add("No department", 0, active, noDept - active, noDept, pct(active, activeTotal));
        }

        Table fix = r.table("attention", "Records needing attention")
                .subtitle("Active " + students.toLowerCase() + " without a " + t.get("program").toLowerCase() + ", "
                        + t.get("level").toLowerCase() + " or " + t.get("semester").toLowerCase()
                        + ". They are left out of fees, registration checks and promotion.")
                .text("studentId", t.get("studentId")).text("name", "Name").text("email", "Email")
                .text("department", "Department").text("programme", t.get("program")).text("issue", "Missing")
                .emptyText("Every active record has a " + t.get("program").toLowerCase() + ", " + t.get("level").toLowerCase()
                        + " and " + t.get("semester").toLowerCase() + ".");
        all.stream().filter(StudentRow::enabled).sorted(Comparator.comparing(s -> Objects.toString(s.username(), ""))).forEach(s -> {
            List<String> missing = new ArrayList<>();
            if (s.programId() == null) missing.add(t.get("program"));
            if (s.level() == null) missing.add(t.get("level"));
            if (s.semester() == null || s.semester() < 1) missing.add(t.get("semester"));
            if (!missing.isEmpty())
                fix.add(s.username(), s.name(), s.email(), l.deptName(l.studentDept(s)), l.programName(s.programId()), String.join(", ", missing));
        });

        r.stat("Total " + students.toLowerCase(), all.size())
         .stat("Active", activeTotal, null, "good")
         .stat("Deactivated", all.size() - activeTotal)
         .stat(t.get("program") + "s with " + students.toLowerCase(), all.stream().map(StudentRow::programId).filter(Objects::nonNull).distinct().count())
         .stat("Records needing attention", fix.getRows().size(), null, fix.getRows().isEmpty() ? null : "warn");
        r.note("Counts are each " + t.get("student").toLowerCase() + "'s current " + t.get("program").toLowerCase() + ", "
                + t.get("level").toLowerCase() + " and " + t.get("semester").toLowerCase() + " at the time the report is run.");
        return r;
    }

    // ── Course registration ──────────────────────────────────────────────────

    public ReportResult courseRegistration(ReportFilters f) {
        Map<String, String> t = support.terms();
        String course = t.get("course"), courses = t.get("courses");
        ReportResult r = new ReportResult("course-registration", course + " registration",
                "How many " + t.get("student").toLowerCase() + "s registered for each " + course.toLowerCase()
                        + ", who is still to register, and the list of " + t.get("student").toLowerCase() + "s on any " + course.toLowerCase() + ".");
        Lookups l = support.lookups();
        support.sessionScope(r, f);
        support.placeScope(r, f, l);

        Map<Long, StudentRow> students = queries.students().stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        List<RegistrationRow> regs = queries.registrations().stream()
                .filter(x -> f.sessionId() == null || f.sessionId().equals(x.sessionId())).toList();
        Map<Long, List<RegistrationRow>> byCourse = regs.stream().collect(Collectors.groupingBy(RegistrationRow::courseId));
        List<StudentRow> active = students.values().stream().filter(StudentRow::enabled).toList();

        // Drill-down: one course's register first, as it is what was asked for
        if (f.courseId() != null) {
            Category c = l.course(f.courseId());
            if (c == null) throw new IllegalArgumentException(course + " not found.");
            r.scope(course + ": " + Objects.toString(c.getCourseCode(), "") + " " + Objects.toString(c.getTitle(), ""));
            Map<Long, RegistrationRow> latest = new LinkedHashMap<>();
            byCourse.getOrDefault(c.getCid(), List.of()).stream()
                    .sorted(Comparator.comparing(x -> x.registeredOn() == null ? new Date(0) : x.registeredOn()))
                    .forEach(x -> latest.put(x.studentId(), x));
            Table list = r.table("registered", "Registered for " + Objects.toString(c.getCourseCode(), c.getTitle()))
                    .subtitle(c.getTitle())
                    .text("studentId", t.get("studentId")).text("name", "Name").text("email", "Email").text("phone", "Phone")
                    .text("programme", t.get("program")).col("level", t.get("level"), INT).col("semester", t.get("semester"), INT)
                    .col("registeredOn", "Registered on", DATE).text("session", "Session").text("account", "Account");
            latest.values().stream().map(x -> students.get(x.studentId())).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(s -> Objects.toString(s.username(), ""))).forEach(s -> {
                        RegistrationRow x = latest.get(s.id());
                        list.add(s.username(), s.name(), s.email(), s.phone(), l.programName(s.programId()), s.level(), s.semester(),
                                x.registeredOn() == null ? null : day(new java.sql.Date(x.registeredOn().getTime()).toLocalDate()),
                                x.sessionName(), s.enabled() ? "Active" : "Deactivated");
                    });
            Table missing = r.table("notRegistered", "Expected but not registered")
                    .subtitle("Active " + t.get("student").toLowerCase() + "s of this " + course.toLowerCase() + "'s "
                            + t.get("program").toLowerCase() + "s at its " + t.get("level").toLowerCase() + " who have not registered.")
                    .text("studentId", t.get("studentId")).text("name", "Name").text("email", "Email").text("phone", "Phone")
                    .text("programme", t.get("program")).col("level", t.get("level"), INT)
                    .emptyText(c.isOpenToEveryone()
                            ? "This " + course.toLowerCase() + " is open to everyone, so nobody is expected in particular."
                            : "Everyone expected has registered.");
            (c.isOpenToEveryone() ? List.<StudentRow>of() : expected(c, active, l)).stream()
                    .filter(s -> !latest.containsKey(s.id()))
                    .sorted(Comparator.comparing(s -> Objects.toString(s.username(), "")))
                    .forEach(s -> missing.add(s.username(), s.name(), s.email(), s.phone(), l.programName(s.programId()), s.level()));
        }

        Table tb = r.table("courses", "Registrations by " + course.toLowerCase())
                .text("code", course + " code").text("title", course + " title").text("department", "Department")
                .text("programmes", t.get("program") + "s").col("level", t.get("level"), INT).col("semester", t.get("semester"), INT)
                .col("credits", "Credit units", INT).text("lecturer", t.get("lecturer"))
                .col("registered", "Registered", INT).col("expected", "Expected", INT).col("missing", "Not registered", INT)
                .drill("courseId", "courseId", "Show who registered for this " + course.toLowerCase());
        List<Category> inScope = l.courses.values().stream().filter(c -> l.courseMatches(c, f))
                .sorted(Comparator.comparingInt((Category c) -> levelNumber(c.getLevel()))
                        .thenComparing(c -> c.getSemester() == null ? 0 : c.getSemester())
                        .thenComparing(c -> Objects.toString(c.getCourseCode(), ""))).toList();
        long totalRegs = 0, noRegs = 0;
        Set<Long> distinct = new HashSet<>();
        Map<List<Object>, long[]> perLevel = new TreeMap<>(Comparator.comparing((List<Object> k) -> (Integer) k.get(0))
                .thenComparing(k -> (Integer) k.get(1)));
        Map<List<Object>, Set<Long>> perLevelStudents = new HashMap<>();
        for (Category c : inScope) {
            Set<Long> who = byCourse.getOrDefault(c.getCid(), List.of()).stream().map(RegistrationRow::studentId).collect(Collectors.toSet());
            boolean open = c.isOpenToEveryone();
            List<StudentRow> exp = open ? List.of() : expected(c, active, l);
            long missing = exp.stream().filter(s -> !who.contains(s.id())).count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c.getCourseCode());
            row.put("title", c.getTitle());
            row.put("department", l.courseDeptNames(c));
            row.put("programmes", open ? "Open to everyone" : l.courseProgramNames(c));
            row.put("level", levelNumber(c.getLevel()) == 0 ? null : levelNumber(c.getLevel()));
            row.put("semester", c.getSemester());
            row.put("credits", c.getCreditUnits());
            row.put("lecturer", name(c.getUser()));
            row.put("registered", who.size());
            row.put("expected", open ? null : exp.size());
            row.put("missing", open ? null : missing);
            row.put("courseId", c.getCid());
            tb.add(row);
            totalRegs += who.size();
            if (who.isEmpty()) noRegs++;
            distinct.addAll(who);
            List<Object> key = Arrays.asList(levelNumber(c.getLevel()), c.getSemester() == null ? 0 : c.getSemester());
            long[] agg = perLevel.computeIfAbsent(key, k -> new long[2]);
            agg[0]++;
            agg[1] += who.size();
            perLevelStudents.computeIfAbsent(key, k -> new HashSet<>()).addAll(who);
        }

        Table lv = r.table("levels", "By " + t.get("level").toLowerCase() + " and " + t.get("semester").toLowerCase())
                .col("level", t.get("level"), INT).text("semester", t.get("semester")).col("courses", courses, INT)
                .col("registrations", "Registrations", INT).col("students", t.get("student") + "s registered", INT)
                .col("perStudent", courses + " per " + t.get("student").toLowerCase(), DECIMAL);
        perLevel.forEach((k, agg) -> {
            int n = perLevelStudents.get(k).size();
            lv.add((Integer) k.get(0) == 0 ? null : k.get(0), (Integer) k.get(1) == 0 ? "Not set" : support.semesterName(k.get(1)),
                    agg[0], agg[1], n, n == 0 ? null : round1((double) agg[1] / n));
        });

        r.stat(courses, inScope.size())
         .stat("Registrations", totalRegs)
         .stat(t.get("student") + "s registered", distinct.size())
         .stat(courses + " with no registrations", noRegs, null, noRegs > 0 ? "warn" : null);
        r.note("Expected = active " + t.get("student").toLowerCase() + "s whose " + t.get("program").toLowerCase() + " offers the "
                + course.toLowerCase() + " and whose current " + t.get("level").toLowerCase() + " is the " + course.toLowerCase() + "'s "
                + t.get("level").toLowerCase() + ". " + courses + " open to everyone have no expected number.");
        r.note("Click a " + course.toLowerCase() + " to list the " + t.get("student").toLowerCase() + "s registered for it.");
        return r;
    }

    private List<StudentRow> expected(Category c, List<StudentRow> active, Lookups l) {
        Set<Long> programmes = l.courseProgramIds(c);
        int level = levelNumber(c.getLevel());
        return active.stream().filter(s -> s.programId() != null && programmes.contains(s.programId())
                && (level == 0 || (s.level() != null && s.level() == level))).toList();
    }

    // ── Results publication ──────────────────────────────────────────────────

    public ReportResult resultsPublication(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("results-publication", "Results publication tracker",
                "Where every marks sheet is, from draft to published, and which are waiting for someone.");
        Lookups l = support.lookups();
        support.sessionScope(r, f);
        support.placeScope(r, f, l);
        if (f.hasStatus()) r.scope("Status: " + statusName(f.status()));

        Map<Long, Set<Long>> courses = queries.sheetCourses();
        Set<Long> taught = f.lecturerId() == null ? null : l.taughtCourseIds(f);
        List<SheetRow> sheets = queries.sheets().stream()
                .filter(s -> f.sessionId() == null || f.sessionId().equals(s.sessionId()))
                .filter(s -> l.sheetMatches(s.programId(), s.level(), s.semester(), f))
                .filter(s -> !f.hasStatus() || f.status().equalsIgnoreCase(s.status()))
                // A lecturer sees the sheets holding one of their courses
                .filter(s -> taught == null || !Collections.disjoint(courses.getOrDefault(s.id(), Set.of()), taught))
                .sorted(Comparator.comparing((SheetRow s) -> Objects.toString(s.sessionName(), ""), Comparator.reverseOrder())
                        .thenComparing(s -> Objects.toString(l.programName(s.programId()), ""))
                        .thenComparingInt(s -> levelNumber(s.level()))
                        .thenComparing(s -> s.semester() == null ? 0 : s.semester()))
                .toList();
        Map<Long, SheetCounts> counts = queries.sheetCounts();
        Map<String, LocalDateTime> lastChange = queries.lastActionOnEntity(SHEET_ACTIONS);
        LocalDateTime now = LocalDateTime.now();

        Table tb = r.table("sheets", "Marks sheets")
                .text("session", "Session").text("department", "Department").text("programme", t.get("program"))
                .col("level", t.get("level"), INT).text("semester", t.get("semester")).text("status", "Status")
                .col("courses", t.get("courses"), INT).col("students", t.get("student") + "s", INT)
                .col("entered", "Marks entered", PERCENT).col("release", "Release scheduled", DATETIME)
                .text("teacher", support.isSchool() ? "Class teacher" : "Coordinator")
                .col("lastChange", "Last change", DATETIME).col("days", "Days since change", INT);
        Table waiting = r.table("waiting", "Waiting for action")
                .subtitle("Submitted sheets wait for approval; approved sheets wait to be published.")
                .text("session", "Session").text("programme", t.get("program")).col("level", t.get("level"), INT)
                .text("semester", t.get("semester")).text("waitingFor", "Waiting for").col("days", "Days since change", INT)
                .emptyText("No sheet is waiting for approval or publishing.");
        int stale = 0;
        List<Map<String, Object>> waitRows = new ArrayList<>();
        for (SheetRow s : sheets) {
            SheetCounts c = counts.getOrDefault(s.id(), new SheetCounts(0, 0, 0));
            LocalDateTime changed = lastChange.get(String.valueOf(s.id()));
            Long days = changed == null ? null : ChronoUnit.DAYS.between(changed.toLocalDate(), now.toLocalDate());
            tb.add(s.sessionName(), l.deptName(l.deptOfProgram(s.programId())), l.programName(s.programId()),
                    levelNumber(s.level()) == 0 ? null : levelNumber(s.level()), support.semesterName(s.semester()),
                    statusName(s.status()), courses.getOrDefault(s.id(), Set.of()).size(), c.students(),
                    pct(c.entered(), c.rows()), s.publishAt() == null ? null
                            : minute(LocalDateTime.ofInstant(s.publishAt(), java.time.ZoneId.systemDefault())),
                    s.teacher(), minute(changed), days);
            String waitFor = "SUBMITTED".equals(s.status()) ? "Approval" : "APPROVED".equals(s.status())
                    ? (s.publishAt() != null ? "Scheduled release" : "Publishing") : null;
            if (waitFor != null) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("session", s.sessionName());
                w.put("programme", l.programName(s.programId()));
                w.put("level", levelNumber(s.level()) == 0 ? null : levelNumber(s.level()));
                w.put("semester", support.semesterName(s.semester()));
                w.put("waitingFor", waitFor);
                w.put("days", days);
                waitRows.add(w);
                if (days != null && days > 7) stale++;
            }
        }
        waitRows.sort(Comparator.comparing((Map<String, Object> w) -> w.get("days") == null ? -1L : (Long) w.get("days")).reversed());
        waitRows.forEach(waiting::add);

        Table st = r.table("statuses", "Sheets by status").text("status", "Status").col("sheets", "Sheets", INT).chart("status", "sheets");
        Map<String, Long> byStatus = sheets.stream().collect(Collectors.groupingBy(s -> Objects.toString(s.status(), "DRAFT"), Collectors.counting()));
        for (String s : SHEET_STATUSES) st.add(statusName(s), byStatus.getOrDefault(s, 0L));

        r.stat("Marks sheets", sheets.size());
        for (String s : SHEET_STATUSES) r.stat(statusName(s), byStatus.getOrDefault(s, 0L), null, "PUBLISHED".equals(s) ? "good" : null);
        r.stat("Waiting over 7 days", stale, null, stale > 0 ? "warn" : null);
        r.note("Marks entered = results on the sheet with a total above zero. Last change is the latest recorded action on the sheet "
                + "(marks saved, submitted, approved, published, returned …); sheets changed before the audit log began show none.");
        return r;
    }

    static String statusName(String s) {
        if (s == null) return null;
        return switch (s.toUpperCase()) {
            case "DRAFT" -> "Draft";
            case "ACTIVE" -> "Open for marks";
            case "SUBMITTED" -> "Submitted";
            case "APPROVED" -> "Approved";
            case "PUBLISHED" -> "Published";
            default -> s;
        };
    }

    // ── Academic standing ────────────────────────────────────────────────────

    public ReportResult academicStanding(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("academic-standing", "Academic standing",
                "CGPA, credits, carry-overs and class for every active " + t.get("student").toLowerCase()
                        + ", with those who fall short of the promotion rules.");
        Lookups l = support.lookups();
        support.placeScope(r, f, l);
        if (f.hasStatus()) r.scope("Standing: " + standingName(f.status()));

        BigDecimal minCgpa = gradingService.minCgpaForPromotion();
        int maxCarry = gradingService.maxCarryoversForPromotion();
        Map<Long, Standing> all = standings.all();
        List<StudentRow> students = queries.students().stream().filter(StudentRow::enabled).filter(s -> l.studentMatches(s, f))
                .sorted(Comparator.comparing((StudentRow s) -> Objects.toString(l.programName(s.programId()), ""))
                        .thenComparing(s -> s.level() == null ? 0 : s.level())
                        .thenComparing(s -> Objects.toString(s.username(), "")))
                .toList();

        Table tb = r.table("students", t.get("student") + "s")
                .text("studentId", t.get("studentId")).text("name", "Name").text("programme", t.get("program"))
                .col("level", t.get("level"), INT).col("attempted", "Credits attempted", INT).col("earned", "Credits earned", INT)
                .col("cgpa", "CGPA", DECIMAL).text("class", "Class").col("carryovers", "Carry-overs", INT)
                .text("outstanding", "Outstanding " + t.get("courses").toLowerCase()).text("standing", "Standing").text("reason", "Why");

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String k : List.of("GOOD", "CARRYOVER", "AT_RISK", "NO_RESULTS")) counts.put(k, 0L);
        Map<String, Long> classCounts = new LinkedHashMap<>();
        Map<Long, List<Object[]>> perProgLevel = new LinkedHashMap<>();
        List<Double> cgpas = new ArrayList<>();
        int shown = 0;
        for (StudentRow s : students) {
            Standing x = all.get(s.id());
            List<String> reasons = new ArrayList<>();
            String key;
            if (x == null || x.results() == 0) key = "NO_RESULTS";
            else {
                if (maxCarry >= 0 && x.outstanding().size() > maxCarry)
                    reasons.add(x.outstanding().size() + " carry-overs (limit " + maxCarry + ")");
                if (minCgpa.signum() > 0 && x.cgpa() != null && x.cgpa().compareTo(minCgpa) < 0)
                    reasons.add("CGPA below " + minCgpa.stripTrailingZeros().toPlainString());
                key = !reasons.isEmpty() ? "AT_RISK" : !x.outstanding().isEmpty() ? "CARRYOVER" : "GOOD";
            }
            counts.merge(key, 1L, Long::sum);
            String cls = x == null ? null : gradingService.classFor(x.cgpa());
            if (x != null && x.cgpa() != null) {
                cgpas.add(x.cgpa().doubleValue());
                classCounts.merge(cls == null ? "" : cls, 1L, Long::sum);
            }
            perProgLevel.computeIfAbsent(s.programId() == null ? -1L : s.programId(), k -> new ArrayList<>())
                    .add(new Object[]{s, x, key});
            if (f.hasStatus() && !f.status().equalsIgnoreCase(key)) continue;
            shown++;
            tb.add(s.username(), s.name(), l.programName(s.programId()), s.level(),
                    x == null ? 0 : x.creditsAttempted(), x == null ? 0 : x.creditsEarned(), x == null ? null : x.cgpa(), cls,
                    x == null ? 0 : x.outstanding().size(),
                    x == null ? null : x.outstanding().stream().map(g -> courseCode(l, g)).collect(Collectors.joining(", ")),
                    standingName(key), String.join("; ", reasons));
        }

        Table classes = r.table("classes", "Class distribution")
                .subtitle(t.get("student") + "s with results, by the class their CGPA falls in today.")
                .text("class", "Class").col("from", "From CGPA", DECIMAL).col("students", t.get("student") + "s", INT)
                .col("share", "Share", PERCENT).chart("class", "students");
        long withCgpa = cgpas.size();
        for (var c : gradingService.classes())
            classes.add(c.getName(), c.getMinCgpa(), classCounts.getOrDefault(c.getName(), 0L), pct(classCounts.getOrDefault(c.getName(), 0L), withCgpa));
        if (classCounts.containsKey("")) classes.add("Below every class", null, classCounts.get(""), pct(classCounts.get(""), withCgpa));

        Table prog = r.table("programmes", "By " + t.get("program").toLowerCase() + " and " + t.get("level").toLowerCase())
                .text("programme", t.get("program")).col("level", t.get("level"), INT).col("students", t.get("student") + "s", INT)
                .col("withResults", "With results", INT).col("cgpa", "Average CGPA", DECIMAL).col("good", "Good standing", INT)
                .col("carry", "With carry-overs", INT).col("risk", "Short of promotion rules", INT);
        perProgLevel.forEach((pid, list) -> {
            Map<Integer, List<Object[]>> byLevel = list.stream().collect(Collectors.groupingBy(
                    o -> ((StudentRow) o[0]).level() == null ? 0 : ((StudentRow) o[0]).level(), TreeMap::new, Collectors.toList()));
            byLevel.forEach((lvl, g) -> {
                OptionalDouble avg = g.stream().map(o -> (Standing) o[1]).filter(x -> x != null && x.cgpa() != null)
                        .mapToDouble(x -> x.cgpa().doubleValue()).average();
                prog.add(pid == -1L ? "No " + t.get("program").toLowerCase() : l.programName(pid), lvl == 0 ? null : lvl, g.size(),
                        g.stream().filter(o -> !"NO_RESULTS".equals(o[2])).count(),
                        avg.isPresent() ? BigDecimal.valueOf(avg.getAsDouble()).setScale(2, RoundingMode.HALF_UP) : null,
                        g.stream().filter(o -> "GOOD".equals(o[2])).count(),
                        g.stream().filter(o -> "CARRYOVER".equals(o[2])).count(),
                        g.stream().filter(o -> "AT_RISK".equals(o[2])).count());
            });
        });

        r.stat(t.get("student") + "s", students.size(), f.hasStatus() ? shown + " shown" : null, null)
         .stat("With results", students.size() - counts.get("NO_RESULTS"))
         .stat("Average CGPA", cgpas.isEmpty() ? null : BigDecimal.valueOf(cgpas.stream().mapToDouble(d -> d).average().orElse(0)).setScale(2, RoundingMode.HALF_UP))
         .stat("Good standing", counts.get("GOOD"), null, "good")
         .stat("With carry-overs", counts.get("CARRYOVER"), null, counts.get("CARRYOVER") > 0 ? "warn" : null)
         .stat("Short of promotion rules", counts.get("AT_RISK"), null, counts.get("AT_RISK") > 0 ? "bad" : null);
        r.note("Uses approved and published results, as staff see them on transcripts. A carry-over is a "
                + t.get("course").toLowerCase() + " whose latest attempt was a fail.");
        r.note("Promotion rules (Sessions & Grading): " + (maxCarry >= 0 ? "at most " + maxCarry + " carry-overs" : "no carry-over limit")
                + ", " + (minCgpa.signum() > 0 ? "CGPA at least " + minCgpa.stripTrailingZeros().toPlainString() : "no minimum CGPA") + ".");
        return r;
    }

    static String standingName(String key) {
        return switch (key == null ? "" : key.toUpperCase()) {
            case "GOOD" -> "Good standing";
            case "CARRYOVER" -> "Has carry-overs";
            case "AT_RISK" -> "Short of promotion rules";
            case "NO_RESULTS" -> "No results yet";
            default -> key;
        };
    }

    private static String courseCode(Lookups l, Graded g) {
        Category c = l.course(g.mark().courseId());
        return c == null ? "?" : Objects.toString(c.getCourseCode(), c.getTitle());
    }

    // ── Grade moderation ─────────────────────────────────────────────────────

    public ReportResult gradeModeration(ReportFilters f) {
        Map<String, String> t = support.terms();
        String course = t.get("course");
        ReportResult r = new ReportResult("grade-moderation", "Grade moderation",
                "Score spread and grade distribution for each " + course.toLowerCase()
                        + ", with results that look unusual flagged for the exam board.");
        Lookups l = support.lookups();
        support.sessionScope(r, f);
        support.placeScope(r, f, l);

        int defaultCredits = gradingService.defaultCreditUnits();
        List<Graded> marks = queries.gradedMarks(MODERATED).stream()
                .filter(m -> f.sessionId() == null || f.sessionId().equals(m.sessionId()))
                .filter(m -> l.sheetMatches(m.programId(), m.level(), m.semester(), f))
                .filter(m -> l.taughtBy(l.course(m.courseId()), f))
                .map(m -> standings.graded(m, defaultCredits)).toList();
        Map<Long, String> sessionNames = queries.sheets().stream().filter(s -> s.sessionId() != null)
                .collect(Collectors.toMap(SheetRow::sessionId, SheetRow::sessionName, (a, b) -> a));
        List<String> letters = gradingService.bands().stream().map(GradeBand::getLetter).toList();
        String top = letters.isEmpty() ? null : letters.get(0);
        double scopeMean = marks.stream().filter(g -> g.mark().score() != null)
                .mapToDouble(g -> g.mark().score().doubleValue()).average().orElse(0);

        Table tb = r.table("courses", course + "s")
                .text("session", "Session").text("code", course + " code").text("title", course + " title")
                .text("lecturer", t.get("lecturer")).text("status", "Sheet status").col("students", t.get("student") + "s", INT)
                .col("mean", "Mean", DECIMAL).col("sd", "Std dev", DECIMAL).col("max", "Highest", DECIMAL).col("min", "Lowest", DECIMAL)
                .col("pass", "Pass rate", PERCENT);
        for (String g : letters) tb.col("g_" + g, g, INT);
        tb.text("flags", "Flags");
        Table flagged = r.table("flagged", "Flagged for review")
                .text("session", "Session").text("code", course + " code").text("title", course + " title").text("lecturer", t.get("lecturer"))
                .col("students", t.get("student") + "s", INT).col("mean", "Mean", DECIMAL).col("pass", "Pass rate", PERCENT).text("flags", "Flags")
                .emptyText("Nothing looks unusual.");

        Map<List<Long>, List<Graded>> groups = marks.stream().collect(Collectors.groupingBy(
                g -> Arrays.asList(g.mark().sessionId(), g.mark().courseId()), LinkedHashMap::new, Collectors.toList()));
        List<List<Long>> keys = new ArrayList<>(groups.keySet());
        keys.sort(Comparator.comparing((List<Long> k) -> Objects.toString(sessionNames.get(k.get(0)), ""), Comparator.reverseOrder())
                .thenComparing(k -> Objects.toString(l.course(k.get(1)) == null ? null : l.course(k.get(1)).getCourseCode(), "")));
        int flaggedCount = 0;
        long passed = 0;
        for (List<Long> k : keys) {
            List<Graded> g = groups.get(k);
            Category c = l.course(k.get(1));
            List<Double> scores = g.stream().filter(x -> x.mark().score() != null).map(x -> x.mark().score().doubleValue()).toList();
            OptionalDouble mean = scores.stream().mapToDouble(d -> d).average();
            long pass = g.stream().filter(Graded::passed).count();
            passed += pass;
            Double passRate = pct(pass, g.size());
            Map<String, Long> byLetter = g.stream().collect(Collectors.groupingBy(x -> Objects.toString(x.mark().grade(), "").toUpperCase(), Collectors.counting()));

            List<String> flags = new ArrayList<>();
            if (g.size() >= 5) {
                if (passRate != null && passRate >= 100) flags.add("Everyone passed");
                if (passRate != null && passRate < 40) flags.add("Low pass rate");
                if (mean.isPresent() && mean.getAsDouble() >= scopeMean + 15) flags.add("Mean well above average");
                if (mean.isPresent() && mean.getAsDouble() <= scopeMean - 15) flags.add("Mean well below average");
                if (top != null && pct(byLetter.getOrDefault(top.toUpperCase(), 0L), g.size()) >= 50) flags.add("Half or more got " + top);
                Double sd = stdDev(scores);
                if (sd != null && sd < 5) flags.add("Scores bunched together");
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("session", sessionNames.get(k.get(0)));
            row.put("code", c == null ? null : c.getCourseCode());
            row.put("title", c == null ? null : c.getTitle());
            row.put("lecturer", c == null ? null : name(c.getUser()));
            row.put("status", g.stream().map(x -> statusName(x.mark().sheetStatus())).distinct().sorted().collect(Collectors.joining(", ")));
            row.put("students", g.size());
            row.put("mean", round1OrNull(mean));
            row.put("sd", stdDev(scores));
            row.put("max", scores.isEmpty() ? null : round1(Collections.max(scores)));
            row.put("min", scores.isEmpty() ? null : round1(Collections.min(scores)));
            row.put("pass", passRate);
            for (String letter : letters) row.put("g_" + letter, byLetter.getOrDefault(letter.toUpperCase(), 0L));
            row.put("flags", String.join("; ", flags));
            tb.add(row);
            if (!flags.isEmpty()) {
                flaggedCount++;
                flagged.add(row.get("session"), row.get("code"), row.get("title"), row.get("lecturer"), g.size(), row.get("mean"), passRate, row.get("flags"));
            }
        }

        Table grades = r.table("grades", "Grade distribution").text("grade", "Grade").col("results", "Results", INT)
                .col("share", "Share", PERCENT).chart("grade", "results");
        Map<String, Long> all = marks.stream().collect(Collectors.groupingBy(x -> Objects.toString(x.mark().grade(), "").toUpperCase(), Collectors.counting()));
        for (String letter : letters) grades.add(letter, all.getOrDefault(letter.toUpperCase(), 0L), pct(all.getOrDefault(letter.toUpperCase(), 0L), marks.size()));

        r.stat(course + "s", groups.size())
         .stat("Results", marks.size())
         .stat("Average score", marks.isEmpty() ? null : round1(scopeMean))
         .stat("Pass rate", fmtPct(pct(passed, marks.size())))
         .stat("Flagged " + course.toLowerCase() + "s", flaggedCount, null, flaggedCount > 0 ? "warn" : null);
        r.note("Includes submitted sheets (not yet approved) so they can be checked before approval, plus approved and published sheets.");
        r.note("Flags need at least 5 " + t.get("student").toLowerCase() + "s: everyone passed; pass rate under 40%; mean 15 marks above or below "
                + "the average of everything shown (" + round1(scopeMean) + "); half or more on the top grade; standard deviation under 5.");
        return r;
    }

    static String fmtPct(Double v) { return v == null ? null : v + "%"; }
}
