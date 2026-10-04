package com.exam.service;

import com.exam.DTO.QuizTimerRequestDTO;
import com.exam.DTO.QuizTimerResponseDTO;
import com.exam.DTO.VoilationTimerRequestDTO;
import com.exam.model.QuizTimer;
import com.exam.model.exam.Quiz;
import com.exam.repository.NumberOfTheoryToAnswerRepository;
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
}
