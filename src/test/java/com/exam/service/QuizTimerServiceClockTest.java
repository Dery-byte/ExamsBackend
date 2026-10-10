package com.exam.service;

import com.exam.DTO.QuizTimerRequestDTO;
import com.exam.DTO.QuizTimerResponseDTO;
import com.exam.DTO.VoilationTimerRequestDTO;
import com.exam.DTO.ViolationTimerResponseDTO;
import com.exam.model.QuizTimer;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.Quiz;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.QuizTimerRepository;
import com.exam.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resume after a crash: the exam clock must never gain time, and (by default) keeps running
 * while the student is away.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizTimerServiceClockTest {

    @Mock QuizTimerRepository quizTimerRepository;
    @Mock UserRepository userRepository;
    @Mock QuizRepository quizRepository;
    @Mock NumberOfTheoryToAnswerRepository numberOfTheoryToAnswerRepository;
    @Mock SystemSettingService systemSettingService;
    @Mock QuizAttemptRepository quizAttemptRepository;
    @InjectMocks QuizTimerService service;

    private QuizTimer timer;

    @BeforeEach
    void setUp() {
        Quiz quiz = new Quiz();
        quiz.setQuizTime("60");                                   // 60-minute quiz
        when(quizRepository.findById(1L)).thenReturn(Optional.of(quiz));
        when(numberOfTheoryToAnswerRepository.findByQuiz_qId(1L)).thenReturn(List.of());
        when(quizTimerRepository.save(any(QuizTimer.class))).thenAnswer(i -> i.getArgument(0));

        timer = new QuizTimer();
        timer.setId(5L);
        timer.setQuiz(quiz);
        timer.setTotalViolationCount(0);
        when(quizTimerRepository.findByUserIdAndQuiz_qId(7L, 1L)).thenReturn(Optional.of(timer));
        when(quizTimerRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(timer));
        when(quizTimerRepository.insertIfAbsent(eq(7L), eq(1L), anyInt(), any())).thenReturn(0);   // row exists
        when(quizAttemptRepository.existsByUser_IdAndQuiz_qIdAndStatus(7L, 1L, AttemptStatus.IN_PROGRESS)).thenReturn(true);
    }

    private void clockRunsWhileAway(boolean on) {
        when(systemSettingService.getBooleanSetting(eq(SystemSettingService.EXAM_CLOCK_RUNS_WHILE_AWAY), anyBoolean())).thenReturn(on);
    }

    private static QuizTimerRequestDTO request(int seconds) {
        QuizTimerRequestDTO r = new QuizTimerRequestDTO();
        r.setRemainingTime(seconds);
        return r;
    }

    @Test
    void timeAwayIsSubtractedOnResume() {
        clockRunsWhileAway(true);
        timer.setRemainingTime(1200);
        timer.setUpdatedAt(LocalDateTime.now().minusSeconds(300));   // away for 5 minutes

        QuizTimerResponseDTO res = service.getQuizTimer(7L, 1L);

        assertThat(res.getStatus()).isEqualTo("saved");
        assertThat(res.getRemainingTime()).isBetween(898, 900);
    }

    @Test
    void reportsExpiredWhenTimeRanOutWhileAway() {
        clockRunsWhileAway(true);
        timer.setRemainingTime(120);
        timer.setUpdatedAt(LocalDateTime.now().minusMinutes(10));

        QuizTimerResponseDTO res = service.getQuizTimer(7L, 1L);

        assertThat(res.getStatus()).isEqualTo("expired");
        assertThat(res.getRemainingTime()).isZero();
    }

    @Test
    void clockResumesFromCheckpointWhenSettingIsOff() {
        clockRunsWhileAway(false);
        timer.setRemainingTime(1200);
        timer.setUpdatedAt(LocalDateTime.now().minusMinutes(30));

        QuizTimerResponseDTO res = service.getQuizTimer(7L, 1L);

        assertThat(res.getStatus()).isEqualTo("saved");
        assertThat(res.getRemainingTime()).isEqualTo(1200);
    }

    @Test
    void checkpointCannotAddTimeBack() {
        clockRunsWhileAway(false);
        timer.setRemainingTime(600);
        timer.setUpdatedAt(LocalDateTime.now());

        QuizTimerResponseDTO res = service.saveQuizTimer(7L, 1L, request(3000));   // client claims 50 minutes left

        assertThat(res.getRemainingTime()).isLessThanOrEqualTo(605);            // 600 + 5 s network tolerance
    }

    @Test
    void normalCountdownIsAccepted() {
        clockRunsWhileAway(true);
        timer.setRemainingTime(600);
        timer.setUpdatedAt(LocalDateTime.now().minusSeconds(15));

        QuizTimerResponseDTO res = service.saveQuizTimer(7L, 1L, request(585));

        assertThat(res.getRemainingTime()).isEqualTo(585);
    }

    @Test
    void firstCheckpointIsAcceptedWhenThisSaveCreatedTheRow() {
        clockRunsWhileAway(true);
        when(quizTimerRepository.insertIfAbsent(eq(7L), eq(1L), anyInt(), any())).thenReturn(1);
        timer.setRemainingTime(3590);
        timer.setUpdatedAt(LocalDateTime.now());

        QuizTimerResponseDTO res = service.saveQuizTimer(7L, 1L, request(3590));

        assertThat(res.getRemainingTime()).isEqualTo(3590);
    }

    @Test
    void violationBeforeFirstCheckpointSeedsTheFullDuration() {
        clockRunsWhileAway(true);
        when(quizTimerRepository.findByUserIdAndQuiz_qId(7L, 1L)).thenReturn(Optional.empty());
        VoilationTimerRequestDTO violation = new VoilationTimerRequestDTO();
        violation.setTotalViolationCount(1);

        service.saveViolationCount(1L, 7L, violation);

        // Seeding with 0 would clamp every later checkpoint to 5 s and expire the exam
        verify(quizTimerRepository).insertIfAbsent(eq(7L), eq(1L), eq(3600), any());
    }

    @Test
    void lateViolationAfterSubmissionDoesNotRecreateTheRow() {
        when(quizTimerRepository.findByUserIdAndQuiz_qId(7L, 1L)).thenReturn(Optional.empty());
        when(quizAttemptRepository.existsByUser_IdAndQuiz_qIdAndStatus(7L, 1L, AttemptStatus.IN_PROGRESS)).thenReturn(false);

        int count = service.recordViolationCount(1L, 7L, 2);

        assertThat(count).isZero();
        verify(quizTimerRepository, never()).insertIfAbsent(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void violationCountNeverGoesDown() {
        timer.setTotalViolationCount(3);

        int count = service.recordViolationCount(1L, 7L, 2);   // a retried, older save arriving late

        assertThat(count).isEqualTo(3);
        assertThat(timer.getTotalViolationCount()).isEqualTo(3);
    }

    @Test
    void violationSavesLeaveTheExamClockCheckpointAlone() {
        LocalDateTime checkpoint = LocalDateTime.now().minusSeconds(40);
        timer.setRemainingTime(1200);
        timer.setUpdatedAt(checkpoint);

        service.recordViolationCount(1L, 7L, 1);
        VoilationTimerRequestDTO delay = new VoilationTimerRequestDTO();
        delay.setViolationDelayTime(30);
        service.saveViolationDelayTime(1L, 7L, delay);

        // Moving updatedAt would hand the student back the 40 s since the checkpoint
        assertThat(timer.getUpdatedAt()).isEqualTo(checkpoint);
    }

    private void lockOutRunsWhileAway(boolean on) {
        when(systemSettingService.getBooleanSetting(eq(SystemSettingService.EXAM_LOCKOUT_RUNS_WHILE_AWAY), anyBoolean())).thenReturn(on);
    }

    @Test
    void lockOutIsSavedOnceAndResumesFromTheServer() {
        lockOutRunsWhileAway(true);
        VoilationTimerRequestDTO delay = new VoilationTimerRequestDTO();
        delay.setViolationDelayTime(60);
        service.saveViolationDelayTime(1L, 7L, delay);

        // e.g. signing in on another device 20 s later
        timer.setViolationDelayUntil(timer.getViolationDelayUntil().minusSeconds(20));
        ViolationTimerResponseDTO res = service.getViolationDelayTime(1L, 7L);

        assertThat(res.getViolationDelayTime()).isBetween(39, 40);
    }

    @Test
    void lockOutPausesWhileAwayEvenWhenTheExamClockRuns() {
        clockRunsWhileAway(true);
        lockOutRunsWhileAway(false);
        VoilationTimerRequestDTO delay = new VoilationTimerRequestDTO();
        delay.setViolationDelayTime(45);                 // what was left as the page closed
        service.saveViolationDelayTime(1L, 7L, delay);

        // Back 10 minutes later, with a fresh exam clock checkpoint from the queue
        timer.setViolationDelayUntil(timer.getViolationDelayUntil().minusSeconds(600));
        timer.setUpdatedAt(LocalDateTime.now());
        ViolationTimerResponseDTO res = service.getViolationDelayTime(1L, 7L);

        assertThat(res.getViolationDelayTime()).isEqualTo(45);
        assertThat(res.getPausesWhileAway()).isTrue();
    }

    @Test
    void lockOutRunsWhileAwayWhenSwitchedOnEvenIfTheExamClockPauses() {
        clockRunsWhileAway(false);
        lockOutRunsWhileAway(true);
        LocalDateTime now = LocalDateTime.now();
        timer.setViolationDelayTime(60);
        timer.setViolationDelayUntil(now.plusSeconds(60 - 600));   // started 10 minutes ago
        timer.setUpdatedAt(now.minusSeconds(600 - 15));

        ViolationTimerResponseDTO res = service.getViolationDelayTime(1L, 7L);

        assertThat(res.getViolationDelayTime()).isZero();
        assertThat(res.getPausesWhileAway()).isFalse();
    }

    @Test
    void clearedLockOutStaysClearedWhilePaused() {
        lockOutRunsWhileAway(false);
        timer.setViolationDelayTime(20);
        timer.setViolationDelayUntil(LocalDateTime.now().plusSeconds(20));
        VoilationTimerRequestDTO done = new VoilationTimerRequestDTO();
        done.setViolationDelayTime(0);                    // served in full on the page
        service.saveViolationDelayTime(1L, 7L, done);

        assertThat(service.getViolationDelayTime(1L, 7L).getViolationDelayTime()).isZero();
    }

    @Test
    void noLockOutWhenNoneWasStarted() {
        lockOutRunsWhileAway(true);
        assertThat(service.getViolationDelayTime(1L, 7L).getViolationDelayTime()).isZero();
        when(quizTimerRepository.findByUserIdAndQuiz_qId(7L, 1L)).thenReturn(Optional.empty());
        assertThat(service.getViolationDelayTime(1L, 7L).getViolationDelayTime()).isZero();
    }
}
