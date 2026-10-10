package com.exam.service.reports;

import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Category;
import com.exam.model.exam.Program;
import com.exam.model.exam.Quiz;
import com.exam.repository.*;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.comms.AnalyticsService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** What every report needs: lookups, the period, the institution's words, scope lines and number helpers. */
@Component
public class ReportSupport {

    /** Quiz scores at or above this percentage count as a pass (same as Analytics). */
    public static final double PASS_MARK = AnalyticsService.PASS_MARK;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private AcademicSessionRepository sessionRepository;
    @Autowired private AcademicSessionService academicSessionService;
    @Autowired private InstitutionService institutionService;

    public Lookups lookups() {
        return new Lookups(departmentRepository.findAll(), programRepository.findAll(),
                categoryRepository.findAll(), quizRepository.findAll());
    }

    public AcademicSession session(Long id) {
        return sessionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Academic session not found."));
    }

    public AcademicSession currentSession() { return academicSessionService.current(); }

    /** The session whose dates include this day, else the current session. */
    public AcademicSession sessionOn(LocalDate day) {
        if (day != null)
            for (AcademicSession s : sessionRepository.findAll())
                if (s.getStartDate() != null && s.getEndDate() != null && !day.isBefore(s.getStartDate()) && !day.isAfter(s.getEndDate()))
                    return s;
        return currentSession();
    }

    /** The chosen dates, else the chosen session's dates, else all time. */
    public Period period(ReportFilters f) {
        if (f.from() != null || f.to() != null) return Period.of(f.from(), f.to(), null);
        if (f.sessionId() == null) return Period.allTime();
        AcademicSession s = session(f.sessionId());
        if (s.getStartDate() == null && s.getEndDate() == null)
            return new Period(null, null, s.getName() + " (no dates set, so all time)");
        return Period.of(s.getStartDate(), s.getEndDate(), s.getName() + " (" + rangeText(s.getStartDate(), s.getEndDate()) + ")");
    }

    private static String rangeText(LocalDate a, LocalDate b) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy");
        return (a == null ? "…" : f.format(a)) + " – " + (b == null ? "…" : f.format(b));
    }

    // ── Department scope (HODs) ──────────────────────────────────────────────

    /**
     * Locks the filters to the HOD's department, refusing a programme, course or quiz from
     * another department (they could otherwise be passed by hand in the URL).
     */
    public ReportFilters scopeToDepartment(ReportFilters f, User hod) {
        if (hod.getDepartment() == null) throw new AccessDeniedException("Your account is not linked to a department.");
        Long dept = hod.getDepartment().getId();
        if (f.programId() != null) {
            Program p = programRepository.findById(f.programId()).orElseThrow(() -> new IllegalArgumentException("Programme not found."));
            if (p.getDepartment() == null || !dept.equals(p.getDepartment().getId()))
                throw new AccessDeniedException("That programme is not in your department.");
        }
        if (f.courseId() != null) {
            Category c = categoryRepository.findById(f.courseId()).orElseThrow(() -> new IllegalArgumentException("Course not found."));
            if (!inDepartment(c, dept)) throw new AccessDeniedException("That course is not in your department.");
        }
        if (f.quizId() != null) {
            Quiz q = quizRepository.findById(f.quizId()).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
            if (!inDepartment(q.getCategory(), dept)) throw new AccessDeniedException("That quiz is not in your department.");
        }
        return f.withDepartment(dept);
    }

    /**
     * Locks the filters to a lecturer: courses they teach and quizzes they manage (they set it or
     * teach its course), refusing any other course or quiz passed by hand.
     */
    public ReportFilters scopeToLecturer(ReportFilters f, User lecturer) {
        Long id = lecturer.getId();
        if (f.courseId() != null) {
            Category c = categoryRepository.findById(f.courseId()).orElseThrow(() -> new IllegalArgumentException("Course not found."));
            if (c.getUser() == null || !id.equals(c.getUser().getId()))
                throw new AccessDeniedException("You do not teach that course.");
        }
        if (f.quizId() != null) {
            Quiz q = quizRepository.findById(f.quizId()).orElseThrow(() -> new IllegalArgumentException("Quiz not found."));
            if (!com.exam.service.examops.ExamAccess.canManageQuiz(lecturer, q))
                throw new AccessDeniedException("That quiz is not one of yours.");
        }
        return f.withDepartment(null).withLecturer(id);
    }

    private static boolean inDepartment(Category c, Long dept) {
        return c != null && c.getPrograms() != null && c.getPrograms().stream()
                .anyMatch(p -> p.getDepartment() != null && dept.equals(p.getDepartment().getId()));
    }

    // ── Words ────────────────────────────────────────────────────────────────

    /** The institution's words: level/semester/course/programme/lecturer/student/studentId … */
    public Map<String, String> terms() { return institutionService.terms(); }

    public String semesterName(Object semester) { return institutionService.semesterName(semester); }

    public boolean isSchool() { return institutionService.isSchool(); }

    /** Whether results show each student's position in class. */
    public boolean showPosition() { return institutionService.showPosition(); }

    // ── Scope lines ──────────────────────────────────────────────────────────

    public void sessionScope(ReportResult r, ReportFilters f) {
        r.scope("Session: " + (f.sessionId() == null ? "All sessions" : session(f.sessionId()).getName()));
    }

    public void periodScope(ReportResult r, Period p) { r.scope("Period: " + p.label()); }

    /** Department / programme / level / semester lines for the filters that are set (department always). */
    public void placeScope(ReportResult r, ReportFilters f, Lookups l) {
        Map<String, String> t = terms();
        r.scope("Department: " + (f.departmentId() == null ? "All departments" : Objects.toString(l.deptName(f.departmentId()), "?")));
        if (f.programId() != null) r.scope(t.get("program") + ": " + Objects.toString(l.programName(f.programId()), "?"));
        if (f.level() != null) r.scope(t.get("level") + ": " + f.level());
        if (f.semester() != null) r.scope(t.get("semester") + ": " + semesterName(f.semester()));
    }

    // ── Values ───────────────────────────────────────────────────────────────

    public static String normLevel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        return s.toLowerCase().startsWith("level ") ? s.substring(6).trim() : s;
    }

    public static int levelNumber(String level) {
        try { return Integer.parseInt(normLevel(level)); } catch (Exception e) { return 0; }
    }

    /** Percentage with one decimal, or null when there is nothing to divide by. */
    public static Double pct(double part, double whole) {
        return whole <= 0 ? null : round1(part * 100.0 / whole);
    }

    public static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    public static Double round1OrNull(OptionalDouble v) { return v.isPresent() ? round1(v.getAsDouble()) : null; }

    public static BigDecimal round2(BigDecimal v) { return v == null ? null : v.setScale(2, RoundingMode.HALF_UP); }

    public static String day(LocalDate d) { return d == null ? null : DAY.format(d); }

    public static String day(LocalDateTime t) { return t == null ? null : DAY.format(t); }

    public static String minute(LocalDateTime t) { return t == null ? null : MINUTE.format(t); }

    public static String name(User u) { return u == null ? null : CurrentUserService.displayName(u); }

    public static String yesNo(boolean b) { return b ? "Yes" : "No"; }

    /** Standard deviation (population) of the values, one decimal; null for fewer than two values. */
    public static Double stdDev(List<Double> values) {
        if (values.size() < 2) return null;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / values.size();
        return round1(Math.sqrt(var));
    }
}
