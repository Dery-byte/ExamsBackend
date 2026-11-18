package com.exam.service.materials;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.*;
import com.exam.model.materials.CourseMaterial;
import com.exam.repository.*;
import com.exam.repository.materials.CourseMaterialRepository;
import com.exam.service.MarksEntryService;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.comms.NotificationService;
import com.exam.service.features.FeatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The every-minute job as the real timer runs it: no transaction around it, data already committed
 * (2026-11-18: saving items outside a transaction made Hibernate reload them and fail with
 * "force initializing collection loading", so nothing was ever announced). Each kind of notice must go
 * out once, and be marked so it isn't sent again.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:materialscheduler;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
        "app.materials.dir=${java.io.tmpdir}/exam-materials-scheduler-test",
})
@Import({MaterialService.class, AssignmentService.class, MaterialAccess.class, MaterialCalendar.class, MaterialStorage.class, OfficePreview.class,
        AiProgress.class, SubmissionText.class, Gradebook.class, MaterialLinks.class, MaterialScheduler.class, MaterialNotices.class,
        FeatureService.class, SystemSettingService.class, AcademicSessionService.class, com.exam.service.comms.CurrentUserService.class})
class MaterialSchedulerTest {

    @Autowired MaterialService materials;
    @Autowired AssignmentService assignments;
    @Autowired MaterialScheduler scheduler;
    @Autowired SystemSettingService settings;
    @Autowired AcademicSessionService academicSessions;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired CategoryRepository categories;
    @Autowired Registered_coursesRepository registrations;
    @Autowired CourseMaterialRepository materialRows;

    @MockBean NotificationService notifications;
    @MockBean MarksEntryService marksEntry;
    @MockBean AssignmentAiMarker ai;

    String tag;
    Category course, otherCourse;
    User lecturer, ama, kofi;

    @BeforeEach
    void setUp() {
        settings.updateSetting(SystemSettingService.MODULE_MATERIALS, "true");
        settings.updateSetting("FEATURE_COURSE_MATERIALS", "true");
        when(ai.providers()).thenReturn(List.of());
        tag = String.valueOf(System.nanoTime()).substring(6);

        Department d = new Department();
        d.setName("Computing " + tag);
        d.setCode("C" + tag);
        d = departments.save(d);
        Program p = new Program();
        p.setName("Computer Science " + tag);
        p.setCode("CS" + tag);
        p.setDurationYears(4);
        p.setDepartment(d);
        p.setEnabled(true);
        p = programs.save(p);
        // Real-world shape: a second programme and department, the lecturer also in that department
        Department d2 = new Department();
        d2.setName("Maths " + tag);
        d2.setCode("M" + tag);
        d2 = departments.save(d2);
        Program p2 = new Program();
        p2.setName("Mathematics " + tag);
        p2.setCode("MA" + tag);
        p2.setDurationYears(4);
        p2.setDepartment(d2);
        p2.setEnabled(true);
        p2 = programs.save(p2);
        lecturer = user("lect" + tag, Role.LECTURER, null, d);
        lecturer.setSecondaryDepartments(new HashSet<>(Set.of(d2)));
        lecturer = users.save(lecturer);
        ama = user("ama" + tag, Role.NORMAL, p, null);
        kofi = user("kofi" + tag, Role.NORMAL, p, null);

        Category c = new Category();
        c.setTitle("Data Structures");
        c.setCourseCode("CSC" + tag);
        c.setLevel("200");
        c.setSemester(1);
        c.setPrograms(new HashSet<>(Set.of(p, p2)));  // eagerly loaded collections along the item's links
        c.setUser(lecturer);
        course = categories.save(c);
        Category other = new Category();             // a second course by the same lecturer, sharing a programme
        other.setTitle("Algorithms");
        other.setCourseCode("CSD" + tag);
        other.setLevel("200");
        other.setSemester(1);
        other.setPrograms(new HashSet<>(Set.of(p, p2)));
        other.setUser(lecturer);
        otherCourse = categories.save(other);
        AcademicSession current = academicSessions.current();
        for (User s : List.of(ama, kofi)) {
            Registered_courses r = new Registered_courses(null, new Date(), course, s);
            r.setSession(current);
            registrations.save(r);
        }
    }

    private User user(String username, Role role, Program program, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFirstname(username);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        if (program != null) { u.setProgram(program); u.setCurrentLevel(200); }
        if (dept != null) u.setDepartment(dept);
        return users.save(u);
    }

    private CourseMaterial row(String title) {
        return materialRows.findAll().stream().filter(m -> m.getTitle().equals(title)
                        && List.of(course.getCid(), otherCourse.getCid()).contains(m.getSpace().getCourse().getCid()))
                .findFirst().orElseThrow();
    }

    private MaterialService.MaterialRequest note(String title) {
        return new MaterialService.MaterialRequest(1, "NOTE", title, "Read this.", null, "VISIBLE", null, null, null, null, null, null, null, null);
    }

    private MaterialService.MaterialRequest assignment(String title, LocalDateTime due) {
        return new MaterialService.MaterialRequest(1, "ASSIGNMENT", title, "Answer.", null, "VISIBLE", null, null, due, BigDecimal.TEN, null, null, true, true);
    }

    @Test
    void newItemsAreAnnouncedOnceWithoutATransactionAroundTheJob() {
        materials.create(lecturer, course.getCid(), note("Week one notes"), List.of());
        materials.create(lecturer, course.getCid(), note("Week one slides"), List.of());
        materials.create(lecturer, otherCourse.getCid(), note("Other course notes"), List.of());

        scheduler.everyMinute();

        // One message for the course, to its students
        verify(notifications, times(1)).notify(argThat((Collection<User> to) -> to.size() == 2), eq("MATERIAL"), contains("CSC" + tag), anyString(), anyString());
        assertThat(row("Other course notes").isNotified()).isTrue();
        assertThat(row("Week one notes").isNotified()).isTrue();
        assertThat(row("Week one notes").getPublishedAt()).isNotNull();
        assertThat(row("Week one slides").isNotified()).isTrue();

        scheduler.everyMinute();
        verify(notifications, times(1)).notify(anyCollection(), eq("MATERIAL"), contains("CSC" + tag), anyString(), anyString());
    }

    @Test
    void dueRemindersGoOutOnceToThoseWhoHaventHandedIn() {
        materials.create(lecturer, course.getCid(), assignment("Essay " + tag, LocalDateTime.now().plusHours(12)), List.of());
        Long id = row("Essay " + tag).getId();
        assignments.submit(ama, id, "my answer", null, List.of());

        scheduler.everyMinute();

        verify(notifications, times(1)).notify(argThat((Collection<User> to) -> to.size() == 1 && to.iterator().next().getId().equals(kofi.getId())),
                eq("ASSIGNMENT"), eq("Assignment due soon"), anyString(), anyString());
        assertThat(row("Essay " + tag).isReminderSent()).isTrue();

        scheduler.everyMinute();
        verify(notifications, times(1)).notify(anyCollection(), eq("ASSIGNMENT"), eq("Assignment due soon"), anyString(), anyString());
    }

    @Test
    void theLecturerHearsOnceWhenSubmissionsClose() {
        materials.create(lecturer, course.getCid(), assignment("Lab " + tag, LocalDateTime.now().plusDays(3)), List.of());
        Long id = row("Lab " + tag).getId();
        assignments.submit(ama, id, "my answer", null, List.of());
        assignments.closeNow(lecturer, id);

        scheduler.everyMinute();

        verify(notifications, times(1)).notify(argThat((User to) -> to.getId().equals(lecturer.getId())),
                eq("ASSIGNMENT"), eq("Submissions closed"), contains("1 of 2 handed in"), anyString());
        assertThat(row("Lab " + tag).isClosedNotified()).isTrue();

        scheduler.everyMinute();
        verify(notifications, times(1)).notify(any(User.class), eq("ASSIGNMENT"), eq("Submissions closed"), anyString(), anyString());
    }
}
