package com.exam.service;

import com.exam.DTO.AttemptStatusDTO;
import com.exam.model.QuizType;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.Category;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.QuizAttempt;
import com.exam.model.exam.Registered_courses;
import com.exam.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real JPA layer on an in-memory H2 database (never the real DB). This proves what the
 * fake-repository tests cannot: the new entity maps, every new JPQL query is valid, the row lock
 * query runs, and the DB itself rejects a duplicate attempt number.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // Explicit in-memory H2. @TestPropertySource outranks application.yml, so the real (MySQL/RDS) datasource is never used.
        // NON_KEYWORDS=USER because `user` is reserved in H2 (not in MySQL).
        "spring.datasource.url=jdbc:h2:mem:attempts;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
})
@Import({AttemptService.class, QuizService.class, QuizDeletionService.class, com.exam.service.admin.MaintenanceService.class,
        com.exam.service.SystemSettingService.class})
class AttemptServiceJpaTest {

    /** QuizService notifies students when a quiz goes live; not under test here. */
    @org.springframework.boot.test.mock.mockito.MockBean com.exam.service.comms.NotificationService notificationService;

    @Autowired AttemptService        service;
    @Autowired QuizAttemptRepository attempts;
    @Autowired UserRepository        users;
    @Autowired QuizRepository        quizzes;
    @Autowired StudentAnswerRepository studentAnswers;
    @Autowired AnswerRepository      answers;
    @Autowired CategoryRepository    categories;
    @Autowired Registered_coursesRepository registrations;
    @Autowired ReportRepository      reports;

    User student;
    User admin;
    Quiz quiz;
    Category course;

    @BeforeEach
    void setUp() {
        student = users.save(user("ada", Role.NORMAL));
        admin   = users.save(user("root", Role.SUPER_ADMIN));

        Category c = new Category();
        c.setTitle("Data Structures");
        c.setCourseCode("CS201");
        course = categories.save(c);

        Quiz q = new Quiz();
        q.setTitle("Mid-sem");
        q.setQuizTime("30");
        q.setQuizpassword("");   // no access code: these tests are about the attempt lifecycle (see passwordGate… below)
        q.setQuizType(QuizType.OBJ);
        q.setMaxAttempts(2);
        q.setActive(true);
        q.setCategory(course);
        quiz = quizzes.save(q);

        Registered_courses reg = new Registered_courses();
        reg.setUser(student);
        reg.setCategory(course);
        registrations.save(reg);
    }

    @Test
    void unenrolledStudentIsBlockedByTheRealEnrollmentCheck() {
        User outsider = users.save(user("bo", Role.NORMAL));   // never registered for `course`

        assertThatThrownBy(() -> service.begin(outsider, quiz))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThat(attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(outsider.getId(), quiz.getqId())).isEmpty();

        // registering fixes it — proves the check reads live enrollment, not a cached copy
        Registered_courses reg = new Registered_courses();
        reg.setUser(outsider);
        reg.setCategory(course);
        registrations.save(reg);
        assertThat(service.begin(outsider, quiz).activeAttemptNumber()).isEqualTo(1);
    }

    @Test
    void fullLifecycleAgainstTheRealJpaLayer() {
        // attempt 1
        AttemptStatusDTO s = service.begin(student, quiz);
        assertThat(s.activeAttemptNumber()).isEqualTo(1);
        service.recordObjective(student, quiz, new BigDecimal("6.5"));

        // attempt 2 (fresh-session resets run real delete queries)
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(2);
        service.recordObjective(student, quiz, new BigDecimal("8.0"));

        // limit reached
        assertThatThrownBy(() -> service.begin(student, quiz)).isInstanceOf(ResponseStatusException.class);

        // both attempts stored separately
        List<QuizAttempt> rows = attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(student.getId(), quiz.getqId());
        assertThat(rows).extracting(QuizAttempt::getAttemptNumber).containsExactly(1, 2);
        assertThat(rows.get(0).getMarksA()).isEqualByComparingTo("6.5");
        assertThat(rows.get(1).getMarksA()).isEqualByComparingTo("8.0");

        // retake voids the latest and frees exactly one slot
        service.grantRetake(quiz.getqId(), student.getId(), admin, "power cut");
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(3);

        List<QuizAttempt> all = attempts.findAllForQuiz(quiz.getqId());   // staff view: student fetched in the same query
        assertThat(all).hasSize(3);
        assertThat(all).extracting(QuizAttempt::getStatus)
                .containsExactly(AttemptStatus.SUBMITTED, AttemptStatus.VOIDED, AttemptStatus.IN_PROGRESS);
        assertThat(all.get(1).getVoidedByName()).isEqualTo(admin.getFullName());
        assertThat(all.get(1).getMarksA()).isEqualByComparingTo("8.0");   // voided attempt's marks are kept
    }

    @Test
    void autoOpenPublishesADueDraftQuizOnFirstStudentAccessAgainstTheRealDb() {
        Quiz draft = new Quiz();
        draft.setTitle("Pop quiz");
        draft.setQuizTime("10");
        draft.setQuizpassword("");
        draft.setQuizType(QuizType.OBJ);
        draft.setMaxAttempts(1);
        draft.setCategory(course);
        draft.setActive(false);
        draft.setAutoOpen(true);
        draft.setQuizDate(java.time.LocalDate.now());
        draft.setStartTime(java.time.LocalTime.now().minusMinutes(1));   // due a minute ago
        draft = quizzes.save(draft);

        assertThat(service.begin(student, draft).activeAttemptNumber()).isEqualTo(1);

        Quiz reloaded = quizzes.findById(draft.getqId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getStatus().name()).isEqualTo("OPEN");
    }

    @Test
    void databaseRejectsADuplicateAttemptNumber() {
        attempts.saveAndFlush(attempt(1));
        assertThatThrownBy(() -> attempts.saveAndFlush(attempt(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newQueriesAreValidAndRunnable() {
        // Hibernate parses every @Query at startup; running them proves the JPQL semantics too.
        assertThat(users.lockById(student.getId())).isPresent();
        assertThat(users.lockById(-1L)).isEmpty();
        studentAnswers.deleteByStudentAndQuiz(student.getId(), quiz.getqId());
        attempts.deleteByQuizId(quiz.getqId());
        assertThat(attempts.findAllForQuiz(quiz.getqId())).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private QuizAttempt attempt(int number) {
        QuizAttempt a = new QuizAttempt();
        a.setUser(student);
        a.setQuiz(quiz);
        a.setAttemptNumber(number);
        a.setStartedAt(LocalDateTime.now());
        return a;
    }

    @Autowired com.exam.repository.QuizUnlockRepository unlocks;

    private void unlock(User u, Quiz q) {
        com.exam.model.examops.QuizUnlock x = new com.exam.model.examops.QuizUnlock();
        x.setUser(u);
        x.setQuiz(q);
        unlocks.save(x);
    }

    @Test
    void passwordGateRequiresAFreshUnlockForEachNewAttemptButNotToResume() throws Exception {
        quiz.setQuizpassword("s3cret");
        quizzes.save(quiz);

        assertThatThrownBy(() -> service.begin(student, quiz))            // no unlock yet
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));

        unlock(student, quiz);
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(1);
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(1);   // resume after a crash: no new unlock needed
        service.recordObjective(student, quiz, new BigDecimal("5"));

        Thread.sleep(5);                                                   // unlock timestamps must be after the attempt start
        assertThatThrownBy(() -> service.begin(student, quiz))            // the old unlock was used by attempt 1
                .isInstanceOf(ResponseStatusException.class);
        unlock(student, quiz);
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(2);
    }

    @Test
    void takenQuizIdsCoverSubmittedAttemptsAndLegacyResultsOnly() {
        Quiz inProgress = quizzes.save(sibling("In progress"));
        Quiz legacy     = quizzes.save(sibling("Legacy"));
        Quiz untouched  = quizzes.save(sibling("Never taken"));

        service.begin(student, quiz);
        service.recordObjective(student, quiz, new BigDecimal("4"));   // OBJ quiz: one section, so the attempt is SUBMITTED
        service.begin(student, inProgress);                             // started, never submitted

        com.exam.model.exam.Report r = new com.exam.model.exam.Report();   // result from before the attempt ledger
        r.setUser(student);
        r.setQuiz(legacy);
        r.setMarks(new BigDecimal("7"));
        reports.save(r);

        java.util.Set<Long> taken = new java.util.HashSet<>(reports.findQuizIdsWithReports());
        taken.addAll(attempts.findQuizIdsWithStatus(AttemptStatus.SUBMITTED));
        assertThat(taken).contains(quiz.getqId(), legacy.getqId())
                .doesNotContain(inProgress.getqId(), untouched.getqId());
    }

    // ── Question pool ────────────────────────────────────────────────────────

    @Autowired QuestionsRepository questionRepo;
    @Autowired QuizService         quizService;

    private void fillPool(Quiz q, int count) {
        for (int i = 0; i < count; i++) {
            com.exam.model.exam.Questions x = new com.exam.model.exam.Questions();
            x.setQuiz(q);
            x.setContent("Question " + i);
            x.setOption1("a");
            x.setOption2("b");
            x.setcorrect_answer(new String[]{"a"});
            x.setDifficulty(i % 2 == 0 ? "EASY" : "HARD");
            questionRepo.save(x);
        }
    }

    private void makePool(Quiz q, int each) {
        q.setQuestionPool(true);
        q.setNumberOfQuestions(each);
        quizzes.save(q);
    }

    private static java.security.Principal as(String username) {
        return () -> username;
    }

    /** What the student's objective-questions endpoint draws (null = every question in the quiz). */
    private List<Long> drawn(String username) {
        return service.beginAndDrawIfStudent(as(username), quiz.getqId()).drawn();
    }

    @Test
    void poolQuizDrawsOnceAndShowsTheSameQuestionsOnEveryLoad() {
        fillPool(quiz, 20);
        makePool(quiz, 6);

        List<Long> first = drawn("ada");
        assertThat(first).hasSize(6).doesNotHaveDuplicates();
        assertThat(questionRepo.findAllById(first)).allMatch(x -> x.getQuiz().getqId().equals(quiz.getqId()));
        // Balanced: the pool is half easy, half hard
        assertThat(questionRepo.findAllById(first)).filteredOn(x -> "EASY".equals(x.getDifficulty())).hasSize(3);

        // A reload, the theory endpoint (begin) or another device: same questions, same order
        assertThat(drawn("ada")).isEqualTo(first);
        service.begin(student, quiz);
        assertThat(service.drawnForActiveAttempt(student, quiz)).isEqualTo(first);
        assertThat(attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(student.getId(), quiz.getqId())).hasSize(1);
    }

    @Test
    void theOptionOrderSeedIsTheAttemptSoItHoldsOnReloadAndChangesOnARetake() {
        fillPool(quiz, 4);   // no pool: every question, but still one seed per attempt
        Long first = service.beginAndDrawIfStudent(as("ada"), quiz.getqId()).attemptId();
        assertThat(first).isNotNull();
        assertThat(service.beginAndDrawIfStudent(as("ada"), quiz.getqId()).attemptId()).isEqualTo(first);
        service.recordObjective(student, quiz, new BigDecimal("2"));
        assertThat(service.beginAndDrawIfStudent(as("ada"), quiz.getqId()).attemptId()).isNotEqualTo(first);
    }

    @Test
    void eachNewAttemptGetsItsOwnDraw() {
        fillPool(quiz, 40);
        makePool(quiz, 10);

        List<Long> first = drawn("ada");
        service.recordObjective(student, quiz, new BigDecimal("4"));
        assertThat(service.drawnForActiveAttempt(student, quiz)).isNull();             // nothing in progress now
        assertThat(service.drawnForLatestAttempt(student.getId(), quiz.getqId())).isEqualTo(first);

        List<Long> second = drawn("ada");
        assertThat(second).hasSize(10);
        List<QuizAttempt> rows = attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(student.getId(), quiz.getqId());
        assertThat(rows).extracting(QuizAttempt::getServedQuestionIds)
                .containsExactly(com.exam.helper.QuestionDraw.join(first), com.exam.helper.QuestionDraw.join(second));
    }

    @Test
    void startingTheAttemptFixesTheDrawBeforeAnyQuestionIsShown() {
        fillPool(quiz, 12);
        makePool(quiz, 5);
        service.begin(student, quiz);
        List<Long> drawn = service.drawnForActiveAttempt(student, quiz);
        assertThat(drawn).hasSize(5);
        assertThat(drawn("ada")).isEqualTo(drawn);
    }

    @Test
    void staffAndPoolFreeQuizzesSaveNoDraw() {
        fillPool(quiz, 8);
        assertThat(drawn("ada")).isNull();   // no pool: every question
        assertThat(attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(student.getId(), quiz.getqId()))
                .extracting(QuizAttempt::getServedQuestionIds).containsExactly((String) null);

        makePool(quiz, 3);
        assertThat(service.beginAndDrawIfStudent(as("root"), quiz.getqId())).isNull();  // staff preview
        assertThat(service.beginAndDrawIfStudent(null, quiz.getqId())).isNull();
        assertThat(attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(admin.getId(), quiz.getqId())).isEmpty();
    }

    @Test
    void anEmptyPoolDrawsNothingUntilQuestionsAreAdded() {
        makePool(quiz, 5);
        assertThat(drawn("ada")).isEmpty();
        assertThat(service.drawnForActiveAttempt(student, quiz)).isNull();

        fillPool(quiz, 3);   // fewer than a full draw: the student gets what there is
        assertThat(drawn("ada")).hasSize(3);
        assertThat(service.drawnForActiveAttempt(student, quiz)).hasSize(3);
    }

    private com.exam.DTO.QuizUpdateRequest edit(boolean live, Boolean pool, Integer each) {
        com.exam.DTO.QuizUpdateRequest r = new com.exam.DTO.QuizUpdateRequest();
        r.setqId(quiz.getqId());
        r.setActive(live);
        r.setQuestionPool(pool);
        r.setNumberOfQuestions(each);
        return r;
    }

    /**
     * The saved state before an edit. A refused edit rolls back in the app, but this test shares one
     * persistence context, so each check sets the starting state it describes.
     */
    private void savedAs(boolean pool, boolean live, Integer each) {
        quiz.setQuestionPool(pool);
        quiz.setActive(live);
        quiz.setNumberOfQuestions(each);
        quizzes.save(quiz);
    }

    @Test
    void savingAQuizEnforcesThePoolRules() {
        // A pool needs the number each student gets
        savedAs(false, false, null);
        assertThatThrownBy(() -> quizService.updateQuiz(edit(false, true, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Number of Questions");

        // Live with too few questions: refused; as a draft: fine
        fillPool(quiz, 3);
        savedAs(false, false, null);
        assertThatThrownBy(() -> quizService.updateQuiz(edit(true, true, 5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has only 3");
        savedAs(false, false, null);
        assertThat(quizService.updateQuiz(edit(false, true, 5)).isQuestionPool()).isTrue();

        // Going live once the pool holds a full draw; null leaves the pool setting as it is
        fillPool(quiz, 9);   // 12 in the pool
        assertThat(quizService.updateQuiz(edit(true, null, null)).isActive()).isTrue();
        assertThat(quizzes.findById(quiz.getqId()).orElseThrow().isQuestionPool()).isTrue();

        // Raising the draw past the pool while live: refused
        assertThatThrownBy(() -> quizService.updateQuiz(edit(true, null, 13)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Add 1 more");

        // Not switched while a student is sitting it
        savedAs(true, true, 5);
        service.begin(student, quiz);
        assertThatThrownBy(() -> quizService.updateQuiz(edit(true, false, 5)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        savedAs(true, true, 5);
        service.recordObjective(student, quiz, new BigDecimal("3"));

        // Off again only when the quiz fits without the pool
        assertThatThrownBy(() -> quizService.updateQuiz(edit(true, false, 5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Delete 7");
        savedAs(true, true, 5);
        assertThat(quizService.updateQuiz(edit(true, false, 12)).isQuestionPool()).isFalse();
    }

    private Quiz sibling(String title) {
        Quiz q = new Quiz();
        q.setTitle(title);
        q.setQuizTime("30");
        q.setQuizpassword("");
        q.setQuizType(QuizType.OBJ);
        q.setMaxAttempts(1);
        q.setActive(true);
        q.setCategory(course);
        return q;
    }

    private static User user(String name, Role role) {
        User u = new User();
        u.setFirstname(name);
        u.setLastname("Test");
        u.setEmail(name + "@example.com");
        u.setUsername(name);
        u.setRole(role);
        return u;
    }
}
