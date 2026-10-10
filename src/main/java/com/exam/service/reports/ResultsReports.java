package com.exam.service.reports;

import com.exam.model.academic.AcademicSession;
import com.exam.model.academic.GradeBand;
import com.exam.model.exam.Category;
import com.exam.repository.AcademicSessionRepository;
import com.exam.service.academic.GradingService;
import com.exam.service.reports.ReportQueries.*;
import com.exam.service.reports.Standings.Graded;
import com.exam.service.reports.Standings.Standing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/** Results for exam boards: the broadsheet, a summary per course, and top performers. */
@Service
@Transactional(readOnly = true)
public class ResultsReports {

    /** Which marks sheets count, chosen with the "status" filter. Default: submitted, approved and published. */
    static final List<ReportCatalog.Option> SHEET_CHOICES = List.of(
            new ReportCatalog.Option("OFFICIAL", "Approved and published only"),
            new ReportCatalog.Option("PUBLISHED", "Published only"),
            new ReportCatalog.Option("ALL", "Every sheet, drafts included"));

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private Standings standings;
    @Autowired private GradingService gradingService;
    @Autowired private AcademicSessionRepository sessionRepository;

    static Set<String> sheetStatuses(ReportFilters f) {
        return switch (f.hasStatus() ? f.status().toUpperCase() : "") {
            case "OFFICIAL" -> Set.of("APPROVED", "PUBLISHED");
            case "PUBLISHED" -> Set.of("PUBLISHED");
            case "ALL" -> new HashSet<>(AcademicReports.SHEET_STATUSES);
            default -> Set.of("SUBMITTED", "APPROVED", "PUBLISHED");
        };
    }

    static String sheetStatusLabel(ReportFilters f) {
        return switch (f.hasStatus() ? f.status().toUpperCase() : "") {
            case "OFFICIAL" -> "approved and published";
            case "PUBLISHED" -> "published only";
            case "ALL" -> "every sheet, drafts included";
            default -> "submitted, approved and published";
        };
    }

    private Map<Long, AcademicSession> sessions() {
        return sessionRepository.findAll().stream().collect(Collectors.toMap(AcademicSession::getId, s -> s));
    }

    // ── Broadsheet ───────────────────────────────────────────────────────────

    public ReportResult broadsheet(ReportFilters f) {
        Map<String, String> t = support.terms();
        boolean school = support.isSchool();
        String course = t.get("course");
        ReportResult r = new ReportResult("broadsheet", "Broadsheet",
                "Every " + t.get("student").toLowerCase() + " against every " + course.toLowerCase() + " for one "
                        + t.get("program").toLowerCase() + ", " + t.get("level").toLowerCase() + " and " + t.get("semester").toLowerCase()
                        + (school ? ", with totals, averages and positions." : ", with GPA, CGPA, carry-overs and remarks."));
        Lookups l = support.lookups();
        Set<String> statuses = sheetStatuses(f);
        Map<Long, AcademicSession> sessions = sessions();

        if (f.programId() == null || f.level() == null || f.semester() == null) {
            support.sessionScope(r, f);
            support.placeScope(r, f, l);
            r.table("broadsheet", "Broadsheet").text("studentId", t.get("studentId")).text("name", "Name")
                    .emptyText("Choose a " + t.get("program").toLowerCase() + ", " + t.get("level").toLowerCase() + " and "
                            + t.get("semester").toLowerCase() + " above to build the broadsheet.");
            return r;
        }

        List<SheetRow> candidates = queries.sheets().stream()
                .filter(s -> f.programId().equals(s.programId()) && String.valueOf(f.level()).equals(normLevel(s.level()))
                        && f.semester().equals(s.semester()) && statuses.contains(s.status()))
                .toList();
        Long sid = f.sessionId();
        if (sid == null) {
            sid = candidates.stream().map(SheetRow::sessionId).filter(Objects::nonNull)
                    .max(Comparator.comparing(id -> sessions.get(id) == null || sessions.get(id).getStartDate() == null
                            ? LocalDate.MIN : sessions.get(id).getStartDate())).orElse(null);
            if (sid != null) r.note("No session was chosen, so the latest session with results is shown.");
        }
        Long sessionId = sid;
        List<SheetRow> sheets = candidates.stream().filter(s -> sessionId == null || sessionId.equals(s.sessionId())).toList();
        AcademicSession session = sessionId == null ? null : sessions.get(sessionId);
        r.scope("Session: " + (session == null ? "Not set" : session.getName()));
        support.placeScope(r, f, l);
        r.scope("Marks sheets: " + sheetStatusLabel(f));

        Set<Long> sheetIds = sheets.stream().map(SheetRow::id).collect(Collectors.toSet());
        List<MarkRow> marks = queries.marksOnSheets(sheetIds);
        Map<Long, StudentRow> info = queries.students().stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        Map<Long, Set<Long>> sheetCourses = queries.sheetCourses();
        Set<Long> courseIds = new HashSet<>();
        marks.forEach(m -> courseIds.add(m.courseId()));
        sheetIds.forEach(id -> courseIds.addAll(sheetCourses.getOrDefault(id, Set.of())));
        List<Category> courses = courseIds.stream().map(l::course).filter(Objects::nonNull)
                .sorted(Comparator.comparing(c -> Objects.toString(c.getCourseCode(), ""))).toList();

        // One row per student and course: the graded one if any, else the latest sheet's
        Map<Long, Map<Long, MarkRow>> grid = new TreeMap<>();
        for (MarkRow m : marks) {
            Map<Long, MarkRow> row = grid.computeIfAbsent(m.studentId(), k -> new HashMap<>());
            MarkRow had = row.get(m.courseId());
            if (had == null || (!Standings.isGraded(had) && Standings.isGraded(m))
                    || (Standings.isGraded(had) == Standings.isGraded(m) && m.sheetId() > had.sheetId()))
                row.put(m.courseId(), m);
        }

        int defaultCredits = gradingService.defaultCreditUnits();
        Map<Long, List<Graded>> official = standings.official();
        MarkRow here = new MarkRow(null, null, null, null, null, null, Long.MAX_VALUE, null, null, sessionId,
                session == null ? null : session.getStartDate(), String.valueOf(f.level()), f.semester());
        BigDecimal minCgpa = gradingService.minCgpaForPromotion();

        Table tb = r.table("broadsheet", "Broadsheet")
                .col("sn", "No.", INT).text("studentId", t.get("studentId")).text("name", "Name");
        for (Category c : courses) {
            String code = Objects.toString(c.getCourseCode(), c.getTitle());
            String cu = !school && c.getCreditUnits() != null ? " (" + c.getCreditUnits() + ")" : "";
            tb.col("s" + c.getCid(), code + cu, NUMBER).text("g" + c.getCid(), code + " grade");
        }
        if (school) tb.col("total", "Total", NUMBER).col("average", "Average", NUMBER);
        else tb.col("taken", "Credits taken", INT).col("passed", "Credits passed", INT)
                .col("gpa", "GPA", DECIMAL).col("cgpa", "CGPA", DECIMAL).text("carry", "Carry-overs");
        boolean position = support.showPosition();
        if (position) tb.text("position", "Position");
        tb.text("remark", "Remark")
          .emptyText("No results on " + (sheets.isEmpty() ? "any marks sheet" : "these marks sheets") + " for this "
                  + t.get("program").toLowerCase() + ", " + t.get("level").toLowerCase() + " and " + t.get("semester").toLowerCase() + ".");

        int n = 0, passedAll = 0, referred = 0, incomplete = 0;
        List<Double> measures = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, MarkRow>> e : grid.entrySet()) {
            StudentRow s = info.get(e.getKey());
            Map<Long, MarkRow> mine = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sn", ++n);
            row.put("studentId", s == null ? null : s.username());
            row.put("name", s == null ? null : s.name());
            List<Graded> sem = new ArrayList<>();
            int ungraded = 0;
            double total = 0;
            int scored = 0;
            for (Category c : courses) {
                MarkRow m = mine.get(c.getCid());
                row.put("s" + c.getCid(), m == null ? null : m.score());
                row.put("g" + c.getCid(), m == null ? null : m.grade());
                if (m == null) continue;
                if (Standings.isGraded(m)) sem.add(standings.graded(m, defaultCredits)); else ungraded++;
                if (m.score() != null) { total += m.score().doubleValue(); scored++; }
            }
            List<String> failed = sem.stream().filter(g -> !g.passed()).map(g -> code(l, g)).toList();
            String remark = !failed.isEmpty() ? "Refer: " + String.join(", ", failed) : ungraded > 0 ? "Incomplete" : "Pass";
            if (school) {
                Double avg = scored == 0 ? null : round1(total / scored);
                row.put("total", scored == 0 ? null : round1(total));
                row.put("average", avg);
                if (avg != null) measures.add(avg);
            } else {
                // CGPA as at this semester: official results up to it, plus this semester's (even if not yet approved)
                Set<Long> semSheets = sem.stream().map(g -> g.mark().sheetId()).collect(Collectors.toSet());
                List<Graded> history = new ArrayList<>(official.getOrDefault(e.getKey(), List.of()).stream()
                        .filter(g -> !semSheets.contains(g.mark().sheetId()) && Standings.CHRONOLOGICAL.compare(g.mark(), here) < 0)
                        .toList());
                history.addAll(sem);
                Standing st = Standings.standing(history);
                BigDecimal gpa = Standings.gpa(sem);
                row.put("taken", sem.stream().mapToInt(Graded::credits).sum());
                row.put("passed", sem.stream().filter(Graded::passed).mapToInt(Graded::credits).sum());
                row.put("gpa", gpa);
                row.put("cgpa", st.cgpa());
                row.put("carry", st.outstanding().stream().map(g -> code(l, g)).collect(Collectors.joining(", ")));
                if (minCgpa.signum() > 0 && st.cgpa() != null && st.cgpa().compareTo(minCgpa) < 0)
                    remark += "; CGPA below " + minCgpa.stripTrailingZeros().toPlainString();
                if (gpa != null) measures.add(gpa.doubleValue());
            }
            row.put("remark", remark);
            if (!failed.isEmpty()) referred++; else if (ungraded > 0) incomplete++; else passedAll++;
            rows.add(row);
        }
        if (position) rank(rows, school ? "average" : "gpa");
        rows.forEach(tb::add);

        Table cs = r.table("courses", course + " summary")
                .text("code", course + " code").text("title", course + " title");
        if (!school) cs.col("credits", "Credit units", INT);
        cs.text("lecturer", t.get("lecturer")).col("students", t.get("student") + "s", INT).col("graded", "With grades", INT)
          .col("mean", "Mean", NUMBER).col("max", "Highest", NUMBER).col("min", "Lowest", NUMBER).col("pass", "Pass rate", PERCENT);
        List<String> letters = gradingService.bands().stream().map(GradeBand::getLetter).toList();
        for (String g : letters) cs.col("x_" + g, g, INT);
        for (Category c : courses) {
            List<MarkRow> ms = grid.values().stream().map(m -> m.get(c.getCid())).filter(Objects::nonNull).toList();
            List<Graded> gs = ms.stream().filter(Standings::isGraded).map(m -> standings.graded(m, defaultCredits)).toList();
            List<Double> scores = ms.stream().filter(m -> m.score() != null).map(m -> m.score().doubleValue()).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c.getCourseCode());
            row.put("title", c.getTitle());
            if (!school) row.put("credits", c.getCreditUnits());
            row.put("lecturer", name(c.getUser()));
            row.put("students", ms.size());
            row.put("graded", gs.size());
            row.put("mean", round1OrNull(scores.stream().mapToDouble(d -> d).average()));
            row.put("max", scores.isEmpty() ? null : Collections.max(scores));
            row.put("min", scores.isEmpty() ? null : Collections.min(scores));
            row.put("pass", pct(gs.stream().filter(Graded::passed).count(), gs.size()));
            Map<String, Long> by = gs.stream().collect(Collectors.groupingBy(g -> g.mark().grade().toUpperCase(), Collectors.counting()));
            for (String g : letters) row.put("x_" + g, by.getOrDefault(g.toUpperCase(), 0L));
            cs.add(row);
        }

        Table key = r.table("key", "Grading key").text("grade", "Grade").col("from", "From score", NUMBER);
        if (!school) key.col("point", "Grade point", DECIMAL);
        key.text("remark", "Remark").text("pass", "Pass");
        for (GradeBand b : gradingService.bands()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("grade", b.getLetter());
            row.put("from", b.getMinScore());
            if (!school) row.put("point", b.getGradePoint());
            row.put("remark", b.getRemark());
            row.put("pass", yesNo(b.isPassing()));
            key.add(row);
        }

        r.stat(t.get("student") + "s", rows.size())
         .stat(t.get("courses"), courses.size())
         .stat("Passed everything", passedAll, null, "good")
         .stat("Referred", referred, "Failed at least one " + course.toLowerCase(), referred > 0 ? "bad" : null)
         .stat("Incomplete", incomplete, "Some results not graded yet", incomplete > 0 ? "warn" : null)
         .stat(school ? "Class average" : "Average GPA", measures.isEmpty() ? null
                 : BigDecimal.valueOf(measures.stream().mapToDouble(d -> d).average().orElse(0)).setScale(2, RoundingMode.HALF_UP))
         .stat(school ? "Highest average" : "Highest GPA", measures.isEmpty() ? null : BigDecimal.valueOf(Collections.max(measures)).setScale(2, RoundingMode.HALF_UP));
        r.note("Sheets included: " + sheets.size() + " (" + sheetStatusLabel(f) + "). Each " + course.toLowerCase()
                + " column shows the mark, then the grade" + (school ? "." : "; the number in brackets is its credit units."));
        if (!school) r.note("GPA is for this " + t.get("semester").toLowerCase() + ". CGPA counts approved and published results up to and including it, plus this "
                + t.get("semester").toLowerCase() + "'s results even if not approved yet. Carry-overs are " + t.get("courses").toLowerCase()
                + " whose latest attempt so far was a fail. Refer lists this " + t.get("semester").toLowerCase() + "'s fails.");
        return r;
    }

    /** Adds "position": 1st, 2nd … by the measure, highest first; ties share a position. */
    private static void rank(List<Map<String, Object>> rows, String measure) {
        List<Map<String, Object>> sorted = rows.stream().filter(m -> m.get(measure) != null)
                .sorted(Comparator.comparing((Map<String, Object> m) -> ((Number) m.get(measure)).doubleValue()).reversed()).toList();
        int pos = 0;
        Double last = null;
        for (int i = 0; i < sorted.size(); i++) {
            double v = ((Number) sorted.get(i).get(measure)).doubleValue();
            if (last == null || v != last) pos = i + 1;
            last = v;
            sorted.get(i).put("position", ordinal(pos));
        }
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = (mod100 >= 11 && mod100 <= 13) ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return n + suffix;
    }

    private static String code(Lookups l, Graded g) {
        Category c = l.course(g.mark().courseId());
        return c == null ? "?" : Objects.toString(c.getCourseCode(), c.getTitle());
    }

    // ── Course result summary ────────────────────────────────────────────────

    public ReportResult courseResults(ReportFilters f) {
        Map<String, String> t = support.terms();
        String course = t.get("course"), student = t.get("student");
        ReportResult r = new ReportResult("course-results", course + " result summary",
                "For each " + course.toLowerCase() + ": who registered, who has a result, passes and fails, the spread of marks and grades.");
        Lookups l = support.lookups();
        Set<String> statuses = sheetStatuses(f);
        support.sessionScope(r, f);
        support.placeScope(r, f, l);
        r.scope("Marks sheets: " + sheetStatusLabel(f));

        List<SheetRow> sheets = queries.sheets().stream()
                .filter(s -> f.sessionId() == null || f.sessionId().equals(s.sessionId()))
                .filter(s -> statuses.contains(s.status()) && l.sheetMatches(s.programId(), s.level(), s.semester(), f)).toList();
        Set<Long> sheetIds = sheets.stream().map(SheetRow::id).collect(Collectors.toSet());
        List<MarkRow> marks = queries.marksOnSheets(sheetIds);
        Map<Long, Set<Long>> sheetCourses = queries.sheetCourses();
        Map<Long, StudentRow> info = queries.students().stream().collect(Collectors.toMap(StudentRow::id, s -> s));
        Map<Long, Set<Long>> registered = new HashMap<>();
        for (RegistrationRow x : queries.registrations()) {
            if (f.sessionId() != null && !f.sessionId().equals(x.sessionId())) continue;
            StudentRow s = info.get(x.studentId());
            // Only students of the chosen programme / department: a shared course has others too
            if (s == null || (f.programId() != null && !f.programId().equals(s.programId()))
                    || (f.departmentId() != null && !f.departmentId().equals(l.studentDept(s)))) continue;
            registered.computeIfAbsent(x.courseId(), k -> new HashSet<>()).add(x.studentId());
        }
        int defaultCredits = gradingService.defaultCreditUnits();

        Set<Long> courseIds = new HashSet<>();
        marks.forEach(m -> courseIds.add(m.courseId()));
        sheetIds.forEach(id -> courseIds.addAll(sheetCourses.getOrDefault(id, Set.of())));
        Map<Long, List<MarkRow>> byCourse = marks.stream().collect(Collectors.groupingBy(MarkRow::courseId));

        if (f.courseId() != null) {
            Category c = l.course(f.courseId());
            if (c == null) throw new IllegalArgumentException(course + " not found.");
            r.scope(course + ": " + Objects.toString(c.getCourseCode(), "") + " " + Objects.toString(c.getTitle(), ""));
            Table list = r.table("students", "Results for " + Objects.toString(c.getCourseCode(), c.getTitle()))
                    .text("studentId", t.get("studentId")).text("name", "Name").text("programme", t.get("program"))
                    .col("level", t.get("level"), INT).col("score", "Mark", NUMBER).text("grade", "Grade").text("result", "Result")
                    .text("status", "Sheet status");
            Map<Long, MarkRow> best = new HashMap<>();
            for (MarkRow m : byCourse.getOrDefault(c.getCid(), List.of())) {
                MarkRow had = best.get(m.studentId());
                if (had == null || (!Standings.isGraded(had) && Standings.isGraded(m))) best.put(m.studentId(), m);
            }
            Set<Long> everyone = new TreeSet<>(best.keySet());
            everyone.addAll(registered.getOrDefault(c.getCid(), Set.of()));
            everyone.stream().map(id -> new Object[]{id, info.get(id)}).filter(o -> o[1] != null)
                    .sorted(Comparator.comparing(o -> Objects.toString(((StudentRow) o[1]).username(), "")))
                    .forEach(o -> {
                        StudentRow s = (StudentRow) o[1];
                        MarkRow m = best.get(s.id());
                        String result = m == null ? "No result" : !Standings.isGraded(m) ? "Not graded"
                                : standings.graded(m, defaultCredits).passed() ? "Pass" : "Fail";
                        list.add(s.username(), s.name(), l.programName(s.programId()), s.level(), m == null ? null : m.score(),
                                m == null ? null : m.grade(), result, m == null ? null : AcademicReports.statusName(m.sheetStatus()));
                    });
        }

        List<String> letters = gradingService.bands().stream().map(GradeBand::getLetter).toList();
        Table tb = r.table("courses", course + "s")
                .text("code", course + " code").text("title", course + " title").text("lecturer", t.get("lecturer"))
                .text("status", "Sheet status").col("registered", "Registered", INT).col("withResult", "With a grade", INT)
                .col("noResult", "No result", INT).col("passed", "Passed", INT).col("failed", "Failed", INT)
                .col("pass", "Pass rate", PERCENT).col("mean", "Mean", NUMBER).col("sd", "Std dev", NUMBER)
                .col("max", "Highest", NUMBER).col("min", "Lowest", NUMBER);
        for (String g : letters) tb.col("x_" + g, g, INT);
        tb.drill("courseId", "courseId", "List each " + student.toLowerCase() + "'s mark for this " + course.toLowerCase())
          .emptyText("No marks sheets match these filters.");

        long totalGraded = 0, totalPassed = 0, missing = 0;
        List<Category> courses = courseIds.stream().map(l::course).filter(Objects::nonNull).filter(c -> l.taughtBy(c, f))
                .sorted(Comparator.comparingInt((Category c) -> levelNumber(c.getLevel()))
                        .thenComparing(c -> Objects.toString(c.getCourseCode(), ""))).toList();
        for (Category c : courses) {
            List<MarkRow> ms = byCourse.getOrDefault(c.getCid(), List.of());
            Map<Long, Graded> graded = new HashMap<>();
            for (MarkRow m : ms) if (Standings.isGraded(m)) graded.put(m.studentId(), standings.graded(m, defaultCredits));
            Set<Long> reg = registered.getOrDefault(c.getCid(), Set.of());
            Set<Long> expected = new HashSet<>(reg);
            ms.forEach(m -> expected.add(m.studentId()));
            long noResult = expected.stream().filter(id -> !graded.containsKey(id)).count();
            long passed = graded.values().stream().filter(Graded::passed).count();
            List<Double> scores = graded.values().stream().filter(g -> g.mark().score() != null).map(g -> g.mark().score().doubleValue()).toList();
            Map<String, Long> by = graded.values().stream().collect(Collectors.groupingBy(g -> g.mark().grade().toUpperCase(), Collectors.counting()));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c.getCourseCode());
            row.put("title", c.getTitle());
            row.put("lecturer", name(c.getUser()));
            row.put("status", ms.stream().map(m -> AcademicReports.statusName(m.sheetStatus())).distinct().sorted().collect(Collectors.joining(", ")));
            row.put("registered", reg.size());
            row.put("withResult", graded.size());
            row.put("noResult", noResult);
            row.put("passed", passed);
            row.put("failed", graded.size() - passed);
            row.put("pass", pct(passed, graded.size()));
            row.put("mean", round1OrNull(scores.stream().mapToDouble(d -> d).average()));
            row.put("sd", stdDev(scores));
            row.put("max", scores.isEmpty() ? null : Collections.max(scores));
            row.put("min", scores.isEmpty() ? null : Collections.min(scores));
            for (String g : letters) row.put("x_" + g, by.getOrDefault(g.toUpperCase(), 0L));
            row.put("courseId", c.getCid());
            tb.add(row);
            totalGraded += graded.size();
            totalPassed += passed;
            if (noResult > 0) missing++;
        }

        r.stat(course + "s", courses.size())
         .stat("Results", totalGraded)
         .stat("Pass rate", AcademicReports.fmtPct(pct(totalPassed, totalGraded)))
         .stat(course + "s with missing results", missing, null, missing > 0 ? "warn" : null);
        r.note("Registered counts " + student.toLowerCase() + "s registered for the " + course.toLowerCase()
                + (f.sessionId() == null ? "" : " in the session") + ". No result = registered or on the sheet, but without a grade.");
        r.note("Click a " + course.toLowerCase() + " to list every " + student.toLowerCase() + "'s mark and grade.");
        return r;
    }

    // ── Top performers ───────────────────────────────────────────────────────

    public ReportResult topPerformers(ReportFilters f) {
        Map<String, String> t = support.terms();
        String student = t.get("student");
        ReportResult r = new ReportResult("top-performers", "Top performers and Dean's list",
                "The highest CGPAs in each " + t.get("program").toLowerCase() + " and " + t.get("level").toLowerCase()
                        + ", the best " + t.get("semester").toLowerCase() + " GPAs, and the Dean's list.");
        Lookups l = support.lookups();
        support.sessionScope(r, f);
        support.placeScope(r, f, l);

        Map<Long, StudentRow> students = queries.students().stream().filter(StudentRow::enabled)
                .filter(s -> f.departmentId() == null || f.departmentId().equals(l.studentDept(s)))
                .filter(s -> f.programId() == null || f.programId().equals(s.programId()))
                .collect(Collectors.toMap(StudentRow::id, s -> s));
        Map<Long, List<Graded>> official = standings.official();
        int top = 10;

        // Highest CGPA, by programme and current level
        Table cg = r.table("cgpa", "Highest CGPA").subtitle("Top " + top + " in each " + t.get("program").toLowerCase()
                        + " and current " + t.get("level").toLowerCase() + ", from every approved and published result.")
                .text("programme", t.get("program")).col("level", t.get("level"), INT).text("rank", "Rank")
                .text("studentId", t.get("studentId")).text("name", "Name").col("cgpa", "CGPA", DECIMAL).text("class", "Class")
                .col("earned", "Credits earned", INT);
        Map<List<Object>, List<Object[]>> groups = new TreeMap<>(Comparator.comparing((List<Object> k) -> Objects.toString(k.get(0), ""))
                .thenComparing(k -> (Integer) k.get(1)));
        BigDecimal best = null;
        String bestName = null;
        for (StudentRow s : students.values()) {
            if (f.level() != null && !f.level().equals(s.level())) continue;
            List<Graded> gs = official.get(s.id());
            if (gs == null || gs.isEmpty()) continue;
            Standing st = Standings.standing(gs);
            if (st.cgpa() == null) continue;
            groups.computeIfAbsent(Arrays.asList(l.programName(s.programId()), s.level() == null ? 0 : s.level()), k -> new ArrayList<>())
                    .add(new Object[]{s, st});
            if (best == null || st.cgpa().compareTo(best) > 0) { best = st.cgpa(); bestName = s.name(); }
        }
        groups.forEach((k, list) -> {
            list.sort(Comparator.comparing((Object[] o) -> ((Standing) o[1]).cgpa()).reversed());
            int pos = 0;
            BigDecimal last = null;
            for (int i = 0; i < Math.min(top, list.size()); i++) {
                StudentRow s = (StudentRow) list.get(i)[0];
                Standing st = (Standing) list.get(i)[1];
                if (last == null || st.cgpa().compareTo(last) != 0) pos = i + 1;
                last = st.cgpa();
                cg.add(k.get(0), (Integer) k.get(1) == 0 ? null : k.get(1), ordinal(pos), s.username(), s.name(), st.cgpa(),
                        gradingService.classFor(st.cgpa()), st.creditsEarned());
            }
        });

        // Semester GPAs from the results earned in that semester
        record Term(Long studentId, Long sessionId, String level, Integer semester) {}
        Map<Term, List<Graded>> terms = new HashMap<>();
        official.forEach((id, gs) -> {
            if (!students.containsKey(id)) return;
            for (Graded g : gs) {
                MarkRow m = g.mark();
                if (f.sessionId() != null && !f.sessionId().equals(m.sessionId())) continue;
                if (f.semester() != null && !f.semester().equals(m.semester())) continue;
                if (f.level() != null && !String.valueOf(f.level()).equals(normLevel(m.level()))) continue;
                terms.computeIfAbsent(new Term(id, m.sessionId(), normLevel(m.level()), m.semester()), k -> new ArrayList<>()).add(g);
            }
        });
        Map<Long, String> sessionNames = sessions().values().stream().collect(Collectors.toMap(AcademicSession::getId, AcademicSession::getName));
        BigDecimal threshold = gradingService.classes().isEmpty() ? null : gradingService.classes().get(0).getMinCgpa();

        Table sem = r.table("semester", "Best " + t.get("semester").toLowerCase() + " GPA")
                .subtitle("Top " + top + " in each " + t.get("program").toLowerCase() + ", " + t.get("level").toLowerCase() + " and "
                        + t.get("semester").toLowerCase() + ".")
                .text("session", "Session").text("semester", t.get("semester")).text("programme", t.get("program"))
                .col("level", t.get("level"), INT).text("rank", "Rank").text("studentId", t.get("studentId")).text("name", "Name")
                .col("gpa", "GPA", DECIMAL).col("credits", "Credits", INT);
        Table deans = r.table("deans", "Dean's list")
                .subtitle(threshold == null ? "Set the classes of degree in Sessions & Grading to define the Dean's list."
                        : t.get("semester") + " GPA of " + threshold.stripTrailingZeros().toPlainString()
                        + " or more (the top class) with no failed " + t.get("course").toLowerCase() + ".")
                .text("session", "Session").text("semester", t.get("semester")).text("studentId", t.get("studentId"))
                .text("name", "Name").text("programme", t.get("program")).col("level", t.get("level"), INT)
                .col("gpa", "GPA", DECIMAL).col("credits", "Credits", INT)
                .emptyText("Nobody reached the Dean's list for these filters.");

        Map<List<Object>, List<Object[]>> semGroups = new TreeMap<>(Comparator
                .comparing((List<Object> k) -> Objects.toString(k.get(0), ""), Comparator.reverseOrder())
                .thenComparing(k -> Objects.toString(k.get(1), ""))
                .thenComparing(k -> Objects.toString(k.get(2), ""))
                .thenComparing(k -> levelNumber((String) k.get(3))));
        List<Object[]> deanRows = new ArrayList<>();
        terms.forEach((term, gs) -> {
            BigDecimal gpa = Standings.gpa(gs);
            if (gpa == null) return;
            StudentRow s = students.get(term.studentId());
            int credits = gs.stream().mapToInt(Graded::credits).sum();
            String sessionName = term.sessionId() == null ? null : sessionNames.get(term.sessionId());
            semGroups.computeIfAbsent(Arrays.asList(sessionName, support.semesterName(term.semester()), l.programName(s.programId()), term.level()),
                    k -> new ArrayList<>()).add(new Object[]{s, gpa, credits});
            if (threshold != null && gpa.compareTo(threshold) >= 0 && gs.stream().allMatch(Graded::passed))
                deanRows.add(new Object[]{sessionName, support.semesterName(term.semester()), s, term.level(), gpa, credits});
        });
        semGroups.forEach((k, list) -> {
            list.sort(Comparator.comparing((Object[] o) -> (BigDecimal) o[1]).reversed());
            int pos = 0;
            BigDecimal last = null;
            for (int i = 0; i < Math.min(top, list.size()); i++) {
                StudentRow s = (StudentRow) list.get(i)[0];
                BigDecimal gpa = (BigDecimal) list.get(i)[1];
                if (last == null || gpa.compareTo(last) != 0) pos = i + 1;
                last = gpa;
                sem.add(k.get(0), k.get(1), k.get(2), levelNumber((String) k.get(3)) == 0 ? null : levelNumber((String) k.get(3)),
                        ordinal(pos), s.username(), s.name(), gpa, list.get(i)[2]);
            }
        });
        deanRows.sort(Comparator.comparing((Object[] o) -> Objects.toString(o[0], ""), Comparator.reverseOrder())
                .thenComparing(o -> ((BigDecimal) o[4]), Comparator.reverseOrder()));
        for (Object[] o : deanRows) {
            StudentRow s = (StudentRow) o[2];
            deans.add(o[0], o[1], s.username(), s.name(), l.programName(s.programId()),
                    levelNumber((String) o[3]) == 0 ? null : levelNumber((String) o[3]), o[4], o[5]);
        }

        r.stat("Highest CGPA", best, bestName, best == null ? null : "good")
         .stat(student + "s with a CGPA", groups.values().stream().mapToInt(List::size).sum())
         .stat("Dean's list entries", deanRows.size(), threshold == null ? null : "GPA " + threshold.stripTrailingZeros().toPlainString() + "+", null);
        r.note("Uses approved and published results only. The CGPA ranking covers active " + student.toLowerCase()
                + "s by their current " + t.get("level").toLowerCase() + "; the session and " + t.get("semester").toLowerCase()
                + " filters apply to the " + t.get("semester").toLowerCase() + " GPA and the Dean's list.");
        return r;
    }
}
