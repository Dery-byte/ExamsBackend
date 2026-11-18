package com.exam.service.materials;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.*;
import com.exam.model.materials.CourseMaterial;
import com.exam.repository.*;
import com.exam.repository.materials.CourseMaterialRepository;
import com.exam.repository.materials.CourseSpaceRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Course materials on H2: the three switches (developer module, Super Admin / department feature,
 * lecturer visibility per course, week and item), real-content file checks, hand-in with closing and
 * reopening, marking with marks released later, AI suggestions kept apart from the mark, hiding
 * during a quiz, announcements, copying from an earlier term, term dates and signed links.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:materials;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
        "app.materials.dir=${java.io.tmpdir}/exam-materials-test",
})
@Import({MaterialService.class, AssignmentService.class, MaterialAccess.class, MaterialCalendar.class, MaterialStorage.class, OfficePreview.class, AiProgress.class, SubmissionText.class, Gradebook.class,
        MaterialLinks.class, MaterialScheduler.class, MaterialNotices.class, FeatureService.class, SystemSettingService.class,
        AcademicSessionService.class, com.exam.service.comms.CurrentUserService.class})
class MaterialsJpaTest {

    @Autowired MaterialService materials;
    @Autowired AssignmentService assignments;
    @Autowired MaterialCalendar calendar;
    @Autowired MaterialLinks links;
    @Autowired MaterialScheduler scheduler;
    @Autowired SystemSettingService settings;
    @Autowired FeatureService features;
    @Autowired AcademicSessionService academicSessions;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired ProgramRepository programs;
    @Autowired CategoryRepository categories;
    @Autowired Registered_coursesRepository registrations;
    @Autowired QuizRepository quizzes;
    @Autowired QuizAttemptRepository attempts;
    @Autowired CourseMaterialRepository materialRows;
    @Autowired CourseSpaceRepository spaces;
    @Autowired AcademicSessionRepository sessionRows;

    @MockBean NotificationService notifications;
    @MockBean MarksEntryService marksEntry;
    @MockBean AssignmentAiMarker ai;

    Department cmp;
    Program cs;
    Category course;
    User lecturer, otherLecturer, hod, sa, ama, kofi, outsider;

    static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << >>\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);

    @BeforeEach
    void setUp() {
        settings.updateSetting(SystemSettingService.MODULE_MATERIALS, "true");
        settings.updateSetting("FEATURE_COURSE_MATERIALS", "true");
        when(ai.providers()).thenReturn(List.of());
        when(ai.pick(any())).thenAnswer(inv -> inv.getArgument(0) == null ? LlmProvider.CLAUDE : inv.getArgument(0));
        assignments.runAiOnCallingThreadForTests();

        cmp = new Department();
        cmp.setName("Computing");
        cmp.setCode("CMP");
        cmp = departments.save(cmp);
        cs = new Program();
        cs.setName("Computer Science");
        cs.setCode("CS");
        cs.setDurationYears(4);
        cs.setDepartment(cmp);
        cs.setEnabled(true);
        cs = programs.save(cs);

        lecturer = user("lect1", Role.LECTURER, null);
        otherLecturer = user("lect2", Role.LECTURER, null);
        hod = user("hod1", Role.ADMIN, null);
        sa = user("sa1", Role.SUPER_ADMIN, null);
        ama = user("10001", Role.NORMAL, cs);
        kofi = user("10002", Role.NORMAL, cs);
        outsider = user("10003", Role.NORMAL, cs);

        course = new Category();
        course.setTitle("Data Structures");
        course.setCourseCode("CSC 201");
        course.setLevel("200");
        course.setSemester(1);
        course.setPrograms(new HashSet<>(Set.of(cs)));
        course.setUser(lecturer);
        course = categories.save(course);

        AcademicSession current = academicSessions.current();
        for (User s : List.of(ama, kofi)) {
            Registered_courses r = new Registered_courses(null, new Date(), course, s);
            r.setSession(current);
            registrations.save(r);
        }
    }

    private User user(String username, Role role, Program program) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFirstname(username);
        u.setLastname("Test");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        if (role == Role.NORMAL) {
            u.setProgram(program);
            u.setCurrentLevel(200);
        } else if (role != Role.SUPER_ADMIN) {
            u.setDepartment(cmp);
        }
        return users.save(u);
    }

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("files", name, "application/pdf", PDF);
    }

    private MaterialService.MaterialRequest note(int week, String title, String visibility, LocalDateTime from) {
        return new MaterialService.MaterialRequest(week, "NOTE", title, null, null, visibility, from, null,
                null, null, null, null, null, null);
    }

    private MaterialService.MaterialRequest assignment(int week, String title, LocalDateTime due, BigDecimal max) {
        return new MaterialService.MaterialRequest(week, "ASSIGNMENT", title, "Answer all questions.", null, "VISIBLE", null, null,
                due, max, "Q1 5 marks, Q2 5 marks", null, true, true);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> studentItems(User s) {
        Map<String, Object> view = materials.studentSpace(s, course.getCid());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> w : (List<Map<String, Object>>) view.get("weeks")) out.addAll((List<Map<String, Object>>) w.get("items"));
        return out;
    }

    private List<String> studentTitles(User s) {
        return studentItems(s).stream().map(m -> (String) m.get("title")).toList();
    }

    private Long idOf(String title) {
        return materialRows.findAll().stream().filter(m -> m.getTitle().equals(title)).findFirst().orElseThrow().getId();
    }

    @Test
    void theDeveloperModuleAndTheDepartmentFeatureGateEveryone() {
        settings.updateSetting(SystemSettingService.MODULE_MATERIALS, "false");
        assertThatThrownBy(() -> materials.manageSpace(lecturer, course.getCid())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> materials.manageSpace(sa, course.getCid())).isInstanceOf(AccessDeniedException.class);
        assertThat(features.flagsFor(lecturer).get("COURSE_MATERIALS")).isFalse();
        assertThat(SystemSettingService.isDeveloperOnly(SystemSettingService.MODULE_MATERIALS)).isTrue();
        assertThat(SystemSettingService.isDeveloperOnly(SystemSettingService.MATERIALS_QUOTA_GB)).isTrue();

        settings.updateSetting(SystemSettingService.MODULE_MATERIALS, "true");
        assertThat(materials.manageSpace(lecturer, course.getCid())).containsKey("weeks");

        // The HOD switches it off for the department: the lecturer and students lose it, the Super Admin doesn't
        features.setForDepartment(hod, "COURSE_MATERIALS", cmp.getId(), false);
        assertThatThrownBy(() -> materials.manageSpace(lecturer, course.getCid())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> materials.studentSpace(ama, course.getCid())).isInstanceOf(AccessDeniedException.class);
        assertThat(materials.manageSpace(sa, course.getCid())).containsKey("weeks");
        features.setForDepartment(hod, "COURSE_MATERIALS", cmp.getId(), null);

        // Another lecturer can't manage the course; the HOD can
        assertThatThrownBy(() -> materials.manageSpace(otherLecturer, course.getCid())).isInstanceOf(AccessDeniedException.class);
        assertThat(materials.manageSpace(hod, course.getCid())).containsKey("weeks");
        assertThat(materials.manageCourses(lecturer, null)).extracting(m -> m.get("courseId")).containsExactly(course.getCid());
    }

    @Test
    void studentsSeeOnlyWhatTheLecturerHasReleased() {
        materials.create(lecturer, course.getCid(), note(1, "Visible notes", "VISIBLE", null), List.of(pdf("week1.pdf")));
        materials.create(lecturer, course.getCid(), note(1, "Draft notes", "DRAFT", null), List.of(pdf("draft.pdf")));
        materials.create(lecturer, course.getCid(), note(2, "Later notes", "SCHEDULED", LocalDateTime.now().plusDays(2)), List.of(pdf("later.pdf")));
        materials.create(lecturer, course.getCid(), note(3, "Earlier schedule", "SCHEDULED", LocalDateTime.now().minusMinutes(1)), List.of(pdf("now.pdf")));

        assertThat(studentTitles(ama)).containsExactlyInAnyOrder("Visible notes", "Earlier schedule");
        // Not registered: refused
        assertThatThrownBy(() -> materials.studentSpace(outsider, course.getCid())).isInstanceOf(AccessDeniedException.class);

        // Week 1 hidden, then scheduled for later
        materials.saveWeek(lecturer, course.getCid(), 1, new MaterialService.WeekRequest("Linked lists", "HIDDEN", null));
        assertThat(studentTitles(ama)).containsExactly("Earlier schedule");
        materials.saveWeek(lecturer, course.getCid(), 1, new MaterialService.WeekRequest(null, "SCHEDULED", LocalDateTime.now().plusHours(1)));
        assertThat(studentTitles(ama)).containsExactly("Earlier schedule");
        materials.saveWeek(lecturer, course.getCid(), 1, new MaterialService.WeekRequest(null, "OPEN", null));
        assertThat(studentTitles(ama)).contains("Visible notes");

        // The whole course hidden
        materials.saveSettings(lecturer, course.getCid(), new MaterialService.SettingsRequest(false, null, null, null, null, null, null, null));
        assertThat(materials.studentSpace(ama, course.getCid()).get("hiddenReason")).isEqualTo("HIDDEN");
        materials.saveSettings(lecturer, course.getCid(), new MaterialService.SettingsRequest(true, null, null, null, null, null, null, null));

        // The draft's file can't be fetched by a student; the visible one can, and counts as opened
        Long draft = idOf("Draft notes");
        Long visible = idOf("Visible notes");
        String draftFile = fileIdOf(draft);
        assertThatThrownBy(() -> materials.studentFileLink(ama, draftFile, false)).isInstanceOf(AccessDeniedException.class);
        Map<String, Object> link = materials.studentFileLink(ama, fileIdOf(visible), true);
        assertThat((String) link.get("url")).startsWith("/materials/download/");
        assertThat(link).doesNotContainKey("previewUrl");          // PDFs need no converted copy
        @SuppressWarnings("unchecked")
        Map<String, Object> seen = materials.viewsOf(lecturer, visible);
        assertThat(seen.get("opened")).isEqualTo(1L);
        assertThat(seen.get("rosterSize")).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private String fileIdOf(Long materialId) {
        Map<String, Object> view = materials.manageSpace(lecturer, course.getCid());
        for (Map<String, Object> w : (List<Map<String, Object>>) view.get("weeks"))
            for (Map<String, Object> item : (List<Map<String, Object>>) w.get("items"))
                if (materialId.equals(item.get("id")))
                    return (String) ((List<Map<String, Object>>) item.get("files")).get(0).get("id");
        throw new AssertionError("no file");
    }

    @Test
    void filesAreCheckedByWhatTheyReallyAre() {
        MockMultipartFile fake = new MockMultipartFile("files", "notes.pdf", "application/pdf", "MZ\u0090\u0000 this is a program".getBytes(StandardCharsets.ISO_8859_1));
        assertThatThrownBy(() -> materials.create(lecturer, course.getCid(), note(1, "Bad", "VISIBLE", null), List.of(fake)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("doesn't look like a real .pdf");
        MockMultipartFile page = new MockMultipartFile("files", "page.html", "text/html", "<html></html>".getBytes());
        assertThatThrownBy(() -> materials.create(lecturer, course.getCid(), note(1, "Bad", "VISIBLE", null), List.of(page)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("can't be uploaded");
        MockMultipartFile disguisedPage = new MockMultipartFile("files", "notes.txt", "text/plain", "<!DOCTYPE html><script>x</script>".getBytes());
        assertThatThrownBy(() -> materials.create(lecturer, course.getCid(), note(1, "Bad", "VISIBLE", null), List.of(disguisedPage)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(materialRows.findAll()).isEmpty();
        assertThat(MaterialStorage.cleanName("..\\..\\etc/passwd\"x.pdf")).isEqualTo("passwdx.pdf");
    }

    @Test
    void submissionsCloseAtTheDueDateAndTheLecturerCanReopenThem() {
        materials.create(lecturer, course.getCid(), assignment(2, "Essay", LocalDateTime.now().plusHours(1), BigDecimal.TEN), List.of());
        Long id = idOf("Essay");

        Map<String, Object> mine = assignments.submit(ama, id, "My essay", null, List.of(pdf("essay.pdf")));
        assertThat(((Map<?, ?>) mine.get("submission")).get("late")).isEqualTo(false);
        assertThat(((List<?>) ((Map<?, ?>) mine.get("submission")).get("files"))).hasSize(1);

        // Replace: keep nothing, send a new file
        assignments.submit(ama, id, null, List.of(), List.of(pdf("essay-v2.pdf")));

        assignments.closeNow(lecturer, id);
        assertThatThrownBy(() -> assignments.submit(kofi, id, "late work", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Submissions closed");

        // The due date moves into the past; the lecturer reopens with a new closing date
        materials.update(lecturer, id, new MaterialService.MaterialRequest(null, null, null, null, null, null, null, null,
                LocalDateTime.now().minusHours(1), null, null, null, null, null));
        assertThatThrownBy(() -> assignments.reopen(lecturer, id, new AssignmentService.ReopenRequest(LocalDateTime.now().minusMinutes(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assignments.reopen(lecturer, id, new AssignmentService.ReopenRequest(LocalDateTime.now().plusDays(1)));
        Map<String, Object> late = assignments.submit(kofi, id, "late work", null, List.of());
        assertThat(((Map<?, ?>) late.get("submission")).get("late")).isEqualTo(true);

        Map<String, Object> roster = assignments.roster(lecturer, id);
        assertThat(((Map<?, ?>) roster.get("counts")).get("handedIn")).isEqualTo(2L);
        assertThat(((Map<?, ?>) roster.get("counts")).get("late")).isEqualTo(1L);
        // A student can't hand in for a course they aren't registered for
        assertThatThrownBy(() -> assignments.submit(outsider, id, "x", null, List.of())).isInstanceOf(AccessDeniedException.class);
        // Assignments with work handed in can't be deleted
        assertThatThrownBy(() -> materials.delete(lecturer, id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marksStayHiddenUntilReleasedAndTheAiOnlySuggests() {
        materials.create(lecturer, course.getCid(), assignment(1, "Lab 1", LocalDateTime.now().plusDays(1), BigDecimal.TEN), List.of());
        Long id = idOf("Lab 1");
        assignments.submit(ama, id, "Answer text", null, List.of(pdf("lab.pdf")));

        when(ai.mark(any(), any(), any())).thenReturn(new AssignmentAiMarker.Result(new BigDecimal("7.5"), "Good work.", LlmProvider.CLAUDE));
        Map<String, Object> work = assignments.aiSuggest(lecturer, id, ama.getId(), "CLAUDE");
        assertThat((BigDecimal) work.get("aiScore")).isEqualByComparingTo("7.5");
        assertThat(work.get("score")).isNull();                       // nothing saved as the mark

        assertThatThrownBy(() -> assignments.mark(lecturer, id, ama.getId(), new AssignmentService.MarkRequest(new BigDecimal("11"), null, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assignments.mark(lecturer, id, ama.getId(), new AssignmentService.MarkRequest(new BigDecimal("8"), "Well done", true));

        Map<String, Object> before = assignments.studentView(ama, id);
        assertThat(((Map<?, ?>) before.get("submission")).containsKey("score")).isFalse();
        assertThatThrownBy(() -> assignments.submit(ama, id, "changed", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("marked");

        assignments.releaseMarks(lecturer, id, true);
        Map<String, Object> after = assignments.studentView(ama, id);
        assertThat((BigDecimal) ((Map<?, ?>) after.get("submission")).get("score")).isEqualByComparingTo("8");
        verify(notifications, atLeastOnce()).notify(anyCollection(), eq("ASSIGNMENT"), eq("Assignment marked"), anyString(), anyString());

        // Paper work: a mark for a student who handed in nothing online
        assignments.mark(lecturer, id, kofi.getId(), new AssignmentService.MarkRequest(new BigDecimal("5"), null, false));
        assertThat(((Map<?, ?>) assignments.roster(lecturer, id).get("counts")).get("marked")).isEqualTo(2L);
    }

    @Test
    void identicalFilesFromTwoStudentsAreFlagged() {
        materials.create(lecturer, course.getCid(), assignment(1, "Copy check", LocalDateTime.now().plusDays(1), BigDecimal.TEN), List.of());
        Long id = idOf("Copy check");
        assignments.submit(ama, id, null, null, List.of(pdf("mine.pdf")));
        assignments.submit(kofi, id, null, null, List.of(pdf("also-mine.pdf")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) assignments.roster(lecturer, id).get("students");
        Map<String, Object> amaRow = rows.stream().filter(r -> ama.getId().equals(r.get("studentId"))).findFirst().orElseThrow();
        assertThat((List<?>) amaRow.get("sameFileAs")).hasSize(1);
    }

    @Autowired Gradebook gradebook;

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowOf(Map<String, Object> book, User student) {
        return ((List<Map<String, Object>>) book.get("students")).stream().filter(r -> student.getId().equals(r.get("studentId"))).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRunningTotalAddsMarkedAndMissingWorkAndStudentsSeeOnlyReleasedMarks() {
        materials.create(lecturer, course.getCid(), assignment(1, "A1", LocalDateTime.now().plusDays(1), new BigDecimal("5")), List.of());
        materials.create(lecturer, course.getCid(), assignment(2, "A2", LocalDateTime.now().plusDays(1), BigDecimal.TEN), List.of());
        Long a1 = idOf("A1"), a2 = idOf("A2");
        assignments.submit(ama, a1, "answer one", null, List.of());
        assignments.submit(ama, a2, "answer two", null, List.of());
        assignments.submit(kofi, a1, "kofi one", null, List.of());
        assignments.mark(lecturer, a1, ama.getId(), new AssignmentService.MarkRequest(new BigDecimal("4"), null, false));
        assignments.mark(lecturer, a2, ama.getId(), new AssignmentService.MarkRequest(new BigDecimal("8"), null, false));

        Map<String, Object> book = gradebook.staff(lecturer, course.getCid());
        assertThat((List<?>) book.get("assignments")).hasSize(2);
        Map<String, Object> amaRow = rowOf(book, ama);
        assertThat((BigDecimal) amaRow.get("total")).isEqualByComparingTo("12");
        assertThat((BigDecimal) amaRow.get("outOf")).isEqualByComparingTo("15");
        assertThat((BigDecimal) amaRow.get("percent")).isEqualByComparingTo("80.0");
        // Kofi: A1 handed in but not marked (pending, left out); A2 still open (left out)
        Map<String, Object> kofiRow = rowOf(book, kofi);
        assertThat(((Map<String, Map<String, Object>>) kofiRow.get("cells")).get(String.valueOf(a1)).get("state")).isEqualTo("PENDING");
        assertThat((BigDecimal) kofiRow.get("outOf")).isEqualByComparingTo("0");

        // A2 closes: Kofi's missing work counts 0 out of 10
        assignments.closeNow(lecturer, a2);
        kofiRow = rowOf(gradebook.staff(lecturer, course.getCid()), kofi);
        assertThat(((Map<String, Map<String, Object>>) kofiRow.get("cells")).get(String.valueOf(a2)).get("state")).isEqualTo("MISSING");
        assertThat((BigDecimal) kofiRow.get("total")).isEqualByComparingTo("0");
        assertThat((BigDecimal) kofiRow.get("outOf")).isEqualByComparingTo("10");

        // Practice: A2 switched off the total
        amaRow = rowOf(gradebook.setCounts(lecturer, a2, false), ama);
        assertThat((BigDecimal) amaRow.get("total")).isEqualByComparingTo("4");
        assertThat((BigDecimal) amaRow.get("outOf")).isEqualByComparingTo("5");
        gradebook.setCounts(lecturer, a2, true);

        // Students: only released marks count, and they're told what's still to come
        assignments.releaseMarks(lecturer, a1, true);
        Map<String, Object> mine = (Map<String, Object>) materials.studentSpace(ama, course.getCid()).get("myMarks");
        assertThat((BigDecimal) mine.get("total")).isEqualByComparingTo("4");
        assertThat((BigDecimal) mine.get("outOf")).isEqualByComparingTo("5");
        assertThat(mine.get("notReleased")).isEqualTo(1);
        Map<String, Object> card = materials.studentCourses(ama).get(0);
        assertThat((Map<String, Object>) card.get("assignmentTotal")).containsEntry("percent", new BigDecimal("80.0"));

        // Another lecturer can't open this course's marks
        assertThatThrownBy(() -> gradebook.staff(otherLecturer, course.getCid())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void similarTypedAnswersAreFlaggedWithTheSharedPassage() {
        materials.create(lecturer, course.getCid(), assignment(1, "Essay 2", LocalDateTime.now().plusDays(1), BigDecimal.TEN), List.of());
        Long id = idOf("Essay 2");
        assignments.submit(ama, id, SimilarityCheckTest.AMA, null, List.of());
        assignments.submit(kofi, id, SimilarityCheckTest.KOFI + " As an AI language model I hope this helps.", null, List.of());

        List<Map<String, Object>> rows = (List<Map<String, Object>>) assignments.roster(lecturer, id).get("students");
        Map<String, Object> kofiRow = rows.stream().filter(r -> kofi.getId().equals(r.get("studentId"))).findFirst().orElseThrow();
        assertThat((Map<String, Object>) kofiRow.get("similarTo")).containsEntry("name", "10001 Test");
        assertThat((Integer) kofiRow.get("chatbotPhrases")).isEqualTo(2);

        Map<String, Object> work = assignments.work(lecturer, id, kofi.getId());
        List<Map<String, Object>> similar = (List<Map<String, Object>>) work.get("similar");
        assertThat(similar).isNotEmpty();
        assertThat((List<String>) similar.get(0).get("passages")).anyMatch(p -> p.contains("insert at the head we create a new node"));
        assertThat((List<String>) work.get("chatbotPhrases")).contains("as an ai language model");
    }

    @Test
    void materialsHideFromAStudentWhileTheyTakeAQuizOfTheCourse() {
        materials.create(lecturer, course.getCid(), note(1, "Cheat sheet", "VISIBLE", null), List.of(pdf("a.pdf")));
        Quiz q = new Quiz();
        q.setTitle("Mid-sem");
        q.setQuizTime("30");
        q.setQuizpassword("x");
        q.setCategory(course);
        q = quizzes.save(q);
        QuizAttempt a = new QuizAttempt();
        a.setUser(ama);
        a.setQuiz(q);
        a.setAttemptNumber(1);
        a.setStartedAt(LocalDateTime.now().minusMinutes(5));
        attempts.save(a);

        assertThat(materials.studentSpace(ama, course.getCid()).get("hiddenReason")).isEqualTo("QUIZ");
        assertThat(studentTitles(kofi)).containsExactly("Cheat sheet");
        // The lecturer can turn the rule off
        materials.saveSettings(lecturer, course.getCid(), new MaterialService.SettingsRequest(null, false, null, null, null, null, null, null));
        assertThat(studentTitles(ama)).containsExactly("Cheat sheet");
    }

    @Test
    void theSchedulerAnnouncesNewItemsOnce() {
        materials.create(lecturer, course.getCid(), note(1, "Week one", "VISIBLE", null), List.of(pdf("a.pdf")));
        materials.create(lecturer, course.getCid(), note(1, "Draft", "DRAFT", null), List.of(pdf("b.pdf")));
        scheduler.announce();
        verify(notifications, times(1)).notify(anyCollection(), eq("MATERIAL"), contains("CSC 201"), contains("Week one"), anyString());
        assertThat(materialRows.findById(idOf("Week one")).orElseThrow().isNotified()).isTrue();
        assertThat(materialRows.findById(idOf("Draft")).orElseThrow().isNotified()).isFalse();
        scheduler.announce();
        verify(notifications, times(1)).notify(anyCollection(), eq("MATERIAL"), anyString(), anyString(), anyString());
    }

    @Test
    void termDatesGiveRealWeeksAndWeeklyRelease() {
        assertThatThrownBy(() -> calendar.saveTerm(lecturer, 1, LocalDate.now(), 14)).isInstanceOf(AccessDeniedException.class);
        LocalDate start = LocalDate.now().minusWeeks(2);
        calendar.saveTerm(sa, 1, start, 12);
        Map<String, Object> view = materials.manageSpace(lecturer, course.getCid());
        assertThat(view.get("currentWeek")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> settingsView = (Map<String, Object>) view.get("settings");
        assertThat(settingsView.get("weeks")).isEqualTo(12);
        assertThat(settingsView.get("weekOneMonday")).isEqualTo(MaterialCalendar.mondayOf(start));

        materials.saveSettings(lecturer, course.getCid(), new MaterialService.SettingsRequest(null, null, null, null, null, true, "WEDNESDAY", LocalTime.of(9, 0)));
        materials.create(lecturer, course.getCid(), note(6, "Week six", "VISIBLE", null), List.of(pdf("w6.pdf")));
        assertThat(studentTitles(ama)).doesNotContain("Week six");           // week 6 opens on its Wednesday
        LocalDateTime expected = MaterialService.releaseMoment(MaterialCalendar.mondayOf(start), 6, DayOfWeek.WEDNESDAY, LocalTime.of(9, 0));
        @SuppressWarnings("unchecked")
        Map<String, Object> week6 = ((List<Map<String, Object>>) materials.manageSpace(lecturer, course.getCid()).get("weeks")).get(6);
        assertThat(week6.get("state")).isEqualTo("SCHEDULED");
        assertThat(week6.get("releaseAt")).isEqualTo(expected);
    }

    @Test
    void copyingFromAnEarlierTermMakesDraftsThatShareTheFiles() {
        // Last year's materials
        AcademicSession current = academicSessions.current();
        AcademicSession last = new AcademicSession();
        last.setName("2024/2025");
        last.setStartDate(current.getStartDate().minusYears(1));
        last.setEndDate(current.getEndDate().minusYears(1));
        last = sessionRows.save(last);
        var oldSpace = new com.exam.model.materials.CourseSpace();
        oldSpace.setCourse(course);
        oldSpace.setAcademicSession(last);
        oldSpace = spaces.save(oldSpace);
        CourseMaterial old = new CourseMaterial();
        old.setSpace(oldSpace);
        old.setKind(CourseMaterial.Kind.NOTE);
        old.setTitle("Old notes");
        old.setWeekNumber(4);
        old.setVisibility(CourseMaterial.Visibility.VISIBLE);
        Long oldId = materialRows.save(old).getId();
        Long oldSpaceId = oldSpace.getId();

        assertThat(materials.copySources(lecturer, course.getCid())).hasSize(1);
        Map<String, Object> view = materials.copyFrom(lecturer, course.getCid(), oldSpaceId);
        assertThat(view.get("copied")).isEqualTo(1);
        CourseMaterial copy = materialRows.findAll().stream().filter(m -> oldId.equals(m.getCopiedFromId())).findFirst().orElseThrow();
        assertThat(copy.getVisibility()).isEqualTo(CourseMaterial.Visibility.DRAFT);
        assertThat(copy.getWeekNumber()).isEqualTo(4);
        // A second copy adds nothing
        assertThat(materials.copyFrom(lecturer, course.getCid(), oldSpaceId).get("copied")).isEqualTo(0);
    }

    @Test
    void signedLinksExpireAndCantBeAltered() throws Exception {
        materials.create(lecturer, course.getCid(), note(1, "Signed", "VISIBLE", null), List.of(pdf("s.pdf")));
        String fileId = fileIdOf(idOf("Signed"));
        String url = (String) materials.studentFileLink(ama, fileId, true).get("url");
        Map<String, String> q = new HashMap<>();
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) q.put(kv.split("=")[0], kv.split("=", 2)[1]);
        MaterialService.Download d = materials.download(fileId, Long.parseLong(q.get("u")), Long.parseLong(q.get("e")), q.get("i"), q.get("s"));
        assertThat(d.inline()).isTrue();
        assertThat(java.nio.file.Files.readAllBytes(d.path())).isEqualTo(PDF);
        // Someone else's id, or a later expiry, breaks the signature
        assertThatThrownBy(() -> materials.download(fileId, kofi.getId(), Long.parseLong(q.get("e")), q.get("i"), q.get("s")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> materials.download(fileId, Long.parseLong(q.get("u")), Long.parseLong(q.get("e")) + 600, q.get("i"), q.get("s")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Autowired OfficePreview officePreview;

    @Test
    void officeFilesGetAPdfViewOnlyWhenLibreOfficeIsThere() {
        // A minimal .pptx (a ZIP) uploads fine and, with no LibreOffice on this machine, offers no PDF view
        byte[] zip = {0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        materials.create(lecturer, course.getCid(), note(1, "Slides", "VISIBLE", null),
                List.of(new MockMultipartFile("files", "week1.pptx", "application/vnd.ms-powerpoint", zip)));
        String fileId = fileIdOf(idOf("Slides"));
        Map<String, Object> link = materials.studentFileLink(ama, fileId, true);
        if (officePreview.available()) assertThat(link).containsKey("previewUrl");
        else assertThat(link).doesNotContainKey("previewUrl");
        // A made-up preview link is refused
        assertThatThrownBy(() -> materials.preview(fileId, ama.getId(), System.currentTimeMillis() / 1000 + 60, "forged"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void recycleBinRestoresAndPurges() {
        materials.create(lecturer, course.getCid(), note(1, "Binned", "VISIBLE", null), List.of(pdf("bin.pdf")));
        Long id = idOf("Binned");
        materials.delete(lecturer, id);
        assertThat(studentTitles(ama)).doesNotContain("Binned");
        assertThat(materials.bin(lecturer, course.getCid())).hasSize(1);
        materials.restore(lecturer, id);
        assertThat(studentTitles(ama)).contains("Binned");
        materials.delete(lecturer, id);
        materials.purge(lecturer, id);
        assertThat(materialRows.findById(id)).isEmpty();
    }
}
