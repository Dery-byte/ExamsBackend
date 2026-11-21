package com.exam.service;

import com.exam.DTO.ViolationTimerResponseDTO;
import com.exam.DTO.VoilationTimerRequestDTO;
import com.exam.model.QuizTimer;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.Quiz;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.QuizTimerRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Staff reset of a student's violations: the count goes back to 0 and saves stamped with an older
 * reset number (still queued on the student's device, or sent before the page heard of the reset)
 * can't bring it back or restart a lock-out.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizTimerViolationResetTest {

    @Mock QuizTimerRepository quizTimerRepository;
    @Mock QuizRepository quizRepository;
    @Mock NumberOfTheoryToAnswerRepository numberOfTheoryToAnswerRepository;
    @Mock SystemSettingService systemSettingService;
    @Mock QuizAttemptRepository quizAttemptRepository;
    @InjectMocks QuizTimerService service;

    private QuizTimer timer;

    @BeforeEach
    void setUp() {
        Quiz quiz = new Quiz();
        quiz.setQuizTime("60");
        when(quizRepository.findById(1L)).thenReturn(Optional.of(quiz));
        when(numberOfTheoryToAnswerRepository.findByQuiz_qId(1L)).thenReturn(List.of());
        when(quizTimerRepository.save(any(QuizTimer.class))).thenAnswer(i -> i.getArgument(0));

        timer = new QuizTimer();
        timer.setId(5L);
        timer.setQuiz(quiz);
        timer.setRemainingTime(1800);
        timer.setUpdatedAt(LocalDateTime.now());
        timer.setTotalViolationCount(0);
        when(quizTimerRepository.findByUserIdAndQuiz_qId(7L, 1L)).thenReturn(Optional.of(timer));
        when(quizTimerRepository.findForUpdate(7L, 1L)).thenReturn(Optional.of(timer));
        when(quizAttemptRepository.existsByUser_IdAndQuiz_qIdAndStatus(7L, 1L, AttemptStatus.IN_PROGRESS)).thenReturn(true);
    }

    private static VoilationTimerRequestDTO lockOut(int seconds, Integer resetNo) {
        VoilationTimerRequestDTO r = new VoilationTimerRequestDTO();
        r.setViolationDelayTime(seconds);
        r.setViolationResetNo(resetNo);
        return r;
    }

    @Test
    void resetClearsTheCountAndTheLockOutAndRaisesTheResetNumber() {
        service.recordViolationCount(1L, 7L, 3, 0);
        service.saveViolationDelayTime(1L, 7L, lockOut(90, 0));

        int resetNo = service.resetViolations(1L, 7L);

        assertThat(resetNo).isEqualTo(1);
        assertThat(timer.getTotalViolationCount()).isZero();
        assertThat(timer.getViolationDelayUntil()).isNull();
        ViolationTimerResponseDTO state = service.getViolationState(1L, 7L);
        assertThat(state.getTotalViolationCount()).isZero();
        assertThat(state.getViolationResetNo()).isEqualTo(1);
        assertThat(state.getViolationDelayTime()).isZero();
    }

    @Test
    void aViolationFromBeforeTheResetDoesNotCount() {
        service.recordViolationCount(1L, 7L, 3, 0);
        service.resetViolations(1L, 7L);

        // The student's page still thinks it is at 3 and sends number 4 with the old reset number
        QuizTimerService.ViolationState st = service.recordViolationCount(1L, 7L, 4, 0);

        assertThat(st.counted()).isFalse();
        assertThat(st.count()).isZero();
        assertThat(st.resetNo()).isEqualTo(1);
        assertThat(timer.getTotalViolationCount()).isZero();
    }

    @Test
    void aPageFromBeforeResetsExistedCountsAsResetNumberZero() {
        service.resetViolations(1L, 7L);
        assertThat(service.recordViolationCount(1L, 7L, 5, null).counted()).isFalse();
        assertThat(service.recordViolationCount(1L, 7L, 5)).isZero();
    }

    @Test
    void violationsAfterTheResetCountFromOne() {
        service.recordViolationCount(1L, 7L, 3, 0);
        service.resetViolations(1L, 7L);

        QuizTimerService.ViolationState st = service.recordViolationCount(1L, 7L, 1, 1);

        assertThat(st.counted()).isTrue();
        assertThat(st.count()).isEqualTo(1);
        assertThat(timer.getTotalViolationCount()).isEqualTo(1);
    }

    @Test
    void aLockOutQueuedBeforeTheResetDoesNotStartAgain() {
        service.resetViolations(1L, 7L);

        ViolationTimerResponseDTO res = service.saveViolationDelayTime(1L, 7L, lockOut(60, 0));

        assertThat(res.getViolationDelayTime()).isZero();
        assertThat(res.getViolationResetNo()).isEqualTo(1);
        assertThat(timer.getViolationDelayUntil()).isNull();
    }

    @Test
    void aLockOutAfterTheResetIsSaved() {
        service.resetViolations(1L, 7L);

        service.saveViolationDelayTime(1L, 7L, lockOut(60, 1));

        assertThat(timer.getViolationDelayUntil()).isNotNull();
    }

    @Test
    void clockCheckpointTellsThePageAboutTheReset() {
        service.resetViolations(1L, 7L);
        service.resetViolations(1L, 7L);
        assertThat(service.getQuizTimer(7L, 1L).getViolationResetNo()).isEqualTo(2);
        assertThat(service.getViolationCount(1L, 7L).getViolationResetNo()).isEqualTo(2);
    }

    @Test
    void theCountCanBeSetToALowerNumber() {
        service.recordViolationCount(1L, 7L, 3, 0);

        service.resetViolations(1L, 7L, 1);

        assertThat(timer.getTotalViolationCount()).isEqualTo(1);
        // The page, told the count is 1, sends its next violation as number 2
        QuizTimerService.ViolationState st = service.recordViolationCount(1L, 7L, 2, 1);
        assertThat(st.counted()).isTrue();
        assertThat(st.count()).isEqualTo(2);
    }

    @Test
    void theCountCanOnlyGoDown() {
        service.recordViolationCount(1L, 7L, 2, 0);

        assertThatThrownBy(() -> service.resetViolations(1L, 7L, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resetViolations(1L, 7L, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resetViolations(1L, 7L, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(timer.getTotalViolationCount()).isEqualTo(2);
        assertThat(timer.getViolationResetNo()).isZero();
    }

    @Test
    void nothingToResetOnceTheAttemptIsOverAndNoRowIsLeft() {
        when(quizTimerRepository.findByUserIdAndQuiz_qId(7L, 1L)).thenReturn(Optional.empty());
        when(quizTimerRepository.findForUpdate(7L, 1L)).thenReturn(Optional.empty());
        when(quizAttemptRepository.existsByUser_IdAndQuiz_qIdAndStatus(7L, 1L, AttemptStatus.IN_PROGRESS)).thenReturn(false);

        assertThatThrownBy(() -> service.resetViolations(1L, 7L)).isInstanceOf(IllegalArgumentException.class);
    }
}
