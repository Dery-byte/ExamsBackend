package com.exam.service.academic;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.academic.GradeBand;
import com.exam.model.exam.Category;
import com.exam.model.exam.SemesterSheet;
import com.exam.model.exam.StudentCourseMark;
import com.exam.repository.StudentCourseMarkRepository;
import com.exam.repository.UserRepository;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a student's academic record from official marks: semester GPA, CGPA, class of degree,
 * outstanding carry-overs and promotion eligibility.
 * <p>
 * Rules:
 *  - GPA = Σ(grade point × credit units) ÷ Σ credit units, rounded to 2 decimals.
 *  - Every attempt counts towards CGPA (a failed course retaken later appears twice).
 *  - A course is an outstanding carry-over while the student's latest attempt at it is a failing grade.
 *  - Students only ever see PUBLISHED results; staff also see APPROVED ones.
 */
@Service
public class AcademicRecordService {

    public static final Set<String> STUDENT_VISIBLE = Set.of("PUBLISHED");
    public static final Set<String> STAFF_VISIBLE   = Set.of("APPROVED", "PUBLISHED");

    @Autowired private StudentCourseMarkRepository markRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GradingService gradingService;

    /** One graded course attempt, normalised for calculations. */
    public record Attempt(StudentCourseMark mark, SemesterSheet sheet, Category course, String sessionName,
                          LocalDate sessionStart, String level, int semester, int credits, BigDecimal score,
                          String grade, BigDecimal gradePoint, boolean passed) {}

    // ── Core ─────────────────────────────────────────────────────────────────

    /** Graded attempts on sheets with one of the given statuses, oldest first. */
    @Transactional(readOnly = true)
    public List<Attempt> attempts(Long studentId, Set<String> statuses) {
        int defaultCredits = gradingService.defaultCreditUnits();
        return markRepository.findByStudent_Id(studentId).stream()
                .filter(m -> m.getSemesterSheet() != null && statuses.contains(m.getSemesterSheet().getStatus()))
                .filter(m -> m.getGrade() != null && !"N/A".equals(m.getGrade()) && m.getCourse() != null)
                .map(m -> toAttempt(m, defaultCredits))
                .sorted(ATTEMPT_ORDER)
                .toList();
    }

    private static final Comparator<Attempt> ATTEMPT_ORDER = Comparator
            .comparing((Attempt a) -> a.sessionStart() == null ? LocalDate.MIN : a.sessionStart())
            .thenComparing(a -> levelNumber(a.level()))
            .thenComparingInt(Attempt::semester)
            .thenComparing(a -> a.sheet().getId());

    private Attempt toAttempt(StudentCourseMark m, int defaultCredits) {
        SemesterSheet sheet = m.getSemesterSheet();
        AcademicSession session = sheet.getSession();
        Category c = m.getCourse();
        int credits = c.getCreditUnits() != null ? c.getCreditUnits() : defaultCredits;
        Optional<GradeBand> band = gradingService.bandForLetter(m.getGrade());
        BigDecimal gp = m.getGradePoint() != null ? m.getGradePoint()
                : band.map(GradeBand::getGradePoint).orElseGet(() -> gradingService.gradeFor(m.getTotalScore()).getGradePoint());
        boolean passed = band.map(GradeBand::isPassing).orElse(!"F".equalsIgnoreCase(m.getGrade()));
        return new Attempt(m, sheet, c, session != null ? session.getName() : null,
                session != null ? session.getStartDate() : null, normLevel(sheet.getLevel()),
                sheet.getSemester() == null ? 0 : sheet.getSemester(), credits, m.getTotalScore(),
                m.getGrade(), gp, passed);
    }

    /** Courses whose latest attempt was a fail. */
    public List<Attempt> outstanding(List<Attempt> attempts) {
        Map<Long, Attempt> latest = new LinkedHashMap<>();
        for (Attempt a : attempts) latest.put(a.course().getCid(), a);   // attempts are oldest-first
        return latest.values().stream().filter(a -> !a.passed()).toList();
    }

    public static BigDecimal gpa(Collection<Attempt> attempts) {
        int credits = attempts.stream().mapToInt(Attempt::credits).sum();
        if (credits == 0) return null;
        return points(attempts).divide(BigDecimal.valueOf(credits), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal points(Collection<Attempt> attempts) {
        return attempts.stream().map(a -> a.gradePoint().multiply(BigDecimal.valueOf(a.credits())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── Transcript ───────────────────────────────────────────────────────────

    /** Full record grouped by session → level → semester, with running CGPA. */
    @Transactional(readOnly = true)
    public Map<String, Object> transcript(Long studentId, boolean staffView) {
        User s = userRepository.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        if (s.getRole() != Role.NORMAL) throw new IllegalArgumentException("That user is not a student.");
        List<Attempt> attempts = attempts(studentId, staffView ? STAFF_VISIBLE : STUDENT_VISIBLE);

        // Group (order preserved: attempts are chronological)
        Map<String, List<Attempt>> groups = new LinkedHashMap<>();
        for (Attempt a : attempts) {
            String key = a.sessionName() + "|" + a.level() + "|" + a.semester();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }

        Set<Long> retaken = new HashSet<>();
        Map<Long, Integer> seen = new HashMap<>();
        List<Map<String, Object>> semesters = new ArrayList<>();
        List<Attempt> soFar = new ArrayList<>();
        for (List<Attempt> group : groups.values()) {
            soFar.addAll(group);
            Attempt first = group.get(0);
            Map<String, Object> sem = new LinkedHashMap<>();
            sem.put("session", first.sessionName());
            sem.put("level", first.level());
            sem.put("semester", first.semester());
            sem.put("courses", group.stream().map(a -> {
                int attemptNo = seen.merge(a.course().getCid(), 1, Integer::sum);
                if (attemptNo > 1) retaken.add(a.course().getCid());
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("courseId", a.course().getCid());
                c.put("courseCode", a.course().getCourseCode());
                c.put("courseTitle", a.course().getTitle());
                c.put("creditUnits", a.credits());
                c.put("score", a.score());
                c.put("grade", a.grade());
                c.put("gradePoint", a.gradePoint());
                c.put("passed", a.passed());
                c.put("attempt", attemptNo);
                return c;
            }).toList());
            sem.put("creditUnits", group.stream().mapToInt(Attempt::credits).sum());
            sem.put("creditPoints", points(group));
            sem.put("gpa", gpa(group));
            sem.put("cumulativeCreditUnits", soFar.stream().mapToInt(Attempt::credits).sum());
            sem.put("cgpa", gpa(soFar));
            semesters.add(sem);
        }

        BigDecimal cgpa = gpa(attempts);
        List<Attempt> owing = outstanding(attempts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", s.getId());
        out.put("studentName", CurrentUserService.displayName(s));
        out.put("username", s.getUsername());
        out.put("program", s.getProgram() != null ? s.getProgram().getName() : null);
        out.put("department", s.getProgram() != null && s.getProgram().getDepartment() != null
                ? s.getProgram().getDepartment().getName()
                : (s.getDepartment() != null ? s.getDepartment().getName() : null));
        out.put("currentLevel", s.getCurrentLevel());
        out.put("currentSemester", s.getCurrentSemester());
        out.put("semesters", semesters);
        out.put("totalCreditUnits", attempts.stream().mapToInt(Attempt::credits).sum());
        out.put("creditsEarned", attempts.stream().filter(Attempt::passed).mapToInt(Attempt::credits).sum());
        out.put("cgpa", cgpa);
        out.put("maxGradePoint", gradingService.maxGradePoint());
        out.put("degreeClass", gradingService.classFor(cgpa));
        out.put("outstanding", owing.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("courseId", a.course().getCid());
            m.put("courseCode", a.course().getCourseCode());
            m.put("courseTitle", a.course().getTitle());
            m.put("creditUnits", a.credits());
            m.put("lastGrade", a.grade());
            m.put("lastSession", a.sessionName());
            return m;
        }).toList());
        out.put("retakenCourses", retaken.size());
        out.put("includesApproved", staffView);
        out.put("generatedDate", LocalDate.now());
        return out;
    }

    // ── Promotion ────────────────────────────────────────────────────────────

    /** Whether the student meets the Super Admin's promotion rules (uses approved + published results). */
    @Transactional(readOnly = true)
    public Map<String, Object> eligibility(User student) {
        List<Attempt> attempts = attempts(student.getId(), STAFF_VISIBLE);
        int owing = outstanding(attempts).size();
        BigDecimal cgpa = gpa(attempts);
        int maxCarry = gradingService.maxCarryoversForPromotion();
        BigDecimal minCgpa = gradingService.minCgpaForPromotion();

        List<String> reasons = new ArrayList<>();
        if (maxCarry >= 0 && owing > maxCarry)
            reasons.add(owing + " outstanding carry-over" + (owing == 1 ? "" : "s") + " (limit " + maxCarry + ")");
        if (minCgpa.signum() > 0 && cgpa != null && cgpa.compareTo(minCgpa) < 0)
            reasons.add("CGPA " + cgpa + " is below the minimum " + minCgpa.stripTrailingZeros().toPlainString());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentId", student.getId());
        m.put("name", CurrentUserService.displayName(student));
        m.put("eligible", reasons.isEmpty());
        m.put("reasons", reasons);
        m.put("cgpa", cgpa);
        m.put("outstandingCarryovers", owing);
        return m;
    }

    /** Students (any program or level) whose latest attempt at this course is a fail. */
    @Transactional(readOnly = true)
    public List<User> studentsOwing(Long courseId) {
        Map<Long, List<StudentCourseMark>> byStudent = markRepository.findByCourse_Cid(courseId).stream()
                .filter(m -> m.getStudent() != null && m.getSemesterSheet() != null
                        && STAFF_VISIBLE.contains(m.getSemesterSheet().getStatus())
                        && m.getGrade() != null && !"N/A".equals(m.getGrade()))
                .collect(Collectors.groupingBy(m -> m.getStudent().getId()));
        int defaultCredits = gradingService.defaultCreditUnits();
        List<User> out = new ArrayList<>();
        for (List<StudentCourseMark> marks : byStudent.values()) {
            List<Attempt> attempts = marks.stream().map(m -> toAttempt(m, defaultCredits)).sorted(ATTEMPT_ORDER).toList();
            User st = marks.get(0).getStudent();
            if (st.isEnabled() && !attempts.get(attempts.size() - 1).passed()) out.add(st);
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static String normLevel(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.toLowerCase().startsWith("level ") ? s.substring(6).trim() : s;
    }

    private static int levelNumber(String level) {
        try { return Integer.parseInt(level); } catch (Exception e) { return 0; }
    }
}
