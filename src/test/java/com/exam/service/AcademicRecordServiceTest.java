package com.exam.service;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Category;
import com.exam.model.exam.SemesterSheet;
import com.exam.model.exam.StudentCourseMark;
import com.exam.repository.DegreeClassRepository;
import com.exam.repository.GradeBandRepository;
import com.exam.repository.StudentCourseMarkRepository;
import com.exam.repository.UserRepository;
import com.exam.service.academic.AcademicRecordService;
import com.exam.service.academic.GradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** GPA / CGPA, carry-overs and promotion rules, on the default (legacy) grading scale. */
class AcademicRecordServiceTest {

    private StudentCourseMarkRepository markRepository;
    private SystemSettingService settings;
    private GradingService grading;
    private AcademicRecordService records;
    private final List<StudentCourseMark> marks = new ArrayList<>();
    private User student;
    private long ids = 1;

    @BeforeEach
    void setUp() {
        markRepository = mock(StudentCourseMarkRepository.class);
        settings = mock(SystemSettingService.class);
        GradeBandRepository bands = mock(GradeBandRepository.class);
        DegreeClassRepository classes = mock(DegreeClassRepository.class);
        when(bands.findAllByOrderByMinScoreDesc()).thenReturn(List.of());      // → legacy scale
        when(classes.findAllByOrderByMinCgpaDesc()).thenReturn(List.of());

        grading = new GradingService();
        ReflectionTestUtils.setField(grading, "bandRepository", bands);
        ReflectionTestUtils.setField(grading, "classRepository", classes);
        ReflectionTestUtils.setField(grading, "studentCourseMarkRepository", markRepository);
        ReflectionTestUtils.setField(grading, "systemSettingService", settings);

        student = new User();
        student.setId(7L);
        student.setRole(Role.NORMAL);
        student.setFirstname("Ama");
        student.setLastname("Mensah");
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(student));

        records = new AcademicRecordService();
        ReflectionTestUtils.setField(records, "markRepository", markRepository);
        ReflectionTestUtils.setField(records, "userRepository", users);
        ReflectionTestUtils.setField(records, "gradingService", grading);
        when(markRepository.findByStudent_Id(7L)).thenReturn(marks);
    }

    private Category course(long id, String code, Integer credits) {
        Category c = new Category();
        c.setCid(id);
        c.setCourseCode(code);
        c.setTitle(code);
        c.setCreditUnits(credits);
        return c;
    }

    private void mark(Category c, String session, int startYear, int level, int sem, double score, String status) {
        AcademicSession s = new AcademicSession();
        s.setName(session);
        s.setStartDate(LocalDate.of(startYear, 8, 1));
        SemesterSheet sheet = new SemesterSheet();
        ReflectionTestUtils.setField(sheet, "id", ids++);
        sheet.setSession(s);
        sheet.setLevel(String.valueOf(level));
        sheet.setSemester(sem);
        sheet.setStatus(status);
        StudentCourseMark m = new StudentCourseMark();
        m.setStudent(student);
        m.setCourse(c);
        m.setSemesterSheet(sheet);
        grading.applyGrade(m, BigDecimal.valueOf(score));
        m.setTotalScore(BigDecimal.valueOf(score));
        marks.add(m);
    }

    @Test
    void legacyScaleKeepsTheOldLetters() {
        assertThat(grading.gradeFor(BigDecimal.valueOf(90)).getLetter()).isEqualTo("A+");
        assertThat(grading.gradeFor(BigDecimal.valueOf(89.99)).getLetter()).isEqualTo("A");
        assertThat(grading.gradeFor(BigDecimal.valueOf(50)).getLetter()).isEqualTo("D");
        assertThat(grading.gradeFor(BigDecimal.valueOf(49.5)).getLetter()).isEqualTo("F");
        assertThat(grading.gradeFor(null).getLetter()).isEqualTo("F");
    }

    @Test
    void gpaIsWeightedByCreditUnits() {
        mark(course(1, "CS101", 4), "2025/2026", 2025, 100, 1, 85, "PUBLISHED");  // A = 4.0 × 4
        mark(course(2, "CS102", 2), "2025/2026", 2025, 100, 1, 65, "PUBLISHED");  // C = 2.0 × 2

        Map<String, Object> t = records.transcript(7L, false);

        assertThat(t.get("cgpa")).isEqualTo(new BigDecimal("3.33"));             // (16 + 4) / 6
        assertThat(t.get("totalCreditUnits")).isEqualTo(6);
    }

    @Test
    void missingCreditUnitsUseTheDefault() {
        when(settings.getSetting(GradingService.DEFAULT_CREDIT_UNITS)).thenReturn("3");
        mark(course(1, "CS101", null), "2025/2026", 2025, 100, 1, 85, "PUBLISHED");

        Map<String, Object> t = records.transcript(7L, false);

        assertThat(t.get("totalCreditUnits")).isEqualTo(3);
    }

    @Test
    void studentsOnlySeePublishedResults() {
        mark(course(1, "CS101", 3), "2025/2026", 2025, 100, 1, 85, "PUBLISHED");
        mark(course(2, "CS102", 3), "2025/2026", 2025, 100, 1, 40, "APPROVED");

        assertThat(records.transcript(7L, false).get("totalCreditUnits")).isEqualTo(3);
        assertThat(records.transcript(7L, true).get("totalCreditUnits")).isEqualTo(6);
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedCourseIsCarriedOverUntilPassed() {
        Category cs201 = course(3, "CS201", 3);
        mark(cs201, "2025/2026", 2025, 200, 1, 35, "PUBLISHED");
        assertThat((List<?>) records.transcript(7L, false).get("outstanding")).hasSize(1);

        mark(cs201, "2026/2027", 2026, 300, 1, 72, "PUBLISHED");                 // resit, passed
        Map<String, Object> t = records.transcript(7L, false);

        assertThat((List<?>) t.get("outstanding")).isEmpty();
        assertThat(t.get("cgpa")).isEqualTo(new BigDecimal("1.50"));             // both attempts count: (0 + 3.0) / 2
        List<Map<String, Object>> sems = (List<Map<String, Object>>) t.get("semesters");
        Map<String, Object> resit = ((List<Map<String, Object>>) sems.get(1).get("courses")).get(0);
        assertThat(resit.get("attempt")).isEqualTo(2);
    }

    @Test
    void promotionRulesHoldBackStudentsWithTooManyCarryovers() {
        when(settings.getSetting(GradingService.PROMOTION_MAX_CARRYOVERS)).thenReturn("1");
        mark(course(1, "CS101", 3), "2025/2026", 2025, 100, 1, 30, "APPROVED");
        mark(course(2, "CS102", 3), "2025/2026", 2025, 100, 1, 20, "APPROVED");

        Map<String, Object> e = records.eligibility(student);

        assertThat(e.get("eligible")).isEqualTo(false);
        assertThat((List<?>) e.get("reasons")).hasSize(1);
    }

    @Test
    void noRulesMeansEveryoneIsEligible() {
        mark(course(1, "CS101", 3), "2025/2026", 2025, 100, 1, 10, "APPROVED");
        assertThat(records.eligibility(student).get("eligible")).isEqualTo(true);
    }

    @Test
    void scaleValidationRejectsGapsAndDuplicates() {
        GradeBandRepository bands = mock(GradeBandRepository.class);
        ReflectionTestUtils.setField(grading, "bandRepository", bands);
        var noZero = List.of(new GradingService.BandRow("A", BigDecimal.valueOf(50), BigDecimal.ONE, null, true),
                new GradingService.BandRow("F", BigDecimal.valueOf(10), BigDecimal.ZERO, null, false));
        assertThatThrownBy(() -> grading.update(noZero, null, null, null, null)).hasMessageContaining("start at 0");

        var dup = List.of(new GradingService.BandRow("A", BigDecimal.valueOf(50), BigDecimal.ONE, null, true),
                new GradingService.BandRow("a", BigDecimal.ZERO, BigDecimal.ZERO, null, false));
        assertThatThrownBy(() -> grading.update(dup, null, null, null, null)).hasMessageContaining("twice");
        verify(bands, never()).saveAll(any());
    }
}
