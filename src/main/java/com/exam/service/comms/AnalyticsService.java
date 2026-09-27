package com.exam.service.comms;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only performance analytics for the Super Admin (whole university or one department)
 * and HODs (always their own department).
 * <p>
 * Two data sources:
 *  - quiz results ({@link Report#getPercentage()}): continuous-assessment performance;
 *  - official marks ({@link StudentCourseMark}) on APPROVED / PUBLISHED marks sheets: final grades.
 * A score of {@value #PASS_MARK}% or more counts as a pass.
 */
@Service
public class AnalyticsService {

    public static final double PASS_MARK = 50.0;
    private static final Set<String> OFFICIAL_SHEET_STATUSES = Set.of("APPROVED", "PUBLISHED");
    private static final List<String> GRADE_ORDER = List.of("A+", "A", "B", "C", "D", "F");

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private SemesterSheetRepository semesterSheetRepository;
    @Autowired private StudentCourseMarkRepository studentCourseMarkRepository;
    @Autowired private DepartmentRepository departmentRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Department dept) {
        // ── Scope ────────────────────────────────────────────────────────────
        List<User> students = userRepository.findByRole(Role.NORMAL).stream()
                .filter(u -> dept == null || NotificationService.inDepartment(u, dept)).toList();
        Set<Long> studentIds = students.stream().map(User::getId).collect(Collectors.toSet());

        List<User> lecturers = userRepository.findByRole(Role.LECTURER).stream()
                .filter(u -> dept == null || NotificationService.inDepartment(u, dept)).toList();

        List<Category> courses = categoryRepository.findAll().stream()
                .filter(c -> dept == null || courseInDept(c, dept)).toList();

        List<Quiz> quizzes = quizRepository.findAll().stream()
                .filter(q -> dept == null || (q.getCategory() != null && courseInDept(q.getCategory(), dept))).toList();

        List<Report> reports = reportRepository.findAll().stream()
                .filter(r -> r.getUser() != null && r.getPercentage() != null && studentIds.contains(r.getUser().getId()))
                .toList();

        List<SemesterSheet> sheets = semesterSheetRepository.findAll().stream()
                .filter(s -> dept == null || (s.getProgram() != null && s.getProgram().getDepartment() != null
                        && dept.getId().equals(s.getProgram().getDepartment().getId()))).toList();
        Set<Long> officialSheetIds = sheets.stream()
                .filter(s -> OFFICIAL_SHEET_STATUSES.contains(s.getStatus()))
                .map(SemesterSheet::getId).collect(Collectors.toSet());

        List<StudentCourseMark> officialMarks = studentCourseMarkRepository.findAll().stream()
                .filter(m -> m.getSemesterSheet() != null && officialSheetIds.contains(m.getSemesterSheet().getId())
                        && m.getGrade() != null && !"N/A".equals(m.getGrade()))
                .toList();

        // ── Build ────────────────────────────────────────────────────────────
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", dept == null
                ? Map.of("departmentId", "", "departmentName", "All departments")
                : Map.of("departmentId", dept.getId(), "departmentName", dept.getName()));
        out.put("passMark", PASS_MARK);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("students", students.size());
        totals.put("lecturers", lecturers.size());
        totals.put("courses", courses.size());
        totals.put("quizzes", quizzes.size());
        totals.put("liveQuizzes", quizzes.stream().filter(Quiz::isActive).count());
        totals.put("attempts", reports.size());
        totals.put("averageScore", round(avg(reports)));
        totals.put("passRate", round(passRate(reports)));
        out.put("totals", totals);

        out.put("sheetStatus", countBy(sheets, SemesterSheet::getStatus,
                List.of("DRAFT", "ACTIVE", "SUBMITTED", "APPROVED", "PUBLISHED")));
        out.put("gradeDistribution", countBy(officialMarks, StudentCourseMark::getGrade, GRADE_ORDER));
        out.put("coursePerformance", coursePerformance(reports));
        out.put("programPerformance", programPerformance(reports, students));
        out.put("lecturerPerformance", lecturerPerformance(reports));
        out.put("monthlyTrend", monthlyTrend(reports));
        out.put("atRiskStudents", atRisk(students, reports, officialMarks));
        return out;
    }

    public Optional<Department> department(Long id) {
        return id == null ? Optional.empty() : departmentRepository.findById(id);
    }

    // ── Sections ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> coursePerformance(List<Report> reports) {
        Map<Category, List<Report>> byCourse = reports.stream()
                .filter(r -> r.getQuiz() != null && r.getQuiz().getCategory() != null)
                .collect(Collectors.groupingBy(r -> r.getQuiz().getCategory(), LinkedHashMap::new, Collectors.toList()));
        return byCourse.entrySet().stream().map(e -> {
            Category c = e.getKey();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("courseId", c.getCid());
            m.put("courseCode", c.getCourseCode());
            m.put("title", c.getTitle());
            m.put("lecturer", CurrentUserService.displayName(c.getUser()));
            m.put("attempts", e.getValue().size());
            m.put("averageScore", round(avg(e.getValue())));
            m.put("passRate", round(passRate(e.getValue())));
            return m;
        }).sorted(Comparator.comparing(m -> (Double) m.get("passRate"))).toList();
    }

    private List<Map<String, Object>> programPerformance(List<Report> reports, List<User> students) {
        Map<Long, Program> programs = new LinkedHashMap<>();
        Map<Long, Long> headcount = new HashMap<>();
        students.forEach(s -> {
            if (s.getProgram() != null) {
                programs.putIfAbsent(s.getProgram().getId(), s.getProgram());
                headcount.merge(s.getProgram().getId(), 1L, Long::sum);
            }
        });
        Map<Long, List<Report>> byProgram = reports.stream()
                .filter(r -> r.getUser().getProgram() != null)
                .collect(Collectors.groupingBy(r -> r.getUser().getProgram().getId()));
        return programs.values().stream().map(p -> {
            List<Report> rs = byProgram.getOrDefault(p.getId(), List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("programId", p.getId());
            m.put("name", p.getName());
            m.put("code", p.getCode());
            m.put("students", headcount.getOrDefault(p.getId(), 0L));
            m.put("attempts", rs.size());
            m.put("averageScore", round(avg(rs)));
            m.put("passRate", round(passRate(rs)));
            return m;
        }).sorted(Comparator.comparing(m -> String.valueOf(m.get("name")))).toList();
    }

    private List<Map<String, Object>> lecturerPerformance(List<Report> reports) {
        Map<Long, User> lecturers = new HashMap<>();
        Map<Long, List<Report>> byLecturer = new HashMap<>();
        Map<Long, Set<Long>> coursesByLecturer = new HashMap<>();
        for (Report r : reports) {
            Quiz q = r.getQuiz();
            if (q == null) continue;
            User lec = q.getCategory() != null && q.getCategory().getUser() != null ? q.getCategory().getUser() : q.getUser();
            if (lec == null || lec.getRole() != Role.LECTURER) continue;
            lecturers.putIfAbsent(lec.getId(), lec);
            byLecturer.computeIfAbsent(lec.getId(), k -> new ArrayList<>()).add(r);
            if (q.getCategory() != null)
                coursesByLecturer.computeIfAbsent(lec.getId(), k -> new HashSet<>()).add(q.getCategory().getCid());
        }
        return lecturers.values().stream().map(l -> {
            List<Report> rs = byLecturer.get(l.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lecturerId", l.getId());
            m.put("name", CurrentUserService.displayName(l));
            m.put("courses", coursesByLecturer.getOrDefault(l.getId(), Set.of()).size());
            m.put("attempts", rs.size());
            m.put("averageScore", round(avg(rs)));
            m.put("passRate", round(passRate(rs)));
            return m;
        }).sorted(Comparator.comparing(m -> (Double) m.get("passRate"))).toList();
    }

    /** Last 6 calendar months, oldest first (months with no attempts are included as zeros). */
    private List<Map<String, Object>> monthlyTrend(List<Report> reports) {
        DateTimeFormatter label = DateTimeFormatter.ofPattern("MMM yyyy");
        YearMonth now = YearMonth.now();
        Map<YearMonth, List<Report>> byMonth = reports.stream()
                .filter(r -> r.getSubmissionDate() != null)
                .collect(Collectors.groupingBy(r -> YearMonth.from(r.getSubmissionDate())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = now.minusMonths(i);
            List<Report> rs = byMonth.getOrDefault(ym, List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", ym.format(label));
            m.put("attempts", rs.size());
            m.put("averageScore", rs.isEmpty() ? null : round(avg(rs)));
            m.put("passRate", rs.isEmpty() ? null : round(passRate(rs)));
            out.add(m);
        }
        return out;
    }

    /**
     * At risk: 2+ failed courses on official marks sheets, or a quiz average below the pass
     * mark across at least 3 attempts. Worst first, at most 50.
     */
    private List<Map<String, Object>> atRisk(List<User> students, List<Report> reports, List<StudentCourseMark> marks) {
        Map<Long, List<Report>> reportsByStudent = reports.stream()
                .collect(Collectors.groupingBy(r -> r.getUser().getId()));
        Map<Long, Long> failsByStudent = marks.stream()
                .filter(m -> m.getStudent() != null && "F".equals(m.getGrade()))
                .collect(Collectors.groupingBy(m -> m.getStudent().getId(), Collectors.counting()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (User s : students) {
            List<Report> rs = reportsByStudent.getOrDefault(s.getId(), List.of());
            long fails = failsByStudent.getOrDefault(s.getId(), 0L);
            double average = avg(rs);
            List<String> reasons = new ArrayList<>();
            if (fails >= 2) reasons.add(fails + " failed courses");
            if (rs.size() >= 3 && average < PASS_MARK) reasons.add("Quiz average " + round(average) + "%");
            if (reasons.isEmpty()) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", s.getId());
            m.put("name", CurrentUserService.displayName(s));
            m.put("program", s.getProgram() != null ? s.getProgram().getName() : null);
            m.put("level", s.getCurrentLevel());
            m.put("failedCourses", fails);
            m.put("attempts", rs.size());
            m.put("averageScore", rs.isEmpty() ? null : round(average));
            m.put("reasons", reasons);
            out.add(m);
        }
        out.sort(Comparator.<Map<String, Object>, Long>comparing(m -> (Long) m.get("failedCourses")).reversed()
                .thenComparing(m -> m.get("averageScore") == null ? 100.0 : (Double) m.get("averageScore")));
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean courseInDept(Category c, Department dept) {
        return c.getPrograms() != null && c.getPrograms().stream()
                .anyMatch(p -> p.getDepartment() != null && dept.getId().equals(p.getDepartment().getId()));
    }

    private static double avg(List<Report> rs) {
        return rs.stream().mapToDouble(Report::getPercentage).average().orElse(0);
    }

    private static double passRate(List<Report> rs) {
        if (rs.isEmpty()) return 0;
        return 100.0 * rs.stream().filter(r -> r.getPercentage() >= PASS_MARK).count() / rs.size();
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }

    /** Counts by key, in the given order first, then any unexpected keys. */
    private static <T> List<Map<String, Object>> countBy(List<T> items, Function<T, String> key, List<String> order) {
        Map<String, Long> counts = items.stream()
                .collect(Collectors.groupingBy(i -> Objects.toString(key.apply(i), "UNKNOWN"), Collectors.counting()));
        List<String> keys = new ArrayList<>(order);
        counts.keySet().stream().filter(k -> !keys.contains(k)).sorted().forEach(keys::add);
        return keys.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", k);
            m.put("count", counts.getOrDefault(k, 0L));
            return m;
        }).toList();
    }
}
