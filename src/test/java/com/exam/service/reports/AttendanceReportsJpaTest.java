package com.exam.service.reports;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import com.exam.model.exam.Registered_courses;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicRecordService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.academic.GradingService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.attendance.AttendanceMinimum;
import com.exam.service.attendance.AttendancePolicyService;
import com.exam.service.attendance.AttendanceService;
import com.exam.service.examops.TimetableService;
import com.exam.service.features.FeatureService;
import com.exam.service.fees.FeeScheduleService;
import com.exam.service.fees.ResultsHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The attendance reports on H2: the register's codes and percentages, the summary and low-attendance
 * lists with the quiz minimum, the meetings list, each role's lock, and the PDFs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:attreports;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({ReportQueries.class, ReportSupport.class, Standings.class, AcademicReports.class, ResultsReports.class, TeachingReports.class,
        QuestionPapers.class, QuestionPaperPdf.class, com.exam.service.QuestionImageService.class, ExamReports.class,
        FinanceReports.class, OversightReports.class, ReportCatalog.class, ReportPdfService.class,
        AcademicRecordService.class, GradingService.class, AcademicSessionService.class, InstitutionService.class,
        SystemSettingService.class, TimetableService.class, FeeScheduleService.class, ResultsHoldService.class,
        com.exam.service.academic.ThemeService.class, com.exam.service.admin.MaintenanceService.class,
        AttendanceReports.class, AttendanceMinimum.class, AttendancePolicyService.class, AttendanceService.class, FeatureService.class,
        com.exam.service.comms.CurrentUserService.class, ReportsJpaTest.Templates.class})
class AttendanceReportsJpaTest {

    @org.springframework.boot.test.mock.mockito.MockBean com.exam.service.fees.PaystackClient paystack;

    @Autowired ReportCatalog catalog;
    @Autowired ReportPdfService pdf;
    @Autowired AttendanceService attendance;
    @Autowired AttendancePolicyService policies;
    @Autowired SystemSettingService settings;
    @Autowired AcademicSessionService academicSessions;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired CategoryRepository categories;
    @Autowired Registered_coursesRepository registrations;

    static final double LAT = 5.6037, LNG = -0.1870;
    Department cmp, maths;
    Program cs;
    Category course;
    User sa, hod, otherHod, lecturer, otherLecturer, ama, kofi, esi;

    @BeforeEach
    void setUp() {
        settings.updateSetting("FEATURE_ATTENDANCE", "true");
        cmp = dept("Computing", "CMP");
        maths = dept("Mathematics", "MTH");
        cs = new Program();
        cs.setName("Computer Science");
        cs.setCode("CS");
        cs.setDurationYears(4);
        cs.setDepartment(cmp);
        cs.setEnabled(true);
        cs = programs.save(cs);

        sa = user("sa1", Role.SUPER_ADMIN, null);
        hod = user("hod1", Role.ADMIN, cmp);
        otherHod = user("hod2", Role.ADMIN, maths);
        lecturer = user("lect1", Role.LECTURER, cmp);
        otherLecturer = user("lect2", Role.LECTURER, cmp);
        ama = user("10001", Role.NORMAL, null);
        kofi = user("10002", Role.NORMAL, null);
        esi = user("10003", Role.NORMAL, null);

        course = new Category();
        course.setTitle("Data Structures");
        course.setCourseCode("CSC 201");
        course.setLevel("200");
        course.setPrograms(new HashSet<>(Set.of(cs)));
        course.setUser(lecturer);
        course = categories.save(course);
        AcademicSession current = academicSessions.current();
        for (User s : List.of(ama, kofi, esi)) {
            Registered_courses r = new Registered_courses(null, new Date(), course, s);
            r.setSession(current);
            registrations.save(r);
        }

        // Three meetings: Ama at all three, Kofi at the first, Esi excused from the last and absent from the others
        Long[] ids = new Long[3];
        for (int i = 0; i < 3; i++) {
            Map<String, Object> s = attendance.open(lecturer, new AttendanceService.OpenRequest("COURSE", course.getCid(), null, null, null,
                    LAT, LNG, 8.0, null, 60, "Week " + (i + 1), null, false));
            Long id = (Long) s.get("id");
            ids[i] = id;
            String code = (String) attendance.codeView(lecturer, id).get("code");
            assertThat(attendance.checkIn(ama, new AttendanceService.CheckInRequest(id, code, LAT, LNG, 5.0, "ama-phone-0000000001", null), null).ok()).isTrue();
            if (i == 0) assertThat(attendance.checkIn(kofi, new AttendanceService.CheckInRequest(id, code, LAT, LNG, 5.0, "kofi-phone-000000002", null), null).ok()).isTrue();
            attendance.end(lecturer, id);
        }
        attendance.mark(lecturer, ids[2], esi.getId(), "EXCUSED", "Sports");
        policies.saveSystem(sa, new AttendancePolicyService.PolicyRequest(null, null, null, null, null, null, null, null, null, null, null, true, 75));
    }

    private Department dept(String name, String code) {
        Department d = new Department();
        d.setName(name);
        d.setCode(code);
        return departments.save(d);
    }

    private User user(String username, Role role, Department d) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFirstname(username);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        if (role == Role.NORMAL) {
            u.setProgram(cs);
            u.setCurrentLevel(200);
        } else {
            u.setDepartment(d);
        }
        return users.save(u);
    }

    private static ReportFilters course(Long courseId, String status) {
        return new ReportFilters(null, null, null, null, null, null, null, courseId, null, status);
    }

    private static Map<String, Object> rowOf(ReportResult r, String table, String studentId) {
        return r.getTables().stream().filter(t -> t.getId().equals(table)).findFirst().orElseThrow().getRows().stream()
                .filter(x -> studentId.equals(x.get("studentId"))).findFirst().orElseThrow();
    }

    @Test
    void theRegisterShowsEveryMeetingAndPercentage() throws Exception {
        ReportResult r = catalog.run("attendance-register", course(course.getCid(), null), lecturer);
        Map<String, Object> amaRow = rowOf(r, "register", "10001");
        Map<String, Object> kofiRow = rowOf(r, "register", "10002");
        Map<String, Object> esiRow = rowOf(r, "register", "10003");
        assertThat(amaRow.values()).containsSubsequence("P", "P", "P");
        assertThat(amaRow.get("percent")).isEqualTo(100.0);
        assertThat(kofiRow.get("percent")).isEqualTo(33.3);
        assertThat(kofiRow.get("meets")).isEqualTo("No");
        assertThat(esiRow.values()).containsSubsequence("A", "A", "E");
        assertThat(esiRow.get("counted")).isEqualTo(2);
        assertThat(esiRow.get("percent")).isEqualTo(0.0);
        assertThat(new String(pdf.render(r), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        ReportResult none = catalog.run("attendance-register", ReportFilters.none(), lecturer);
        assertThat(none.getTables().get(0).getEmptyText()).contains("Choose a course");
    }

    @Test
    void summaryLowAttendanceAndMeetings() throws Exception {
        ReportResult summary = catalog.run("attendance-summary", ReportFilters.none(), sa);
        assertThat(rowOf(summary, "students", "10002").get("meets")).isEqualTo("No (needs 75%)");
        assertThat(rowOf(summary, "students", "10001").get("meets")).isEqualTo("Yes");

        ReportResult low = catalog.run("attendance-low", ReportFilters.none(), hod);
        List<Map<String, Object>> rows = low.getTables().get(0).getRows();
        assertThat(rows).extracting(x -> x.get("studentId")).containsExactlyInAnyOrder("10002", "10003");
        assertThat(rowOf(low, "students", "10002").get("blocked")).isEqualTo("Yes");
        ReportResult under50 = catalog.run("attendance-low", course(null, "50"), sa);
        assertThat(under50.getTables().get(0).getRows()).extracting(x -> x.get("studentId")).containsExactlyInAnyOrder("10002", "10003");
        assertThatThrownBy(() -> catalog.run("attendance-low", course(null, "33"), sa)).isInstanceOf(IllegalArgumentException.class);

        ReportResult meetings = catalog.run("attendance-sessions", ReportFilters.none(), sa);
        assertThat(meetings.getTables().get(0).getRows()).hasSize(3);
        assertThat(meetings.getTables().get(0).getRows().stream().filter(x -> Integer.valueOf(1).equals(x.get("meeting"))).findFirst().orElseThrow()
                .get("present")).as("first meeting: Ama and Kofi").isEqualTo(2);
        for (ReportResult r : List.of(summary, low, meetings))
            assertThat(new String(pdf.render(r), 0, 4, StandardCharsets.US_ASCII)).as(r.getKey()).isEqualTo("%PDF");
    }

    @Test
    void eachRoleSeesOnlyItsOwnClasses() {
        assertThatThrownBy(() -> catalog.run("attendance-register", course(course.getCid(), null), otherLecturer))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(catalog.run("attendance-sessions", ReportFilters.none(), otherLecturer).getTables().get(0).getRows()).isEmpty();
        assertThat(catalog.run("attendance-sessions", ReportFilters.none(), otherHod).getTables().get(0).getRows()).isEmpty();
        assertThat(catalog.run("attendance-sessions", ReportFilters.none(), hod).getTables().get(0).getRows()).hasSize(3);
    }

    @Test
    void theReportsDisappearWhileAttendanceIsOff() {
        assertThat(catalog.definitions(sa)).extracting(ReportCatalog.Definition::key).contains("attendance-register", "attendance-low");
        settings.updateSetting("FEATURE_ATTENDANCE", "false");
        assertThat(catalog.definitions(sa)).extracting(ReportCatalog.Definition::key).doesNotContain("attendance-register");
    }
}
