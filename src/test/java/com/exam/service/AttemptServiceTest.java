package com.exam.service;

import com.exam.DTO.AttemptStatusDTO;
import com.exam.model.QuizStatus;
import com.exam.model.QuizType;
import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * State-machine tests for the attempt ledger, run against in-memory fake repositories.
 * (Row locking and the DB unique constraint are what make this race-safe in production; these
 * tests cover the rules that decide who may start, submit and retake.)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttemptServiceTest {

    @Mock QuizAttemptRepository      attempts;
    @Mock UserRepository             users;
    @Mock QuizRepository             quizzes;
    @Mock ReportRepository           reports;
    @Mock QuizTimerRepository        timers;
    @Mock UserQuizProgressRepository quizProgress;
    @Mock TheoryProgressRepository   theoryProgress;

    @InjectMocks AttemptService service;

    final List<QuizAttempt> store = new ArrayList<>();
    final List<Report> reportStore = new ArrayList<>();

    User student;
    User owner;      // lecturer who created the quiz
    User stranger;   // another lecturer
    Quiz quiz;

    @BeforeEach
    void setUp() {
        student  = user(1L, Role.NORMAL, "Ada", "Student");
        owner    = user(2L, Role.LECTURER, "Lee", "Owner");
        stranger = user(3L, Role.LECTURER, "Sam", "Stranger");

        quiz = new Quiz();
        quiz.setqId(10L);
        quiz.setQuizType(QuizType.OBJ);
        quiz.setMaxAttempts(1);
        quiz.setUser(owner);

        when(users.lockById(anyLong())).thenAnswer(i -> Optional.of(student));
        when(quizzes.findById(10L)).thenReturn(Optional.of(quiz));
        when(attempts.save(any(QuizAttempt.class))).thenAnswer(i -> {
            QuizAttempt a = i.getArgument(0);
            if (store.stream().noneMatch(x -> x == a)) store.add(a);
            return a;
        });
        when(attempts.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(anyLong(), anyLong())).thenAnswer(i ->
                store.stream()
                        .filter(a -> a.getUser().getId().equals(i.getArgument(0)) && a.getQuiz().getqId().equals(i.getArgument(1)))
                        .sorted(Comparator.comparingInt(QuizAttempt::getAttemptNumber))
                        .toList());
        when(reports.findByUser_IdAndQuiz_qId(anyLong(), anyLong())).thenAnswer(i -> reportStore.stream()
                .filter(r -> r.getUser().getId().equals(i.getArgument(0)) && r.getQuiz().getqId().equals(i.getArgument(1)))
                .toList());
    }

    // ── begin ────────────────────────────────────────────────────────────────

    @Test
    void firstBeginStartsAttemptOneAndIsIdempotent() {
        AttemptStatusDTO s1 = service.begin(student, quiz);
        AttemptStatusDTO s2 = service.begin(student, quiz);   // e.g. page reload / two fetches at once

        assertThat(store).hasSize(1);
        assertThat(s1.activeAttemptNumber()).isEqualTo(1);
        assertThat(s2.activeAttemptNumber()).isEqualTo(1);
        assertThat(s2.attemptsUsed()).isEqualTo(1);
        assertThat(s2.attemptsRemaining()).isZero();
        // the very first attempt must not wipe an in-flight pre-existing session
        verifyNoInteractions(timers, quizProgress, theoryProgress);
    }

    @Test
    void singleAttemptQuizRefusesASecondAttemptAfterSubmit() {
        service.begin(student, quiz);
        service.recordObjective(student, quiz, new BigDecimal("7.5"));

        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
        assertConflict(() -> service.begin(student, quiz));
        assertThat(store).hasSize(1);
    }

    @Test
    void replayedSubmitCannotBurnTheNextAttempt() {
        quiz.setMaxAttempts(2);
        service.begin(student, quiz);
        service.recordObjective(student, quiz, BigDecimal.TEN);

        // a network retry / double click of the same submission
        assertConflict(() -> service.recordObjective(student, quiz, BigDecimal.TEN));

        assertThat(store).hasSize(1);                                   // attempt 2 was NOT started by the replay
        assertThat(service.myStatus(student, quiz).attemptsRemaining()).isEqualTo(1);
    }

    @Test
    void multipleAttemptsAreAllKeptWithTheirOwnMarks() {
        quiz.setMaxAttempts(3);
        for (int n = 1; n <= 3; n++) {
            service.begin(student, quiz);
            service.recordObjective(student, quiz, new BigDecimal(n * 10));
        }

        assertThat(store).extracting(QuizAttempt::getAttemptNumber).containsExactly(1, 2, 3);
        assertThat(store).extracting(QuizAttempt::getMarksA)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"));
        assertConflict(() -> service.begin(student, quiz));             // limit reached
        assertThat(store).hasSize(3);
    }

    @Test
    void secondAttemptStartsWithACleanSession() {
        quiz.setMaxAttempts(2);
        service.begin(student, quiz);
        service.recordObjective(student, quiz, BigDecimal.ONE);

        service.begin(student, quiz);   // attempt 2

        verify(timers).deleteByUserIdAndQuiz_qId(1L, 10L);
        verify(quizProgress).deleteByUserIdAndQuizId(1L, 10L);
        verify(theoryProgress).deleteByUserAndQuiz(student, quiz);
    }

    @Test
    void closedQuizBlocksNewAttemptsButNotResuming() {
        service.begin(student, quiz);            // attempt in progress
        quiz.setStatus(QuizStatus.CLOSED);
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(1);   // resume is fine

        service.recordObjective(student, quiz, BigDecimal.ONE);
        quiz.setMaxAttempts(2);
        assertConflict(() -> service.begin(student, quiz));                             // but no new attempt
    }

    // ── submitting ───────────────────────────────────────────────────────────

    @Test
    void submitWithoutBeginAutoStartsOnlyTheFirstAttempt() {
        service.recordObjective(student, quiz, BigDecimal.ONE);   // e.g. a session that began before this feature
        assertThat(store).hasSize(1);
        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
    }

    @Test
    void bothTypeQuizNeedsBothSectionsAndEachOnlyOnce() {
        quiz.setQuizType(QuizType.BOTH);
        service.begin(student, quiz);

        service.recordObjective(student, quiz, new BigDecimal("6"));
        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);       // theory still to come
        assertConflict(() -> service.recordObjective(student, quiz, new BigDecimal("9"))); // objective can't be resubmitted

        service.recordTheory(student, quiz, new BigDecimal("14"));
        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(store.get(0).getMarksA()).isEqualByComparingTo("6");
        assertThat(store.get(0).getMarksB()).isEqualByComparingTo("14");
    }

    @Test
    void finishClosesAnAttemptWhoseSectionWasSkipped() {
        quiz.setQuizType(QuizType.BOTH);
        quiz.setMaxAttempts(2);
        service.begin(student, quiz);
        service.recordTheory(student, quiz, new BigDecimal("5"));       // no objective questions in this quiz
        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.IN_PROGRESS);

        service.finish(student, quiz);

        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(2);
    }

    @Test
    void theoryPrecheckRejectsBeforeAnLlmCallIsMade() {
        quiz.setQuizType(QuizType.BOTH);
        service.begin(student, quiz);
        service.assertTheoryMaySubmit(student, quiz);                    // fine the first time
        service.recordTheory(student, quiz, BigDecimal.ONE);             // attempt stays IN_PROGRESS (objective pending)

        assertConflict(() -> service.assertTheoryMaySubmit(student, quiz));   // a resend is refused up-front
    }

    // ── retake ───────────────────────────────────────────────────────────────

    @Test
    void retakeVoidsTheLatestAttemptKeepsItAndFreesASlot() {
        service.begin(student, quiz);
        service.recordObjective(student, quiz, new BigDecimal("4"));

        AttemptStatusDTO s = service.grantRetake(10L, 1L, owner, "network failure");

        assertThat(store).hasSize(1);   // kept for audit
        QuizAttempt voided = store.get(0);
        assertThat(voided.getStatus()).isEqualTo(AttemptStatus.VOIDED);
        assertThat(voided.getVoidReason()).isEqualTo("network failure");
        assertThat(voided.getVoidedById()).isEqualTo(2L);
        assertThat(voided.getMarksA()).isEqualByComparingTo("4");        // old marks retained in the ledger
        assertThat(s.attemptsUsed()).isZero();
        verify(timers, atLeastOnce()).deleteByUserIdAndQuiz_qId(1L, 10L);

        // the student can now start a fresh attempt; numbering never reuses a voided number
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(2);
        service.recordObjective(student, quiz, new BigDecimal("9"));
        assertConflict(() -> service.begin(student, quiz));               // and only one retake was granted
    }

    @Test
    void statusFlagsAPendingRetakeOnlyUntilItIsStarted() {
        quiz.setMaxAttempts(2);
        service.begin(student, quiz);
        service.recordObjective(student, quiz, BigDecimal.ONE);
        assertThat(service.myStatus(student, quiz).retakeGranted()).isFalse();   // ordinary 2nd attempt: "Start attempt 2 of 2"

        service.begin(student, quiz);
        service.recordObjective(student, quiz, BigDecimal.ONE);                  // limit reached
        assertThat(service.myStatus(student, quiz).retakeGranted()).isFalse();

        AttemptStatusDTO granted = service.grantRetake(10L, 1L, owner, null);
        assertThat(granted.retakeGranted()).isTrue();                            // shown as "Retake quiz"
        assertThat(service.myStatus(student, quiz).retakeGranted()).isTrue();
        assertThat(service.myStatus(student, quiz).canStart()).isTrue();

        AttemptStatusDTO started = service.begin(student, quiz);                 // student starts the retake
        assertThat(started.retakeGranted()).isFalse();                           // now it is just an attempt in progress
        assertThat(started.activeAttemptNumber()).isEqualTo(3);
        service.recordObjective(student, quiz, BigDecimal.TEN);
        assertThat(service.myStatus(student, quiz).retakeGranted()).isFalse();   // and once submitted, no longer pending
    }

    @Test
    void retakeOfAnInProgressAttemptGivesAFreshStart() {
        service.begin(student, quiz);   // crashed mid-exam, nothing submitted
        service.grantRetake(10L, 1L, owner, null);

        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.VOIDED);
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(2);
    }

    @Test
    void retakeIsRefusedOnceTheResultHasBeenReviewed() {
        service.begin(student, quiz);
        service.recordObjective(student, quiz, new BigDecimal("4"));

        Report report = new Report();
        report.setUser(student);
        report.setQuiz(quiz);
        report.setMarks(new BigDecimal("4"));
        report.setIsReviewed(true);
        reportStore.add(report);

        assertConflict(() -> service.grantRetake(10L, 1L, owner, null));
        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.SUBMITTED);   // nothing was voided

        report.setIsReviewed(false);                                               // un-reviewing makes it possible again
        assertThat(service.grantRetake(10L, 1L, owner, null).attemptsUsed()).isZero();
    }

    @Test
    void reviewedResultBlocksNewAttemptsEvenWhenAttemptsAreLeft() {
        quiz.setMaxAttempts(3);
        service.begin(student, quiz);
        service.recordObjective(student, quiz, new BigDecimal("4"));
        assertThat(service.myStatus(student, quiz).canStart()).isTrue();          // 2 attempts left, not reviewed yet

        Report report = new Report();
        report.setUser(student);
        report.setQuiz(quiz);
        report.setIsReviewed(true);
        reportStore.add(report);

        AttemptStatusDTO status = service.myStatus(student, quiz);
        assertThat(status.resultReviewed()).isTrue();
        assertThat(status.attemptsRemaining()).isEqualTo(2);                       // attempts remain on paper...
        assertThat(status.canStart()).isFalse();                                   // ...but none can be started
        assertConflict(() -> service.begin(student, quiz));
        assertThat(store).hasSize(1);                                              // no new attempt row was created

        report.setIsReviewed(false);                                               // un-reviewing re-opens it
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(2);
    }

    @Test
    void reviewDoesNotStopAStudentResumingAnAttemptAlreadyInProgress() {
        quiz.setMaxAttempts(2);
        service.begin(student, quiz);                                              // attempt 1 in progress

        Report report = new Report();
        report.setUser(student);
        report.setQuiz(quiz);
        report.setIsReviewed(true);
        reportStore.add(report);

        AttemptStatusDTO resumed = service.begin(student, quiz);
        assertThat(resumed.activeAttemptNumber()).isEqualTo(1);
        assertThat(resumed.canStart()).isTrue();
    }

    @Test
    void retakeIsRefusedWhileTheStudentStillHasAttemptsLeft() {
        quiz.setMaxAttempts(3);
        service.begin(student, quiz);
        service.recordObjective(student, quiz, BigDecimal.ONE);

        assertConflict(() -> service.grantRetake(10L, 1L, owner, null));
        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
    }

    @Test
    void retakeNeedsSomethingToRetake() {
        assertThatThrownBy(() -> service.grantRetake(10L, 1L, owner, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── authorization ────────────────────────────────────────────────────────

    @Test
    void onlyStaffWhoManageTheQuizMayGrantRetakes() {
        service.begin(student, quiz);
        service.recordObjective(student, quiz, BigDecimal.ONE);

        assertForbidden(() -> service.grantRetake(10L, 1L, stranger, null));   // another lecturer
        assertForbidden(() -> service.grantRetake(10L, 1L, student, null));    // the student themselves
        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.SUBMITTED);

        User superAdmin = user(9L, Role.SUPER_ADMIN, "Root", "Admin");
        assertThat(service.grantRetake(10L, 1L, superAdmin, null).attemptsUsed()).isZero();
    }

    @Test
    void hodMayManageOnlyQuizzesOfTheirDepartment() {
        Department csDept = Department.builder().id(100L).name("CS").code("CS").build();
        Department eeDept = Department.builder().id(200L).name("EE").code("EE").build();
        quiz.setUser(null);
        quiz.getPrograms().add(Program.builder().id(1L).name("CS BSc").code("CS").durationYears(4).department(csDept).build());

        User csHod = user(20L, Role.ADMIN, "Cs", "Hod");
        csHod.setDepartment(csDept);
        User eeHod = user(21L, Role.ADMIN, "Ee", "Hod");
        eeHod.setDepartment(eeDept);

        service.begin(student, quiz);
        service.recordObjective(student, quiz, BigDecimal.ONE);

        assertForbidden(() -> service.grantRetake(10L, 1L, eeHod, null));
        assertThat(service.grantRetake(10L, 1L, csHod, null).attemptsUsed()).isZero();
    }

    @Test
    void onlyStudentsCanBeGivenRetakes() {
        User anotherLecturer = user(5L, Role.LECTURER, "Not", "Student");
        when(users.lockById(5L)).thenReturn(Optional.of(anotherLecturer));
        assertThatThrownBy(() -> service.grantRetake(10L, 5L, owner, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── results that pre-date attempt tracking ───────────────────────────────

    @Test
    void legacyReportCountsAsAnAttemptAndCanBeRetaken() {
        Report old = new Report();
        old.setUser(student);
        old.setQuiz(quiz);
        old.setMarks(new BigDecimal("12.5"));
        reportStore.add(old);

        assertThat(service.myStatus(student, quiz).attemptsUsed()).isEqualTo(1);   // visible without writing anything
        assertThat(store).isEmpty();
        assertConflict(() -> service.begin(student, quiz));                        // limit 1 already used
        assertThat(store).hasSize(1);                                              // legacy attempt materialised
        assertThat(store.get(0).getMarksA()).isEqualByComparingTo("12.5");

        service.grantRetake(10L, 1L, owner, null);
        assertThat(service.begin(student, quiz).activeAttemptNumber()).isEqualTo(2);
    }

    @Test
    void replayedSubmitOnALegacyResultIsRefused() {
        Report old = new Report();
        old.setUser(student);
        old.setQuiz(quiz);
        reportStore.add(old);

        assertConflict(() -> service.recordObjective(student, quiz, BigDecimal.TEN));
        assertThat(store).hasSize(1);
        assertThat(store.get(0).getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
    }

    @Test
    void quizWithNoLimitSetBehavesAsSingleAttempt() {
        quiz.setMaxAttempts(null);   // quizzes created before this feature
        service.begin(student, quiz);
        service.recordObjective(student, quiz, BigDecimal.ONE);
        assertConflict(() -> service.begin(student, quiz));
    }

    // ── lecturer edits keep the ledger in step ───────────────────────────────

    @Test
    void lecturerEditToOfficialMarksIsMirroredOnTheCountedAttempt() {
        service.begin(student, quiz);
        service.recordObjective(student, quiz, new BigDecimal("5"));

        Report edited = new Report();
        edited.setUser(student);
        edited.setQuiz(quiz);
        edited.setMarks(new BigDecimal("5"));
        edited.setMarksB(new BigDecimal("18"));
        service.syncOfficialMarks(edited);

        assertThat(store.get(0).getMarksB()).isEqualByComparingTo("18");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static User user(long id, Role role, String first, String last) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setFirstname(first);
        u.setLastname(last);
        u.setUsername(first.toLowerCase());
        return u;
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
