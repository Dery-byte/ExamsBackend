package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.AttemptStatus;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.QuizAttempt;
import com.exam.model.examops.ProctoringEvent;
import com.exam.repository.ProctoringEventRepository;
import com.exam.repository.QuizAttemptRepository;
import com.exam.repository.QuizRepository;
import com.exam.repository.ReportRepository;
import com.exam.repository.UserRepository;
import com.exam.service.QuizTimerService;
import com.exam.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Who may reset a student's violations, and how the proctoring report counts after a reset. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProctoringResetTest {

    @Mock ProctoringEventRepository eventRepository;
    @Mock QuizRepository quizRepository;
    @Mock ReportRepository reportRepository;
    @Mock QuizAttemptRepository quizAttemptRepository;
    @Mock UserRepository userRepository;
    @Mock QuizTimerService quizTimerService;
    @Mock SystemSettingService systemSettingService;
    @InjectMocks ProctoringService service;

    private Quiz quiz;
    private User lecturer;
    private User student;
    private final List<ProctoringEvent> saved = new ArrayList<>();

    private static User user(long id, Role role, String first) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setFirstname(first);
        u.setUsername(first.toLowerCase());
        return u;
    }

    @BeforeEach
    void setUp() {
        lecturer = user(2L, Role.LECTURER, "Lect");
        student = user(7L, Role.NORMAL, "Ama");
        quiz = new Quiz();
        quiz.setqId(1L);
        quiz.setTitle("Midterm");
        quiz.setMaxViolations(3);
        quiz.setUser(lecturer);                 // the lecturer set this quiz
        when(quizRepository.findById(1L)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(7L)).thenReturn(Optional.of(student));
        when(quizAttemptRepository.existsByUser_IdAndQuiz_qIdAndStatus(7L, 1L, AttemptStatus.IN_PROGRESS)).thenReturn(true);
        when(quizTimerService.resetViolations(eq(1L), eq(7L), anyInt())).thenReturn(1);
        when(eventRepository.save(any(ProctoringEvent.class))).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        allow(SystemSettingService.VIOLATION_RESET_ADMIN, true);
        allow(SystemSettingService.VIOLATION_RESET_LECTURER, true);
    }

    private void allow(String key, boolean on) {
        when(systemSettingService.getBooleanSetting(eq(key), anyBoolean())).thenReturn(on);
    }

    @Test
    void lecturerResetsWithAReasonAndTheResetIsRecorded() {
        Map<String, Object> out = service.resetViolations(lecturer, 1L, 7L, "  Keyboard closed on iPhone  ");

        assertThat(out.get("resetNo")).isEqualTo(1);
        verify(quizTimerService).resetViolations(1L, 7L, 0);
        assertThat(saved).hasSize(1);
        ProctoringEvent e = saved.get(0);
        assertThat(e.getType()).isEqualTo(ProctoringService.RESET_TYPE);
        assertThat(e.getActorName()).isEqualTo("Lect");
        assertThat(e.getNote()).isEqualTo("Keyboard closed on iPhone");
        assertThat(e.getUser()).isSameAs(student);
    }

    @Test
    void aReasonIsRequired() {
        assertThatThrownBy(() -> service.resetViolations(lecturer, 1L, 7L, "  ok "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resetViolations(lecturer, 1L, 7L, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(quizTimerService, never()).resetViolations(anyLong(), anyLong(), anyInt());
    }

    @Test
    void staffCanSetTheCountToANumber() {
        Map<String, Object> out = service.resetViolations(lecturer, 1L, 7L, "Two were the keyboard", 1);

        verify(quizTimerService).resetViolations(1L, 7L, 1);
        assertThat(out.get("count")).isEqualTo(1);
        assertThat(saved.get(0).getViolationNumber()).isEqualTo(1);   // the reset event keeps the number
    }

    @Test
    void theSuperAdminSwitchDecidesForLecturers() {
        allow(SystemSettingService.VIOLATION_RESET_LECTURER, false);
        assertThatThrownBy(() -> service.resetViolations(lecturer, 1L, 7L, "Phone keyboard"))
                .isInstanceOf(AccessDeniedException.class);
        verify(quizTimerService, never()).resetViolations(anyLong(), anyLong(), anyInt());
    }

    @Test
    void theSuperAdminSwitchDecidesForHods() {
        User hod = user(3L, Role.ADMIN, "Hod");
        allow(SystemSettingService.VIOLATION_RESET_ADMIN, false);
        assertThat(service.mayResetViolations(hod)).isFalse();
        allow(SystemSettingService.VIOLATION_RESET_ADMIN, true);
        assertThat(service.mayResetViolations(hod)).isTrue();
    }

    @Test
    void theSuperAdminAlwaysMay() {
        allow(SystemSettingService.VIOLATION_RESET_ADMIN, false);
        allow(SystemSettingService.VIOLATION_RESET_LECTURER, false);
        assertThat(service.mayResetViolations(user(1L, Role.SUPER_ADMIN, "Sa"))).isTrue();
    }

    @Test
    void aLecturerWhoDoesNotTeachTheQuizCannot() {
        User other = user(9L, Role.LECTURER, "Other");
        assertThatThrownBy(() -> service.resetViolations(other, 1L, 7L, "Phone keyboard"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void notAfterTheStudentHasSubmitted() {
        when(quizAttemptRepository.existsByUser_IdAndQuiz_qIdAndStatus(7L, 1L, AttemptStatus.IN_PROGRESS)).thenReturn(false);
        assertThatThrownBy(() -> service.resetViolations(lecturer, 1L, 7L, "Phone keyboard"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStudentPageCannotSendAResetEvent() {
        assertThatThrownBy(() -> service.record(student, 1L, "violations-reset", null, "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProctoringEvent event(String type, Integer number, int minutesAgo, boolean beforeReset) {
        ProctoringEvent e = new ProctoringEvent();
        e.setUser(student);
        e.setQuiz(quiz);
        e.setType(type);
        e.setViolationNumber(number);
        e.setOccurredAt(LocalDateTime.now().minusMinutes(minutesAgo));
        if (beforeReset) e.setBeforeReset(true);
        return e;
    }

    @Test
    void theReportCountsOnlyViolationsSinceTheLatestReset() {
        ProctoringEvent reset = event(ProctoringService.RESET_TYPE, null, 20, false);
        reset.setActorName("Lect");
        reset.setNote("Keyboard closed on iPhone");
        when(eventRepository.findByQuiz_qIdOrderByOccurredAtAsc(1L)).thenReturn(List.of(
                event("focus-lost", 1, 40, false),
                event("focus-lost", 2, 35, false),
                event("visibility-hidden", 3, 30, false),
                reset,
                event("focus-lost", 4, 19, true),        // sent before the page heard of the reset
                event("visibility-hidden", 1, 10, false)));
        when(reportRepository.findByQuiz_qId(1L)).thenReturn(List.of());
        QuizAttempt sitting = new QuizAttempt();
        sitting.setUser(student);
        sitting.setStatus(AttemptStatus.IN_PROGRESS);
        when(quizAttemptRepository.findAllForQuiz(1L)).thenReturn(List.of(sitting));

        Map<String, Object> report = service.quizReport(lecturer, 1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) report.get("students")).get(0);
        assertThat(row.get("violations")).isEqualTo(1L);
        assertThat(row.get("violationsBeforeReset")).isEqualTo(4L);
        assertThat(row.get("flag")).isEqualTo("WATCH");
        assertThat(row.get("inProgress")).isEqualTo(true);
        assertThat(row.get("lastResetBy")).isEqualTo("Lect");
        assertThat(row.get("lastResetReason")).isEqualTo("Keyboard closed on iPhone");
        assertThat(report.get("canResetViolations")).isEqualTo(true);
    }

    @Test
    void aResetToANumberCountsOnFromThatNumber() {
        ProctoringEvent reset = event(ProctoringService.RESET_TYPE, 1, 20, false);   // set to 1
        when(eventRepository.findByQuiz_qIdOrderByOccurredAtAsc(1L)).thenReturn(List.of(
                event("focus-lost", 1, 40, false),
                event("focus-lost", 2, 35, false),
                event("visibility-hidden", 3, 30, false),
                reset,
                event("visibility-hidden", 2, 10, false)));
        when(reportRepository.findByQuiz_qId(1L)).thenReturn(List.of());
        when(quizAttemptRepository.findAllForQuiz(1L)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) service.quizReport(lecturer, 1L).get("students")).get(0);
        assertThat(row.get("violations")).isEqualTo(2L);
        assertThat(row.get("violationsBeforeReset")).isEqualTo(3L);
        assertThat(row.get("lastResetTo")).isEqualTo(1);
    }
}
