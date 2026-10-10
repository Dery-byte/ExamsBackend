package com.exam.service.reports;

import com.exam.model.QuizType;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.academic.DocumentVerification;
import com.exam.model.comms.AuditLog;
import com.exam.model.exam.*;
import com.exam.model.examops.ProctoringEvent;
import com.exam.model.examops.RemarkRequest;
import com.exam.model.fees.FeePayment;
import com.exam.model.fees.FeeSchedule;
import com.exam.repository.*;
import com.exam.service.SystemSettingService;
import com.exam.service.academic.AcademicRecordService;
import com.exam.service.academic.AcademicSessionService;
import com.exam.service.academic.GradingService;
import com.exam.service.academic.InstitutionService;
import com.exam.service.examops.TimetableService;
import com.exam.service.fees.FeeScheduleService;
import com.exam.service.fees.PaystackClient;
import com.exam.service.fees.ResultsHoldService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs every report (Super Admin and HOD) on in-memory H2 with one of everything in it, so each report's
 * queries are valid and its figures add up, and prints each one as a PDF.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:reports;MODE=MySQL;NON_KEYWORDS=USER,LEVEL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({ReportQueries.class, ReportSupport.class, Standings.class, AcademicReports.class, ResultsReports.class, TeachingReports.class, QuestionPapers.class, QuestionPaperPdf.class, com.exam.service.QuestionImageService.class, ExamReports.class,
        FinanceReports.class, OversightReports.class, ReportCatalog.class, ReportPdfService.class,
        AcademicRecordService.class, GradingService.class, AcademicSessionService.class, InstitutionService.class,
        SystemSettingService.class, TimetableService.class, FeeScheduleService.class, ResultsHoldService.class,
        com.exam.service.academic.ThemeService.class, com.exam.service.admin.MaintenanceService.class,
        ReportsJpaTest.Templates.class})
class ReportsJpaTest {

    @TestConfiguration
    static class Templates {
        @Bean
        SpringTemplateEngine templateEngine() {
            ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
            resolver.setPrefix("templates/");
            resolver.setSuffix(".html");
            resolver.setTemplateMode("HTML");
            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(resolver);
            return engine;
        }
    }

    @MockBean PaystackClient paystack;

    @Autowired ReportCatalog catalog;
    @Autowired ReportPdfService pdf;
    @Autowired FeeScheduleService feeSchedules;
    @Autowired EntityManager em;
    @Autowired UserRepository users;

    User admin, hod, lecturer, otherLecturer, ama, kofi;
    Program cs, accounting;
    Quiz pastQuiz;
    Category course;
    Quiz quiz;
    AcademicSession session;
    SemesterSheet sheet;

    @BeforeEach
    void setUp() {
        session = new AcademicSession();
        session.setName("2026/2027");
        session.setStartDate(LocalDate.now().minusDays(30));
        session.setEndDate(LocalDate.now().plusDays(300));
        session.setCurrent(true);
        em.persist(session);

        Department d = new Department();
        d.setName("Computing");
        d.setCode("CMP");
        em.persist(d);
        cs = new Program();
        cs.setName("Computer Science");
        cs.setCode("CS");
        cs.setDurationYears(4);
        cs.setDepartment(d);
        cs.setEnabled(true);
        em.persist(cs);

        Department business = new Department();
        business.setName("Business");
        business.setCode("BUS");
        em.persist(business);
        accounting = new Program();
        accounting.setName("Accounting");
        accounting.setCode("ACC");
        accounting.setDurationYears(4);
        accounting.setDepartment(business);
        accounting.setEnabled(true);
        em.persist(accounting);

        admin = users.save(user("root", "Root", Role.SUPER_ADMIN, null, null, null));
        hod = user("hod", "Hod", Role.ADMIN, null, null, null);
        hod.setDepartment(d);
        hod = users.save(hod);
        lecturer = user("lect", "Lect", Role.LECTURER, null, null, null);
        lecturer.setDepartment(d);
        lecturer = users.save(lecturer);
        otherLecturer = user("lect2", "Other", Role.LECTURER, null, null, null);
        otherLecturer.setDepartment(d);
        otherLecturer = users.save(otherLecturer);
        ama = users.save(user("10001", "Ama", Role.NORMAL, cs, 200, 1));
        kofi = users.save(user("10002", "Kofi", Role.NORMAL, cs, 200, null));

        course = new Category();
        course.setTitle("Data Structures");
        course.setCourseCode("CS201");
        course.setLevel("200");
        course.setSemester(1);
        course.setCreditUnits(3);
        course.setPrograms(new HashSet<>(Set.of(cs)));
        course.setUser(lecturer);
        em.persist(course);

        quiz = new Quiz();
        quiz.setTitle("Mid-sem");
        quiz.setQuizTime("60");
        quiz.setQuizpassword("");
        quiz.setQuizType(QuizType.OBJ);
        quiz.setActive(true);
        quiz.setCategory(course);
        quiz.setQuizDate(LocalDate.now().plusDays(1));
        quiz.setStartTime(LocalTime.of(9, 0));
        quiz.setProctoringEnabled(true);
        em.persist(quiz);

        pastQuiz = new Quiz();
        pastQuiz.setTitle("Quiz 1");
        pastQuiz.setQuizTime("30");
        pastQuiz.setQuizpassword("");
        pastQuiz.setQuizType(QuizType.OBJ);
        pastQuiz.setActive(true);
        pastQuiz.setCategory(course);
        pastQuiz.setQuizDate(LocalDate.now().minusDays(2));
        pastQuiz.setStartTime(LocalTime.of(9, 0));
        em.persist(pastQuiz);

        Questions mcq = new Questions();
        mcq.setContent("<p>Which structure is <b>LIFO</b>?</p>");
        mcq.setOption1("Queue");
        mcq.setOption2("Stack");
        mcq.setOption3("Tree");
        mcq.setOption4("Graph");
        mcq.setcorrect_answer(new String[]{"Stack"});
        mcq.setQuestionType(QuestionType.MCQ);
        mcq.setQuiz(quiz);
        em.persist(mcq);
        em.persist(answer(ama, mcq, "Stack"));
        em.persist(answer(kofi, mcq, "Tree"));

        Registered_courses reg = new Registered_courses();
        reg.setUser(ama);
        reg.setCategory(course);
        reg.setSession(session);
        reg.setRegDate(new Date());
        em.persist(reg);

        Report result = new Report();
        result.setUser(ama);
        result.setQuiz(quiz);
        result.setMarks(new BigDecimal("36"));
        result.setPercentage(72.0);
        result.setSubmissionDate(LocalDateTime.now());
        result.setIsReviewed(false);
        result.setEvaluationMethod("GPT");
        em.persist(result);

        em.persist(attempt(ama, AttemptStatus.SUBMITTED, null));
        em.persist(attempt(kofi, AttemptStatus.VOIDED, "Power cut"));
        em.persist(event(ama, "tab-switch"));
        em.persist(event(ama, "auto-submit"));

        RemarkRequest rr = new RemarkRequest();
        rr.setReport(result);
        rr.setStudent(ama);
        rr.setReason("Question 3 was marked wrong");
        em.persist(rr);

        TheoryGradingJob job = new TheoryGradingJob();
        job.setUserId(ama.getId());
        job.setQuizId(quiz.getqId());
        job.setAttemptId(1L);
        job.setPayload("{}");
        job.setStatus(TheoryGradingJob.Status.FAILED);
        job.setTries(5);
        job.setLastError("timeout");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        job.setNextAttemptAt(LocalDateTime.now());
        em.persist(job);

        sheet = new SemesterSheet();
        sheet.setProgram(cs);
        sheet.setLevel("200");
        sheet.setSemester(1);
        sheet.setStatus("APPROVED");
        sheet.setSession(session);
        sheet.setCourses(new ArrayList<>(List.of(course)));
        sheet.setPublishAt(Instant.now().plusSeconds(86400));
        em.persist(sheet);
        em.persist(mark(ama, "65", "C"));
        em.persist(mark(kofi, "30", "F"));

        feeSchedules.save(new FeeScheduleService.ScheduleRequest(cs.getId(), 200, null, false, new BigDecimal("1000"),
                null, null, null, null), "SA");
        FeeSchedule schedule = em.createQuery("SELECT s FROM FeeSchedule s", FeeSchedule.class).getSingleResult();
        FeePayment pay = new FeePayment();
        pay.setStudent(ama);
        pay.setSchedule(schedule);
        pay.setSession(session);
        pay.setProgramName(cs.getName());
        pay.setLevel(200);
        pay.setAmount(new BigDecimal("400"));
        pay.setCurrency("GHS");
        pay.setReference("CASH-1");
        pay.setStatus(FeePayment.Status.SUCCESS);
        pay.setMethod(FeePayment.Method.CASH);
        pay.setRecordedBy("Root");
        pay.setPaidAt(LocalDateTime.now());
        em.persist(pay);

        em.persist(audit("Approved marks sheet", admin, String.valueOf(sheet.getId())));
        em.persist(audit("Saved marks", lecturer, String.valueOf(sheet.getId())));

        DocumentVerification doc = new DocumentVerification();
        doc.setCode("ABCD-EFGH-JKMN");
        doc.setDocType(DocumentVerification.Type.TRANSCRIPT);
        doc.setStudentId(ama.getId());
        doc.setStudentName("Ama Test");
        doc.setStudentUsername("10001");
        doc.setProgramName(cs.getName());
        em.persist(doc);

        em.flush();
        em.clear();
    }

    @Test
    void everyReportRunsAndPrints() throws Exception {
        List<ReportCatalog.Definition> defs = catalog.definitions(admin);
        assertThat(defs).extracting(ReportCatalog.Definition::key).contains("department-comparison", "course-registration",
                "academic-standing", "fee-debtors", "sensitive-actions", "exam-load");
        for (ReportCatalog.Definition d : defs) {
            ReportResult all = catalog.run(d.key(), ReportFilters.none(), admin);
            assertThat(all.getTables()).as(d.key()).isNotEmpty();
            ReportResult inSession = catalog.run(d.key(), filters(session.getId(), null, null), admin);
            assertThat(inSession.getTitle()).isEqualTo(all.getTitle());
            byte[] bytes = pdf.render(all);
            assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).as(d.key() + " PDF").isEqualTo("%PDF");
        }
    }

    @Test
    void courseRegistrationCountsAndListsStudents() {
        ReportResult r = catalog.run("course-registration", filters(session.getId(), null, null), admin);
        Map<String, Object> row = table(r, "courses").get(0);
        assertThat(row.get("registered")).isEqualTo(1);
        assertThat(row.get("expected")).isEqualTo(2);
        assertThat(row.get("missing")).isEqualTo(1L);

        ReportResult drill = catalog.run("course-registration", filters(session.getId(), course.getCid(), null), admin);
        assertThat(table(drill, "registered")).extracting(m -> m.get("studentId")).containsExactly("10001");
        assertThat(table(drill, "notRegistered")).extracting(m -> m.get("studentId")).containsExactly("10002");
    }

    @Test
    void debtorsOweTheRestOfTheirFee() {
        ReportResult r = catalog.run("fee-debtors", ReportFilters.none(), admin);
        List<Map<String, Object>> rows = table(r, "debtors");
        assertThat(rows).extracting(m -> m.get("studentId")).containsExactly("10002", "10001");
        assertThat((BigDecimal) rows.get(1).get("balance")).isEqualByComparingTo("600");
        ReportResult c = catalog.run("fee-collections", ReportFilters.none(), admin);
        assertThat(table(c, "manual")).extracting(m -> m.get("reference")).containsExactly("CASH-1");
    }

    @Test
    void integrityStandingAndPublicationAddUp() {
        ReportResult integrity = catalog.run("exam-integrity", ReportFilters.none(), admin);
        Map<String, Object> q = table(integrity, "quizzes").get(0);
        assertThat(q.get("violations")).isEqualTo(1);
        assertThat(q.get("autoSubmitted")).isEqualTo(1L);
        assertThat(q.get("voided")).isEqualTo(1L);
        assertThat(table(integrity, "voided")).extracting(m -> m.get("reason")).containsExactly("Power cut");

        ReportResult standing = catalog.run("academic-standing", ReportFilters.none(), admin);
        Map<String, Map<String, Object>> byId = new HashMap<>();
        table(standing, "students").forEach(m -> byId.put((String) m.get("studentId"), m));
        assertThat(byId.get("10002").get("outstanding")).isEqualTo("CS201");
        assertThat(byId.get("10001").get("standing")).isEqualTo("Good standing");

        ReportResult pub = catalog.run("results-publication", ReportFilters.none(), admin);
        assertThat(table(pub, "sheets").get(0).get("status")).isEqualTo("Approved");
        assertThat(table(pub, "sheets").get(0).get("lastChange")).isNotNull();
        assertThat(table(pub, "waiting").get(0).get("waitingFor")).isEqualTo("Scheduled release");

        ReportResult load = catalog.run("exam-load", ReportFilters.none(), admin);
        Map<String, Object> exam = table(load, "exams").get(0);
        assertThat(exam.get("expected")).isEqualTo(1);
        assertThat(exam.get("basis")).isEqualTo("Registered");

        ReportResult ai = catalog.run("ai-marking", ReportFilters.none(), admin);
        assertThat(table(ai, "failed")).extracting(m -> m.get("error")).containsExactly("timeout");

        ReportResult staff = catalog.run("staff-workload", ReportFilters.none(), admin);
        Map<String, Object> lect = table(staff, "lecturers").get(0);
        assertThat(lect.get("toReview")).isEqualTo(1L);
        assertThat(lect.get("remarks")).isEqualTo(1L);
        assertThat(lect.get("lastAction")).isNotNull();
    }

    @Test
    void hodSeesOnlyDepartmentReportsLockedToTheirDepartment() throws Exception {
        List<ReportCatalog.Definition> defs = catalog.definitions(hod);
        assertThat(defs).extracting(ReportCatalog.Definition::key)
                .contains("broadsheet", "course-results", "exam-absentees", "top-performers", "staff-workload")
                .doesNotContain("department-comparison", "fee-collections", "fee-debtors", "sensitive-actions", "documents-issued");
        assertThat(defs).allSatisfy(d -> assertThat(d.filters()).noneMatch(x -> x.key().equals("department")));

        for (ReportCatalog.Definition d : defs) {
            ReportResult r = catalog.run(d.key(), filters(session.getId(), null, null), hod);
            assertThat(r.getScope()).as(d.key()).anyMatch(line -> line.equals("Department: Computing"));
            assertThat(pdf.render(r)).isNotEmpty();
        }
        // Another department's programme, or a Super Admin report, is refused
        assertThatThrownBy(() -> catalog.run("student-headcount",
                new ReportFilters(null, null, accounting.getId(), null, null, null, null, null, null, null), hod))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.run("fee-debtors", ReportFilters.none(), hod))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        // Asking for another department by id is ignored: the HOD's own is used
        ReportResult forced = catalog.run("student-headcount",
                new ReportFilters(null, 999L, null, null, null, null, null, null, null, null), hod);
        assertThat(forced.getScope()).contains("Department: Computing");
    }

    @Test
    void broadsheetCourseResultsAndAbsenteesAddUp() {
        ReportFilters f = new ReportFilters(session.getId(), null, cs.getId(), 200, 1, null, null, null, null, null);
        ReportResult b = catalog.run("broadsheet", f, hod);
        Map<String, Map<String, Object>> rows = new HashMap<>();
        table(b, "broadsheet").forEach(m -> rows.put((String) m.get("studentId"), m));
        String sKey = "s" + course.getCid(), gKey = "g" + course.getCid();
        assertThat(rows.get("10001").get(gKey)).isEqualTo("C");
        assertThat((BigDecimal) rows.get("10001").get(sKey)).isEqualByComparingTo("65");
        assertThat(rows.get("10001").get("remark")).isEqualTo("Pass");
        assertThat((BigDecimal) rows.get("10001").get("gpa")).isEqualByComparingTo("2.00");
        assertThat(rows.get("10002").get("remark")).isEqualTo("Refer: CS201");
        assertThat(rows.get("10002").get("carry")).isEqualTo("CS201");
        assertThat(table(catalog.run("broadsheet", ReportFilters.none(), hod), "broadsheet")).isEmpty();

        Map<String, Object> cr = table(catalog.run("course-results", filters(session.getId(), null, null), hod), "courses").get(0);
        assertThat(cr.get("registered")).isEqualTo(1);
        assertThat(cr.get("withResult")).isEqualTo(2);
        assertThat(cr.get("passed")).isEqualTo(1L);
        assertThat(cr.get("failed")).isEqualTo(1L);

        ReportResult abs = catalog.run("exam-absentees", filters(session.getId(), null, null), hod);
        Map<String, Object> q = table(abs, "quizzes").get(0);
        assertThat(q.get("quiz")).isEqualTo("Quiz 1");
        assertThat(q.get("expected")).isEqualTo(1);
        assertThat(q.get("absent")).isEqualTo(1);
        ReportResult drill = catalog.run("exam-absentees",
                new ReportFilters(session.getId(), null, null, null, null, null, null, null, pastQuiz.getqId(), null), hod);
        assertThat(table(drill, "absent")).extracting(m -> m.get("studentId")).containsExactly("10001");

        ReportResult top = catalog.run("top-performers", filters(session.getId(), null, null), hod);
        assertThat(table(top, "cgpa")).extracting(m -> m.get("studentId")).containsExactly("10001", "10002");
    }

    @Test
    void lecturerSeesOnlyTeachingReportsForTheirOwnCoursesAndQuizzes() throws Exception {
        List<ReportCatalog.Definition> defs = catalog.definitions(lecturer);
        assertThat(defs).extracting(ReportCatalog.Definition::key)
                .contains("quiz-results", "question-analysis", "course-assessment", "remark-requests", "course-registration",
                        "course-results", "exam-absentees")
                .doesNotContain("broadsheet", "student-headcount", "staff-workload", "academic-standing", "fee-debtors", "department-comparison");
        assertThat(defs).allSatisfy(d -> assertThat(d.filters()).noneMatch(x -> x.key().equals("department") || x.key().equals("program")));
        ReportCatalog.Filter quizPicker = defs.stream().filter(d -> d.key().equals("quiz-results")).findFirst().orElseThrow()
                .filters().get(0);
        assertThat(quizPicker.options()).extracting(ReportCatalog.Option::value)
                .containsExactlyInAnyOrder(String.valueOf(quiz.getqId()), String.valueOf(pastQuiz.getqId()));
        assertThat(catalog.definitions(otherLecturer).stream().filter(d -> d.key().equals("quiz-results")).findFirst().orElseThrow()
                .filters().get(0).options()).isEmpty();

        for (ReportCatalog.Definition d : defs) {
            ReportResult r = catalog.run(d.key(), new ReportFilters(session.getId(), null, null, null, null, null, null,
                    course.getCid(), quiz.getqId(), null), lecturer);
            assertThat(pdf.render(r)).as(d.key()).isNotEmpty();
        }
        // Someone else's quiz or course is refused; their reports show nothing of this lecturer's
        assertThatThrownBy(() -> catalog.run("quiz-results", quizFilter(quiz.getqId()), otherLecturer))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.run("course-assessment", courseFilter(course.getCid()), otherLecturer))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.run("broadsheet", ReportFilters.none(), lecturer))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(table(catalog.run("course-registration", ReportFilters.none(), otherLecturer), "courses")).isEmpty();
        assertThat(table(catalog.run("remark-requests", ReportFilters.none(), otherLecturer), "requests")).isEmpty();
    }

    @Test
    void teachingReportsAddUp() {
        ReportResult sheet = catalog.run("quiz-results", quizFilter(quiz.getqId()), lecturer);
        Map<String, Map<String, Object>> rows = new HashMap<>();
        table(sheet, "results").forEach(m -> rows.put((String) m.get("studentId"), m));
        assertThat(rows.get("10001").get("status")).isEqualTo("Submitted");
        assertThat(rows.get("10001").get("pct")).isEqualTo(72.0);
        assertThat(rows.get("10001").get("violations")).isEqualTo(1L);
        assertThat(rows.get("10002").get("status")).isEqualTo("Absent");
        assertThat(table(sheet, "distribution")).filteredOn(m -> "70–79%".equals(m.get("band")))
                .extracting(m -> m.get("students")).containsExactly(1L);

        ReportResult qa = catalog.run("question-analysis", quizFilter(quiz.getqId()), lecturer);
        Map<String, Object> q1 = table(qa, "objective").get(0);
        assertThat(q1.get("question")).isEqualTo("Which structure is LIFO?");
        assertThat(q1.get("correct")).isEqualTo(50.0);
        assertThat(q1.get("key")).isEqualTo("B");
        assertThat(q1.get("oB")).isEqualTo(50.0);
        assertThat(q1.get("oC")).isEqualTo(50.0);
        assertThat(q1.get("oA")).isEqualTo(0.0);

        ReportResult ca = catalog.run("course-assessment", courseFilter(course.getCid()), lecturer);
        List<Map<String, Object>> students = table(ca, "students");
        assertThat(students).extracting(m -> m.get("studentId")).containsExactly("10001");
        assertThat(students.get(0).get("average")).isEqualTo(72.0);
        assertThat(students.get(0).get("status")).isEqualTo("On track");
        assertThat(table(ca, "quizzes")).hasSize(1);
        assertThat(table(catalog.run("course-assessment", ReportFilters.none(), lecturer), "students")).isEmpty();

        ReportResult rr = catalog.run("remark-requests", ReportFilters.none(), lecturer);
        assertThat(table(rr, "requests")).extracting(m -> m.get("status")).containsExactly("Waiting");
    }

    @Test
    void questionPaperHasBothSectionsWithAndWithoutAnswers() throws Exception {
        Quiz exam = new Quiz();
        exam.setTitle("Final exam");
        exam.setQuizTime("60");
        exam.setQuizpassword("");
        exam.setQuizType(QuizType.BOTH);
        exam.setMaxMarks(30.0);
        exam.setCategory(course);
        exam.setQuizDate(LocalDate.now().plusDays(5));
        exam.setStartTime(LocalTime.of(9, 30));
        em.persist(exam);

        Questions mcq = new Questions();
        mcq.setContent("<p>Which structure is <b>LIFO</b>?</p><script>alert(1)</script>");
        mcq.setOption1("Queue");
        mcq.setOption2("Stack");
        mcq.setcorrect_answer(new String[]{"Stack"});
        mcq.setQuestionType(QuestionType.MCQ);
        mcq.setQuiz(exam);
        em.persist(mcq);
        Questions match = new Questions();
        match.setContent("Match each structure to its order");
        match.setQuestionType(QuestionType.MATCHING);
        match.setQuiz(exam);
        em.persist(match);
        em.persist(pair(match, "Stack", "LIFO", 0));
        em.persist(pair(match, "Queue", "FIFO", 1));
        Questions blank = new Questions();
        blank.setContent("The first node of a linked list is the ____.");
        blank.setcorrect_answer(new String[]{"head", "front"});
        blank.setQuestionType(QuestionType.FILL_BLANK);
        blank.setQuiz(exam);
        em.persist(blank);

        em.persist(theory(exam, "1a", "Define a tree.", "5", true, "A connected acyclic graph."));
        em.persist(theory(exam, "1b", "Give two uses.", "5", true, null));
        em.persist(theory(exam, "2", "Explain hashing.", "10", false, "Mapping keys to slots."));
        em.persist(new NumberOfTheoryToAnswer(null, 30, 1, exam));
        em.flush();
        em.clear();

        ReportFilters f = quizFilter(exam.getqId());
        ReportResult paper = catalog.run("question-paper", f, lecturer);
        Map<String, String> details = new HashMap<>();
        table(paper, "details").forEach(m -> details.put((String) m.get("label"), (String) m.get("value")));
        assertThat(details.get("Course code")).isEqualTo("CS201");
        assertThat(details.get("Quiz")).isEqualTo("Final exam");
        assertThat(details.get("Start time")).isEqualTo("09:30");
        assertThat(details.get("Duration")).isEqualTo("1 hr 30 min (Section A 1 hr, Section B 30 min)");
        assertThat(details.get("Section B")).isEqualTo("Answer 1 of 2 theory questions, 10 marks");
        assertThat(table(paper, "instructions")).extracting(m -> m.get("instruction"))
                .containsExactly("Answer all questions in Section A.", "Answer any one (1) question in Section B. Question 1 is compulsory.");
        QuestionPapers.Paper doc = (QuestionPapers.Paper) paper.getDocument();
        assertThat(doc.cover().timeAllowed()).isEqualTo("One (1) Hour Thirty (30) Minutes");
        assertThat(doc.cover().courseLine()).isEqualTo("CS201: Data Structures");
        assertThat(doc.cover().examLine()).isEqualTo("Final exam, 2026/2027 academic year");
        assertThat(doc.cover().examiner()).isEqualTo("Lect Test");
        assertThat(doc.cover().registrationCells()).hasSize(5);   // shaped like the students' numbers ("10001")
        assertThat(doc.sectionBHeading()).isEqualTo("Section B: Answer any one (1) question from this section. Question 1 is compulsory");
        assertThat(doc.theory().get(0).items()).extracting(QuestionPapers.TheoryItem::label).containsExactly("a)", "b)");
        assertThat(doc.theory().get(1).items()).extracting(QuestionPapers.TheoryItem::label).containsOnlyNulls();
        assertThat(hasNoTypeColumn(paper)).isTrue();
        List<Map<String, Object>> objective = table(paper, "objective");
        assertThat(objective).hasSize(3);
        assertThat(objective.get(0).get("question")).isEqualTo("Which structure is LIFO?");
        assertThat(objective.get(0)).doesNotContainKey("answer");
        assertThat((String) objective.get(1).get("question")).contains("(i) Stack", "A. FIFO", "B. LIFO");
        assertThat(table(paper, "theory")).extracting(m -> m.get("no")).containsExactly("1a", "1b", "2");
        assertThat(table(paper, "theory").get(0).keySet()).containsExactly("no", "question", "marks", "compulsory");
        assertThat(paper.isPrintable()).isTrue();

        ReportResult key = catalog.run("question-paper-answers", f, lecturer);
        List<Map<String, Object>> keyed = table(key, "objective");
        assertThat(keyed.get(0).get("answer")).isEqualTo("B");
        assertThat(keyed.get(1).get("answer")).isEqualTo("i = B, ii = A");
        assertThat(keyed.get(2).get("answer")).isEqualTo("head / front");
        // Theory questions are presented as set: no marking guide or answer on either version
        assertThat(table(key, "theory")).isEqualTo(table(paper, "theory"));

        // The printed paper: cover, Section A, Section B, drawn as real text
        List<String> pages = pdfPages(pdf.render(paper));
        assertThat(pages).hasSize(3);
        assertThat(pages.get(0)).contains("CS201: DATA STRUCTURES", "FINAL EXAM, 2026/2027 ACADEMIC YEAR", "One (1) Hour Thirty (30) Minutes",
                "Student Registration Number", "ANSWER ANY ONE (1) QUESTION IN SECTION B. QUESTION 1 IS COMPULSORY.", "Examiner(s): Lect Test")
                .doesNotContain("Page 1");
        assertThat(pages.get(1)).contains("Page 2 of 3", "1)", "a)", "Queue", "(i)", "Match with:", "Answer:");
        assertThat(pages.get(2)).contains("Page 3 of 3", "SECTION B", "Q1", "(Compulsory)", "Define a tree.", "[5 Marks]", "[10 Marks]");
        List<String> keyPages = pdfPages(pdf.render(key));
        assertThat(keyPages.get(0)).contains("ANSWER KEY").doesNotContain("Student Registration Number");
        assertThat(keyPages.get(1)).contains("(correct)", "= B", "Answer: head / front", "ANSWER KEY - CONFIDENTIAL");

        for (ReportResult r : List.of(paper, key)) {
            byte[] bytes = pdf.render(r);
            assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        }
        assertThatThrownBy(() -> catalog.run("question-paper-answers", f, otherLecturer))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private static boolean hasNoTypeColumn(ReportResult r) {
        return r.getTables().stream().filter(t -> t.getId().equals("objective")).findFirst().orElseThrow()
                .getColumns().stream().noneMatch(c -> c.key().equals("type"));
    }

    @Test
    void sectionAFillsTheLeftColumnThenTheRightThenANewPage() throws Exception {
        Quiz big = new Quiz();
        big.setTitle("Long quiz");
        big.setQuizTime("60");
        big.setQuizpassword("");
        big.setQuizType(QuizType.OBJ);
        big.setMaxMarks(50.0);
        big.setCategory(course);
        em.persist(big);
        for (int i = 1; i <= 50; i++) {
            Questions q = new Questions();
            q.setContent("Question number " + i + ": which of the following best describes the idea being tested here?");
            q.setOption1("The first option, of a typical length");
            q.setOption2("The second option, of a typical length");
            q.setOption3("The third option");
            q.setOption4("The fourth option");
            q.setcorrect_answer(new String[]{"The third option"});
            q.setQuestionType(QuestionType.MCQ);
            q.setQuiz(big);
            em.persist(q);
        }
        em.flush();
        em.clear();
        ReportResult r = catalog.run("question-paper", quizFilter(big.getqId()), lecturer);
        QuestionPapers.Paper doc = (QuestionPapers.Paper) r.getDocument();
        assertThat(doc.sectionAHeading()).startsWith("Section A: Choose the most appropriate answer");

        // Section A flows down the left column, then the right, then onto the next page: every
        // question appears once, in order, and no page after the cover is left without questions
        List<String> pages = pdfPages(pdf.render(r));
        assertThat(pages.size()).isBetween(4, 8);
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i < pages.size(); i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Question number (\\d+):").matcher(pages.get(i));
            int before = order.size();
            while (m.find()) order.add(Integer.parseInt(m.group(1)));
            assertThat(order.size()).as("page " + (i + 1) + " has questions").isGreaterThan(before);
            assertThat(pages.get(i)).contains("Page " + (i + 1) + " of " + pages.size());
        }
        assertThat(order).hasSize(50).isSorted().doesNotHaveDuplicates();
        for (String key : List.of("question-paper", "question-paper-answers")) {
            byte[] bytes = pdf.render(catalog.run(key, quizFilter(big.getqId()), lecturer));
            assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        }
    }

    @Test
    void coverShowsTheProgrammeEvenWhenTheCourseHasNone() throws Exception {
        // A course with no programmes of its own; one quiz names its programme, another relies on registrations
        Category general = new Category();
        general.setTitle("Communication Skills");
        general.setCourseCode("CMS101");
        general.setUser(lecturer);
        em.persist(general);
        Program cs = em.find(Program.class, course.getPrograms().iterator().next().getId());

        Quiz named = paperQuiz("Quiz with programme", general);
        named.setPrograms(new HashSet<>(Set.of(cs)));
        em.persist(named);
        Quiz viaRegistrations = paperQuiz("Quiz by registrations", general);
        em.persist(viaRegistrations);
        Registered_courses reg = new Registered_courses();
        reg.setUser(ama);
        reg.setCategory(general);
        reg.setSession(session);
        reg.setRegDate(new Date());
        em.persist(reg);
        em.flush();
        em.clear();

        for (Quiz q : List.of(named, viaRegistrations)) {
            ReportResult r = catalog.run("question-paper", quizFilter(q.getqId()), lecturer);
            QuestionPapers.Paper doc = (QuestionPapers.Paper) r.getDocument();
            assertThat(doc.cover().programme()).as(q.getTitle()).isEqualTo("Programme: Computer Science");
            assertThat(doc.cover().heading()).as(q.getTitle()).contains("Department of Computing");
            assertThat(pdfPages(pdf.render(r)).get(0)).contains("PROGRAMME: COMPUTER SCIENCE", "DEPARTMENT OF COMPUTING");
        }
    }

    private Quiz paperQuiz(String title, Category c) {
        Quiz q = new Quiz();
        q.setTitle(title);
        q.setQuizTime("30");
        q.setQuizpassword("");
        q.setQuizType(QuizType.OBJ);
        q.setMaxMarks(10.0);
        q.setCategory(c);
        q.setQuizDate(LocalDate.now().plusDays(3));
        return q;
    }

    /** Each page's text, as a reader would extract it (proves the PDF holds real text). */
    private static List<String> pdfPages(byte[] bytes) throws Exception {
        com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(bytes);
        com.lowagie.text.pdf.parser.PdfTextExtractor ex = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
        List<String> pages = new ArrayList<>();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) pages.add(ex.getTextFromPage(i));
        return pages;
    }

    private static MatchingPair pair(Questions q, String prompt, String answer, int order) {
        MatchingPair m = new MatchingPair();
        m.setQuestion(q);
        m.setPrompt(prompt);
        m.setAnswer(answer);
        m.setPairOrder(order);
        return m;
    }

    private static TheoryQuestions theory(Quiz q, String no, String text, String marks, boolean compulsory, String guide) {
        TheoryQuestions t = new TheoryQuestions();
        t.setQuiz(q);
        t.setQuesNo(no);
        t.setQuestion(text);
        t.setMarks(marks);
        t.setIsCompulsory(compulsory);
        t.setEvaluationCriteria(guide);
        return t;
    }

    private static ReportFilters quizFilter(Long quizId) {
        return new ReportFilters(null, null, null, null, null, null, null, null, quizId, null);
    }

    private static ReportFilters courseFilter(Long courseId) {
        return new ReportFilters(null, null, null, null, null, null, null, courseId, null, null);
    }

    private static StudentAnswer answer(User u, Questions q, String... chosen) {
        StudentAnswer a = new StudentAnswer();
        a.setUser(u);
        a.setQuestion(q);
        a.setSelectedOptions(chosen);
        return a;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ReportFilters filters(Long sessionId, Long courseId, String status) {
        return new ReportFilters(sessionId, null, null, null, null, null, null, courseId, null, status);
    }

    private static List<Map<String, Object>> table(ReportResult r, String id) {
        return r.getTables().stream().filter(t -> t.getId().equals(id)).findFirst().orElseThrow().getRows();
    }

    private static User user(String username, String first, Role role, Program p, Integer level, Integer semester) {
        User u = new User();
        u.setUsername(username);
        u.setFirstname(first);
        u.setLastname("Test");
        u.setEmail(username + "@example.com");
        u.setPassword("x");
        u.setRole(role);
        u.setEnabled(true);
        u.setProgram(p);
        u.setCurrentLevel(level);
        u.setCurrentSemester(semester);
        return u;
    }

    private QuizAttempt attempt(User u, AttemptStatus status, String voidReason) {
        QuizAttempt a = new QuizAttempt();
        a.setUser(u);
        a.setQuiz(quiz);
        a.setAttemptNumber(1);
        a.setStatus(status);
        a.setStartedAt(LocalDateTime.now());
        if (voidReason != null) {
            a.setVoidedAt(LocalDateTime.now());
            a.setVoidedByName("Root Test");
            a.setVoidReason(voidReason);
        }
        return a;
    }

    private ProctoringEvent event(User u, String type) {
        ProctoringEvent e = new ProctoringEvent();
        e.setUser(u);
        e.setQuiz(quiz);
        e.setType(type);
        e.setViolationNumber(1);
        return e;
    }

    private StudentCourseMark mark(User u, String score, String grade) {
        StudentCourseMark m = new StudentCourseMark();
        m.setSemesterSheet(sheet);
        m.setStudent(u);
        m.setCourse(course);
        m.setTotalScore(new BigDecimal(score));
        m.setGrade(grade);
        return m;
    }

    private static AuditLog audit(String action, User actor, String entityId) {
        AuditLog a = new AuditLog();
        a.setAction(action);
        a.setActorId(actor.getId());
        a.setActorName(actor.getFirstname() + " Test");
        a.setActorRole(actor.getRole().name());
        a.setHttpMethod("POST");
        a.setPath("/api/marks/sheet/" + entityId + "/approve");
        a.setEntityId(entityId);
        a.setStatusCode(200);
        return a;
    }
}
