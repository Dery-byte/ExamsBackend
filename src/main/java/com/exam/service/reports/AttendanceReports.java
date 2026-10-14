package com.exam.service.reports;

import com.exam.model.academic.AcademicSession;
import com.exam.model.attendance.AttendanceMode;
import com.exam.model.attendance.AttendanceRecord;
import com.exam.model.attendance.AttendanceSession;
import com.exam.model.attendance.AttendanceStatus;
import com.exam.model.attendance.CodeDisplay;
import com.exam.model.exam.Category;
import com.exam.model.exam.Program;
import com.exam.repository.AttendanceRecordRepository;
import com.exam.repository.AttendanceSessionRepository;
import com.exam.service.attendance.AttendanceMinimum;
import com.exam.service.attendance.AttendanceTally;
import com.exam.service.reports.ReportQueries.StudentRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.exam.service.reports.ReportResult.*;
import static com.exam.service.reports.ReportSupport.*;

/**
 * Class attendance reports: the register (students × meetings), each student's attendance per
 * course or programme, those below a minimum, and every meeting held.
 * <p>
 * Percentages follow {@link AttendanceTally}, as on the student's own page and for the quiz
 * minimum: present and late count as attended, excused and waiting meetings are left out.
 * A course's meetings are one class; a programme's meetings (whatever their level) are one class.
 */
@Service
@Transactional(readOnly = true)
public class AttendanceReports {

    @Autowired private ReportSupport support;
    @Autowired private ReportQueries queries;
    @Autowired private AttendanceSessionRepository sessions;
    @Autowired private AttendanceRecordRepository records;
    @Autowired private AttendanceMinimum minimum;

    /** Low-attendance thresholds offered as the report's "status" filter. */
    public static final List<String> THRESHOLDS = List.of("50", "60", "70", "75", "80", "90");

    // ── Loading ──────────────────────────────────────────────────────────────

    /** The meetings inside the filters, with every record for them. */
    private record Data(List<AttendanceSession> meetings, Map<Long, List<Rec>> bySession, Map<Long, StudentRow> students, String periodLine) {}

    private record Rec(Long sessionId, Long studentId, AttendanceStatus status, String flags) {
        boolean flagged(String flag) { return flags != null && Arrays.asList(flags.split(",")).contains(flag); }
    }

    private Data load(ReportFilters f, Lookups l, boolean programmeOnly) {
        List<AttendanceSession> all;
        String periodLine;
        if (f.from() != null || f.to() != null) {
            Period p = Period.of(f.from(), f.to(), null);
            all = sessions.findByOpenedAtGreaterThanEqualAndOpenedAtLessThanOrderByOpenedAtAsc(
                    p.from() == null ? Period.EARLIEST : p.from(), p.to() == null ? Period.LATEST : p.to());
            if (f.sessionId() != null) all = all.stream().filter(s -> s.getAcademicSession() != null && f.sessionId().equals(s.getAcademicSession().getId())).toList();
            periodLine = "Period: " + p.label();
        } else {
            AcademicSession year = f.sessionId() != null ? support.session(f.sessionId()) : support.currentSession();
            all = sessions.findByAcademicSession_IdOrderByOpenedAtAsc(year.getId());
            periodLine = "Session: " + year.getName() + (f.sessionId() == null ? " (current)" : "");
        }
        List<AttendanceSession> meetings = all.stream()
                .filter(s -> matches(s, f, l))
                .filter(s -> !programmeOnly || s.getScope() == AttendanceMode.PROGRAMME)
                .toList();

        Map<Long, List<Rec>> bySession = new HashMap<>();
        List<Long> ids = meetings.stream().map(AttendanceSession::getId).toList();
        for (int i = 0; i < ids.size(); i += 900) {
            for (Object[] row : records.reportRows(ids.subList(i, Math.min(ids.size(), i + 900)))) {
                Rec r = new Rec((Long) row[0], (Long) row[1], (AttendanceStatus) row[2], (String) row[3]);
                bySession.computeIfAbsent(r.sessionId(), k -> new ArrayList<>()).add(r);
            }
        }
        // Programme and level filters also narrow the students (a course can be shared by several programmes)
        ReportFilters who = new ReportFilters(null, null, f.programId(), f.level(), null, null, null, null, null, null);
        Map<Long, StudentRow> students = queries.students().stream()
                .filter(s -> l.studentMatches(s, who))
                .collect(Collectors.toMap(StudentRow::id, s -> s, (a, b) -> a));
        return new Data(meetings, bySession, students, periodLine);
    }

    /** Whether a meeting falls inside the department / programme / level / course filters and the lecturer lock. */
    private static boolean matches(AttendanceSession s, ReportFilters f, Lookups l) {
        Category c = s.getCourse();
        Program p = s.getProgram();
        boolean course = s.getScope() == AttendanceMode.COURSE;
        if (f.lecturerId() != null && !(course && c != null && c.getUser() != null && f.lecturerId().equals(c.getUser().getId()))) return false;
        if (f.courseId() != null && !(course && c != null && f.courseId().equals(c.getCid()))) return false;
        if (f.departmentId() != null) {
            boolean in = course
                    ? (c != null && l.courseDepts(c).contains(f.departmentId())) || (s.getDepartment() != null && f.departmentId().equals(s.getDepartment().getId()))
                    : p != null && p.getDepartment() != null && f.departmentId().equals(p.getDepartment().getId());
            if (!in) return false;
        }
        if (f.programId() != null) {
            boolean in = course ? c != null && l.courseProgramIds(c).contains(f.programId()) : p != null && f.programId().equals(p.getId());
            if (!in) return false;
        }
        if (f.level() != null) {
            boolean in = course ? c != null && String.valueOf(f.level()).equals(normLevel(c.getLevel()))
                    : s.getLevel() == null || f.level().equals(s.getLevel());
            if (!in) return false;
        }
        return true;
    }

    private static String classKey(AttendanceSession s) {
        return s.getScope() == AttendanceMode.COURSE
                ? "C" + (s.getCourse() != null ? s.getCourse().getCid() : 0)
                : "P" + (s.getProgram() != null ? s.getProgram().getId() : 0);
    }

    private static String classTitle(AttendanceSession s) {
        if (s.getScope() == AttendanceMode.COURSE && s.getCourse() != null) return TeachingReports.courseLabel(s.getCourse());
        return s.getProgram() != null ? s.getProgram().getName() : "Class";
    }

    private Integer minimumFor(AttendanceSession s) {
        return s.getScope() == AttendanceMode.COURSE ? minimum.minimumForCourse(s.getCourse()) : minimum.minimumForProgramme(s.getProgram());
    }

    private void scope(ReportResult r, ReportFilters f, Lookups l, Data d) {
        r.scope(d.periodLine());
        if (f.lecturerId() == null) support.placeScope(r, f, l);
        if (f.courseId() != null && l.course(f.courseId()) != null)
            r.scope(support.terms().get("course") + ": " + TeachingReports.courseLabel(l.course(f.courseId())));
    }

    private static String code(AttendanceStatus s) {
        if (s == null) return "–";
        return switch (s) {
            case PRESENT -> "P";
            case LATE -> "L";
            case ABSENT -> "A";
            case EXCUSED -> "E";
            case PENDING -> "W";
            case REJECTED -> "X";
        };
    }

    /** One student in one class: their tally over the finished meetings. */
    private record Line(String classKey, String classTitle, Long studentId, AttendanceTally tally, Integer minimum) {}

    private List<Line> lines(Data d) {
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, Integer> minimums = new HashMap<>();
        Map<String, AttendanceTally> tallies = new LinkedHashMap<>();
        for (AttendanceSession s : d.meetings()) {
            String key = classKey(s);
            titles.putIfAbsent(key, classTitle(s));
            if (!minimums.containsKey(key)) minimums.put(key, minimumFor(s));
            if (s.isLive()) continue;   // counts once it has ended
            for (Rec r : d.bySession().getOrDefault(s.getId(), List.of())) {
                if (!d.students().containsKey(r.studentId())) continue;
                tallies.computeIfAbsent(key + "|" + r.studentId(), k -> new AttendanceTally()).add(r.status());
            }
        }
        List<Line> out = new ArrayList<>();
        tallies.forEach((k, t) -> {
            String key = k.substring(0, k.indexOf('|'));
            Long sid = Long.valueOf(k.substring(k.indexOf('|') + 1));
            out.add(new Line(key, titles.get(key), sid, t, minimums.get(key)));
        });
        return out;
    }

    // ── Register ─────────────────────────────────────────────────────────────

    public ReportResult register(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("attendance-register", "Attendance register",
                "Every " + t.get("student").toLowerCase() + " against every class meeting, with their attendance percentage.");
        if (f.courseId() == null && f.programId() == null) {
            r.table("register", "Register").text("studentId", t.get("studentId")).text("name", "Name")
                    .emptyText("Choose a " + t.get("course").toLowerCase() + (f.lecturerId() == null ? " (or a " + t.get("program").toLowerCase() + ")" : "")
                            + " above to see its register.");
            return r;
        }
        Lookups l = support.lookups();
        Data d = load(f, l, f.courseId() == null);
        scope(r, f, l, d);

        List<AttendanceSession> meetings = d.meetings();
        Table tb = r.table("register", "Register").text("studentId", t.get("studentId")).text("name", "Name");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM");
        for (AttendanceSession s : meetings)
            tb.text("m" + s.getId(), "#" + s.getMeetingNumber() + " " + fmt.format(s.getOpenedAt()) + (s.isLive() ? " (live)" : ""));
        Integer min = meetings.isEmpty() ? null : minimumFor(meetings.get(0));
        tb.col("attended", "Attended", INT).col("counted", "Counted", INT).col("percent", "Attendance", PERCENT);
        if (min != null) tb.text("meets", "Meets " + min + "%");
        tb.emptyText("No class meetings for these filters yet.");

        Map<Long, Map<Long, Rec>> grid = new HashMap<>();   // student → session → record
        for (AttendanceSession s : meetings)
            for (Rec x : d.bySession().getOrDefault(s.getId(), List.of()))
                if (d.students().containsKey(x.studentId())) grid.computeIfAbsent(x.studentId(), k -> new HashMap<>()).put(s.getId(), x);

        List<StudentRow> people = grid.keySet().stream().map(d.students()::get)
                .sorted(Comparator.comparing((StudentRow s) -> s.name().toLowerCase()).thenComparing(s -> Objects.toString(s.username(), "")))
                .toList();
        int below = 0;
        List<Double> percents = new ArrayList<>();
        for (StudentRow s : people) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", s.username());
            row.put("name", s.name());
            AttendanceTally tally = new AttendanceTally();
            for (AttendanceSession m : meetings) {
                Rec x = grid.get(s.id()).get(m.getId());
                row.put("m" + m.getId(), code(x == null ? null : x.status()));
                if (x != null && !m.isLive()) tally.add(x.status());
            }
            row.put("attended", tally.attended());
            row.put("counted", tally.counted());
            row.put("percent", tally.percent());
            if (tally.percent() != null) percents.add(tally.percent());
            if (min != null) {
                boolean low = tally.below(min);
                if (low) below++;
                row.put("meets", tally.percent() == null ? "—" : low ? "No" : "Yes");
            }
            tb.add(row);
        }

        long held = meetings.stream().filter(m -> !m.isLive()).count();
        r.stat("Meetings held", held)
         .stat(t.get("student") + "s", people.size())
         .stat("Average attendance", percents.isEmpty() ? null : round1(percents.stream().mapToDouble(Double::doubleValue).average().orElse(0)) + "%");
        if (min != null) r.stat("Below " + min + "%", below, below > 0 ? "Blocked from starting quizzes" : null, below > 0 ? "bad" : "good");
        r.note("P present · L late · A absent · E excused · W waiting for the lecturer's review · X not accepted · – not on the list for that meeting.");
        r.note("Attendance = present and late, out of the finished meetings that count (excused and waiting meetings are left out).");
        return r;
    }

    // ── Summary per student ──────────────────────────────────────────────────

    public ReportResult summary(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("attendance-summary", "Attendance summary",
                "Each " + t.get("student").toLowerCase() + "'s attendance per " + t.get("course").toLowerCase() + " or " + t.get("program").toLowerCase() + ".");
        Lookups l = support.lookups();
        Data d = load(f, l, false);
        scope(r, f, l, d);
        List<Line> lines = lines(d);

        Table byClass = r.table("classes", "By class").text("class", "Class").col("meetings", "Meetings held", INT)
                .col("students", t.get("student") + "s", INT).col("average", "Average attendance", PERCENT)
                .text("minimum", "Minimum").col("below", "Below minimum", INT).chart("class", "average")
                .emptyText("No class meetings for these filters yet.");
        Map<String, Long> meetingsHeld = d.meetings().stream().filter(m -> !m.isLive())
                .collect(Collectors.groupingBy(AttendanceReports::classKey, Collectors.counting()));
        lines.stream().collect(Collectors.groupingBy(Line::classKey, LinkedHashMap::new, Collectors.toList())).forEach((k, g) -> {
            Line first = g.get(0);
            OptionalDouble avg = g.stream().map(x -> x.tally().percent()).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
            long below = first.minimum() == null ? 0 : g.stream().filter(x -> x.tally().below(first.minimum())).count();
            byClass.add(first.classTitle(), meetingsHeld.getOrDefault(k, 0L), g.size(), round1OrNull(avg),
                    first.minimum() == null ? "—" : first.minimum() + "%", first.minimum() == null ? null : below);
        });

        Table tb = r.table("students", t.get("student") + "s").text("studentId", t.get("studentId")).text("name", "Name").text("class", "Class")
                .col("counted", "Meetings counted", INT).col("attended", "Attended", INT).col("late", "Late", INT).col("absent", "Absent", INT)
                .col("excused", "Excused", INT).col("waiting", "Waiting", INT).col("percent", "Attendance", PERCENT).text("meets", "Meets minimum")
                .emptyText("No attendance for these filters yet.");
        List<Line> sorted = lines.stream()
                .sorted(Comparator.comparing((Line x) -> x.tally().percent() == null ? 101.0 : x.tally().percent())
                        .thenComparing(x -> sortName(d, x.studentId())))
                .toList();
        int below = 0;
        for (Line x : sorted) {
            StudentRow s = d.students().get(x.studentId());
            AttendanceTally a = x.tally();
            boolean low = x.minimum() != null && a.below(x.minimum());
            if (low) below++;
            tb.add(s.username(), s.name(), x.classTitle(), a.counted(), a.attended(), a.late(), a.absent(), a.excused(), a.pending(), a.percent(),
                    x.minimum() == null ? "—" : a.percent() == null ? "Not yet" : low ? "No (needs " + x.minimum() + "%)" : "Yes");
        }

        OptionalDouble avg = lines.stream().map(x -> x.tally().percent()).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
        r.stat("Classes", byClass.getRows().size())
         .stat("Meetings held", meetingsHeld.values().stream().mapToLong(Long::longValue).sum())
         .stat("Average attendance", avg.isPresent() ? round1(avg.getAsDouble()) + "%" : null)
         .stat("Below the minimum", below, below > 0 ? "Blocked from starting quizzes" : null, below > 0 ? "bad" : null);
        r.note("Lowest attendance first. Attendance = present and late, out of the finished meetings that count (excused and waiting are left out).");
        r.note("A minimum shows only where the Super Admin or HOD has switched one on.");
        return r;
    }

    private static String sortName(Data d, Long studentId) {
        StudentRow s = d.students().get(studentId);
        return s == null ? "" : s.name().toLowerCase();
    }

    // ── Low attendance ───────────────────────────────────────────────────────

    public ReportResult low(ReportFilters f) {
        Map<String, String> t = support.terms();
        if (f.hasStatus() && !THRESHOLDS.contains(f.status().trim())) throw new IllegalArgumentException("Choose one of the thresholds offered.");
        Integer chosen = f.hasStatus() ? Integer.valueOf(f.status().trim()) : null;
        ReportResult r = new ReportResult("attendance-low", "Low attendance",
                t.get("student") + "s whose attendance is below " + (chosen != null ? chosen + "%" : "the minimum") + ", with contact details for follow-up.");
        Lookups l = support.lookups();
        Data d = load(f, l, false);
        scope(r, f, l, d);
        r.scope("Below: " + (chosen != null ? chosen + "%" : "each class's minimum (75% where none is set)"));

        Table tb = r.table("students", t.get("student") + "s").text("studentId", t.get("studentId")).text("name", "Name").text("class", "Class")
                .col("percent", "Attendance", PERCENT).col("attended", "Attended", INT).col("counted", "Meetings counted", INT)
                .col("absent", "Absent", INT).text("threshold", "Below").text("blocked", "Quizzes blocked").text("phone", "Phone").text("email", "Email")
                .emptyText("No one is below the threshold.");
        int blocked = 0, rows = 0;
        Set<Long> people = new HashSet<>();
        List<Line> lines = lines(d).stream()
                .sorted(Comparator.comparing((Line x) -> x.tally().percent() == null ? 101.0 : x.tally().percent()).thenComparing(x -> sortName(d, x.studentId())))
                .toList();
        for (Line x : lines) {
            int threshold = chosen != null ? chosen : x.minimum() != null ? x.minimum() : 75;
            if (!x.tally().below(threshold)) continue;
            StudentRow s = d.students().get(x.studentId());
            boolean isBlocked = x.minimum() != null && x.tally().below(x.minimum());
            if (isBlocked) blocked++;
            rows++;
            people.add(x.studentId());
            tb.add(s.username(), s.name(), x.classTitle(), x.tally().percent(), x.tally().attended(), x.tally().counted(), x.tally().absent(),
                    threshold + "%", isBlocked ? "Yes" : "No", s.phone(), s.email());
        }
        r.stat(t.get("student") + "s", people.size(), null, people.isEmpty() ? "good" : "warn")
         .stat("Class entries", rows)
         .stat("Blocked from starting quizzes", blocked, null, blocked > 0 ? "bad" : null);
        r.note("Quizzes blocked = a minimum is switched on for that class and the " + t.get("student").toLowerCase()
                + " is below it, so they can't start its quizzes (any quiz, for a " + t.get("program").toLowerCase() + "). Excusing absences raises the percentage.");
        return r;
    }

    // ── Meetings ─────────────────────────────────────────────────────────────

    public ReportResult meetings(ReportFilters f) {
        Map<String, String> t = support.terms();
        ReportResult r = new ReportResult("attendance-sessions", "Class meetings",
                "Every class meeting where attendance was taken: who was there, check-ins waiting for review, and the lecturer's headcount.");
        Lookups l = support.lookups();
        Data d = load(f, l, false);
        scope(r, f, l, d);

        Table tb = r.table("meetings", "Meetings").col("date", "Started", DATETIME).text("class", "Class").col("meeting", "Meeting", INT)
                .text("topic", "Topic").text("by", "Started by").text("place", "Place").text("code", "Code shown")
                .col("listed", "On the list", INT).col("present", "Present", INT).col("late", "Late", INT).col("absent", "Absent", INT)
                .col("excused", "Excused", INT).col("waiting", "Waiting", INT).col("headcount", "Headcount", INT)
                .col("percent", "Attended", PERCENT).col("missed", "Missed 2nd check", INT).text("status", "Status")
                .emptyText("No class meetings for these filters.");
        int over = 0, waiting = 0;
        List<Double> percents = new ArrayList<>();
        List<AttendanceSession> newestFirst = new ArrayList<>(d.meetings());
        Collections.reverse(newestFirst);
        for (AttendanceSession s : newestFirst) {
            List<Rec> recs = d.bySession().getOrDefault(s.getId(), List.of());
            AttendanceTally a = new AttendanceTally();
            recs.forEach(x -> a.add(x.status()));
            long missed = recs.stream().filter(x -> x.flagged(AttendanceRecord.FLAG_MISSED_SECOND_CHECK)).count();
            int checkedIn = a.attended() + a.pending();
            if (s.getHeadcount() != null && checkedIn > s.getHeadcount()) over++;
            waiting += a.pending();
            if (!s.isLive() && a.percent() != null) percents.add(a.percent());
            tb.add(minute(s.getOpenedAt()), classTitle(s) + (s.getScope() == AttendanceMode.PROGRAMME && s.getLevel() != null ? " · " + t.get("level") + " " + s.getLevel() : ""),
                    s.getMeetingNumber(), s.getTopic(), name(s.getOpenedBy()), s.getPlaceName(),
                    s.getCodeDisplay() == CodeDisplay.READ_ALOUD ? "Read aloud" : "Projector", a.held(), a.attended() - a.late(), a.late(), a.absent(),
                    a.excused(), a.pending(), s.getHeadcount(), a.percent(), missed, s.isLive() ? "Live" : "Ended");
        }
        r.stat("Meetings", d.meetings().size())
         .stat("Average attended", percents.isEmpty() ? null : round1(percents.stream().mapToDouble(Double::doubleValue).average().orElse(0)) + "%")
         .stat("Waiting for review", waiting, null, waiting > 0 ? "warn" : null)
         .stat("More check-ins than headcount", over, over > 0 ? "Possible check-ins from outside the room" : null, over > 0 ? "bad" : null);
        r.note("Newest first. A meeting still running shows the check-ins so far; absentees are added when it ends.");
        return r;
    }

    /** "Below 75%" style labels for the low-attendance filter. */
    public static String thresholdLabel(String v) { return "Below " + v + "%"; }
}
