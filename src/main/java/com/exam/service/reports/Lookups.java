package com.exam.service.reports;

import com.exam.model.User;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.exam.Quiz;
import com.exam.service.reports.ReportQueries.StudentRow;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Departments, programmes, courses and quizzes, loaded once per report, and the rules for whether
 * a course, quiz or student falls inside the chosen filters.
 * <p>
 * A course belongs to every department one of its programmes is in, so a course shared by two
 * departments counts in both. A student belongs to their programme's department, or to their own
 * department when they have no programme.
 */
public class Lookups {

    final Map<Long, Department> departments;
    final Map<Long, Program> programs;
    final Map<Long, Category> courses;
    final Map<Long, Quiz> quizzes;

    Lookups(List<Department> departments, List<Program> programs, List<Category> courses, List<Quiz> quizzes) {
        this.departments = index(departments, Department::getId);
        this.programs = index(programs, Program::getId);
        this.courses = index(courses, Category::getCid);
        this.quizzes = index(quizzes, Quiz::getqId);
    }

    private static <T> Map<Long, T> index(List<T> items, java.util.function.Function<T, Long> id) {
        Map<Long, T> m = new LinkedHashMap<>();
        for (T t : items) if (id.apply(t) != null) m.put(id.apply(t), t);
        return m;
    }

    // ── Names ────────────────────────────────────────────────────────────────

    public String deptName(Long id) {
        Department d = id == null ? null : departments.get(id);
        return d == null ? null : d.getName();
    }

    public String programName(Long id) {
        Program p = id == null ? null : programs.get(id);
        return p == null ? null : p.getName();
    }

    public List<Department> sortedDepartments() {
        return departments.values().stream()
                .sorted(Comparator.comparing(d -> Objects.toString(d.getName(), ""), String.CASE_INSENSITIVE_ORDER)).toList();
    }

    // ── Programmes and courses ───────────────────────────────────────────────

    public Long deptOfProgram(Long programId) {
        Program p = programId == null ? null : programs.get(programId);
        return p == null || p.getDepartment() == null ? null : p.getDepartment().getId();
    }

    public Set<Long> courseProgramIds(Category c) {
        if (c == null || c.getPrograms() == null) return Set.of();
        return c.getPrograms().stream().map(Program::getId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public Set<Long> courseDepts(Category c) {
        if (c == null || c.getPrograms() == null) return Set.of();
        return c.getPrograms().stream().filter(p -> p.getDepartment() != null)
                .map(p -> p.getDepartment().getId()).collect(Collectors.toCollection(TreeSet::new));
    }

    public String courseDeptNames(Category c) {
        return courseDepts(c).stream().map(this::deptName).filter(Objects::nonNull).sorted().collect(Collectors.joining(", "));
    }

    public String courseProgramNames(Category c) {
        if (c == null || c.getPrograms() == null) return "";
        return c.getPrograms().stream().map(Program::getName).filter(Objects::nonNull).sorted().collect(Collectors.joining(", "));
    }

    public Category course(Long id) { return id == null ? null : courses.get(id); }

    public Quiz quiz(Long id) { return id == null ? null : quizzes.get(id); }

    public Category courseOfQuiz(Long quizId) {
        Quiz q = quiz(quizId);
        return q == null ? null : q.getCategory();
    }

    /** The course's lecturer, else whoever created the quiz. */
    public static User lecturerOf(Quiz q) {
        if (q == null) return null;
        Category c = q.getCategory();
        return c != null && c.getUser() != null ? c.getUser() : q.getUser();
    }

    /** Course matches department, programme, level and semester filters (those that are set). */
    public boolean courseMatches(Category c, ReportFilters f) {
        if (c == null) return f.departmentId() == null && f.programId() == null && f.level() == null && f.semester() == null
                && f.lecturerId() == null;
        if (!taughtBy(c, f)) return false;
        if (f.departmentId() != null && !courseDepts(c).contains(f.departmentId())) return false;
        if (f.programId() != null && !courseProgramIds(c).contains(f.programId())) return false;
        if (f.level() != null && !String.valueOf(f.level()).equals(ReportSupport.normLevel(c.getLevel()))) return false;
        return f.semester() == null || f.semester().equals(c.getSemester());
    }

    /**
     * Quiz matches the filters. Under a lecturer lock it must be one they manage: they set it, or
     * it is on a course they teach (same rule as quiz access).
     */
    public boolean quizMatches(Long quizId, ReportFilters f) {
        Quiz q = quiz(quizId);
        if (q == null) return false;
        if (f.lecturerId() == null) return courseMatches(q.getCategory(), f);
        boolean manages = (q.getUser() != null && f.lecturerId().equals(q.getUser().getId())) || taughtBy(q.getCategory(), f);
        return manages && courseMatches(q.getCategory(), f.withLecturer(null));
    }

    /** No lecturer lock, or the course is taught by that lecturer. */
    public boolean taughtBy(Category c, ReportFilters f) {
        return f.lecturerId() == null || (c != null && c.getUser() != null && f.lecturerId().equals(c.getUser().getId()));
    }

    /** Ids of the courses inside the lecturer lock (every course when there is none). */
    public Set<Long> taughtCourseIds(ReportFilters f) {
        return courses.values().stream().filter(c -> taughtBy(c, f)).map(Category::getCid).collect(Collectors.toSet());
    }

    // ── Students ─────────────────────────────────────────────────────────────

    public Long studentDept(StudentRow s) {
        Long d = deptOfProgram(s.programId());
        return d != null ? d : s.departmentId();
    }

    /** Student matches department, programme and level filters (those that are set). */
    public boolean studentMatches(StudentRow s, ReportFilters f) {
        if (f.departmentId() != null && !f.departmentId().equals(studentDept(s))) return false;
        if (f.programId() != null && !f.programId().equals(s.programId())) return false;
        return f.level() == null || f.level().equals(s.level());
    }

    /** Sheet matches department, programme, level and semester filters (those that are set). */
    public boolean sheetMatches(Long programId, String level, Integer semester, ReportFilters f) {
        if (f.departmentId() != null && !f.departmentId().equals(deptOfProgram(programId))) return false;
        if (f.programId() != null && !f.programId().equals(programId)) return false;
        if (f.level() != null && !String.valueOf(f.level()).equals(ReportSupport.normLevel(level))) return false;
        return f.semester() == null || f.semester().equals(semester);
    }
}
