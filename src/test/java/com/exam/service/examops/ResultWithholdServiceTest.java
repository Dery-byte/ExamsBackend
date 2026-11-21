package com.exam.service.examops;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.QuizAttempt;
import com.exam.model.exam.Report;
import com.exam.model.examops.ResultWithhold;
import com.exam.repository.*;
import com.exam.service.ReportEmailService;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Withholding one student's quiz result: who may, what students see, and what waits until release. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResultWithholdServiceTest {

    @Mock ResultWithholdRepository withholdRepository;
    @Mock QuizRepository quizRepository;
    @Mock UserRepository userRepository;
    @Mock ReportRepository reportRepository;
    @Mock QuizAttemptRepository quizAttemptRepository;
    @Mock SystemSettingService systemSettingService;
    @Mock ReportEmailService reportEmailService;
    @Mock NotificationService notificationService;
    @Spy ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @InjectMocks ResultWithholdService service;

    private User lecturer;
    private User student;
    private Quiz quiz;

    private static User user(long id, Role role, String first) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setFirstname(first);
        u.setUsername(first.toLowerCase());
        return u;
    }

    private void allow(String key, boolean on) {
        when(systemSettingService.getBooleanSetting(eq(key), anyBoolean())).thenReturn(on);
    }

    @BeforeEach
    void setUp() {
        lecturer = user(2L, Role.LECTURER, "Lect");
        student = user(7L, Role.NORMAL, "Ama");
        quiz = new Quiz();
        quiz.setqId(1L);
        quiz.setTitle("Midterm");
        quiz.setUser(lecturer);
        when(quizRepository.findById(1L)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(7L)).thenReturn(Optional.of(student));
        when(quizAttemptRepository.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(7L, 1L)).thenReturn(List.of(new QuizAttempt()));
        allow(SystemSettingService.RESULT_WITHHOLD_ADMIN, true);
        allow(SystemSettingService.RESULT_WITHHOLD_LECTURER, true);
    }

    // ── Who may ──

    @Test
    void theSuperAdminAlwaysMayAndTheSwitchesDecideForHodsAndLecturers() {
        User hod = user(3L, Role.ADMIN, "Hod");
        allow(SystemSettingService.RESULT_WITHHOLD_ADMIN, false);
        allow(SystemSettingService.RESULT_WITHHOLD_LECTURER, false);
        assertThat(service.mayWithhold(user(1L, Role.SUPER_ADMIN, "Sa"))).isTrue();
        assertThat(service.mayWithhold(hod)).isFalse();
        assertThat(service.mayWithhold(lecturer)).isFalse();
        allow(SystemSettingService.RESULT_WITHHOLD_ADMIN, true);
        allow(SystemSettingService.RESULT_WITHHOLD_LECTURER, true);
        assertThat(service.mayWithhold(hod)).isTrue();
        assertThat(service.mayWithhold(lecturer)).isTrue();
        assertThat(service.mayWithhold(student)).isFalse();
    }

    @Test
    void aLecturerWithTheSwitchOffIsRefused() {
        allow(SystemSettingService.RESULT_WITHHOLD_LECTURER, false);
        assertThatThrownBy(() -> service.withhold(lecturer, 1L, 7L, "Took 60 minutes"))
                .isInstanceOf(AccessDeniedException.class);
        verify(withholdRepository, never()).save(any());
    }

    @Test
    void aLecturerWhoDoesNotTeachTheQuizIsRefused() {
        assertThatThrownBy(() -> service.withhold(user(9L, Role.LECTURER, "Other"), 1L, 7L, "Took 60 minutes"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Withhold ──

    @Test
    void withholdingSavesTheReasonAndWho() {
        service.withhold(lecturer, 1L, 7L, "  Took 60 minutes on a 45 minute quiz  ");

        ArgumentCaptor<ResultWithhold> saved = ArgumentCaptor.forClass(ResultWithhold.class);
        verify(withholdRepository).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("Took 60 minutes on a 45 minute quiz");
        assertThat(saved.getValue().getWithheldByName()).isEqualTo("Lect");
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getStudent()).isSameAs(student);
    }

    @Test
    void aReasonIsRequired() {
        assertThatThrownBy(() -> service.withhold(lecturer, 1L, 7L, " ok ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.withhold(lecturer, 1L, 7L, null)).isInstanceOf(IllegalArgumentException.class);
        verify(withholdRepository, never()).save(any());
    }

    @Test
    void onlyAStudentWhoTookTheQuiz() {
        when(quizAttemptRepository.findByUser_IdAndQuiz_qIdOrderByAttemptNumberAsc(7L, 1L)).thenReturn(List.of());
        when(reportRepository.findByUser_IdAndQuiz_qId(7L, 1L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.withhold(lecturer, 1L, 7L, "Took 60 minutes")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notTwice() {
        when(withholdRepository.existsByQuiz_qIdAndStudent_IdAndActiveTrue(1L, 7L)).thenReturn(true);
        assertThatThrownBy(() -> service.withhold(lecturer, 1L, 7L, "Took 60 minutes")).isInstanceOf(IllegalArgumentException.class);
    }

    // ── What a student sees ──

    private Report report(Quiz q, BigDecimal a, BigDecimal b) {
        Report r = new Report();
        r.setUser(student);
        r.setQuiz(q);
        r.setMarks(a);
        r.setMarksB(b);
        r.setIsReviewed(true);
        return r;
    }

    @Test
    void aStudentSeesWithheldInPlaceOfTheMarksAndOtherResultsAsUsual() {
        Quiz other = new Quiz();
        other.setqId(2L);
        other.setTitle("Quiz 2");
        when(withholdRepository.findActiveQuizIdsForStudent(7L)).thenReturn(List.of(1L));
        Report held = report(quiz, new BigDecimal("14"), new BigDecimal("20"));
        Report fine = report(other, new BigDecimal("9"), new BigDecimal("11"));

        List<?> seen = service.forViewer(student, List.of(held, fine));

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) seen.get(0);
        assertThat(masked.get("withheld")).isEqualTo(true);
        assertThat(masked.get("marks")).isNull();
        assertThat(masked.get("marksB")).isNull();
        assertThat(masked.get("grade")).isNull();
        assertThat(masked.get("percentage")).isNull();
        assertThat(masked.get("withheldMessage")).isEqualTo(ResultWithholdService.STUDENT_MESSAGE);
        assertThat(String.valueOf(masked)).doesNotContain("Took");          // the staff reason is never there
        assertThat(seen.get(1)).isSameAs(fine);
    }

    @Test
    void staffSeeEverything() {
        when(withholdRepository.findActiveQuizIdsForStudent(7L)).thenReturn(List.of(1L));
        Report held = report(quiz, new BigDecimal("14"), new BigDecimal("20"));
        assertThat(service.forViewer(lecturer, List.of(held)).get(0)).isSameAs(held);
    }

    @Test
    void aStudentCannotOpenAWithheldResultButStaffCan() {
        when(withholdRepository.existsByQuiz_qIdAndStudent_IdAndActiveTrue(1L, 7L)).thenReturn(true);
        assertThatThrownBy(() -> service.requireVisible(student, 7L, 1L))
                .isInstanceOf(ResultWithholdService.ResultWithheldException.class)
                .hasMessage(ResultWithholdService.STUDENT_MESSAGE);
        service.requireVisible(lecturer, 7L, 1L);   // no exception
        // A classmate asking for this student's result is refused too
        assertThatThrownBy(() -> service.requireVisible(user(8L, Role.NORMAL, "Kofi"), 7L, 1L))
                .isInstanceOf(ResultWithholdService.ResultWithheldException.class);
    }

    // ── Release ──

    private ResultWithhold active() {
        ResultWithhold w = new ResultWithhold();
        w.setQuiz(quiz);
        w.setStudent(student);
        w.setReason("Took 60 minutes");
        return w;
    }

    @Test
    void releasingAReviewedResultSendsTheEmailAndNoticeThatWaited() {
        ResultWithhold w = active();
        when(withholdRepository.findFirstByQuiz_qIdAndStudent_IdAndActiveTrue(1L, 7L)).thenReturn(Optional.of(w));
        Report r = report(quiz, BigDecimal.ONE, BigDecimal.ONE);
        when(reportRepository.findTopByUser_IdAndQuiz_qIdOrderBySubmissionDateDesc(7L, 1L)).thenReturn(Optional.of(r));

        Map<String, Object> out = service.release(lecturer, 1L, 7L);

        assertThat(w.isActive()).isFalse();
        assertThat(w.getReleasedByName()).isEqualTo("Lect");
        assertThat(out.get("notified")).isEqualTo(true);
        verify(reportEmailService).sendReviewedReport(student, quiz);
        verify(notificationService).resultReleased(student, quiz);
    }

    @Test
    void releasingBeforeReviewSendsNothingYet() {
        when(withholdRepository.findFirstByQuiz_qIdAndStudent_IdAndActiveTrue(1L, 7L)).thenReturn(Optional.of(active()));
        Report r = report(quiz, BigDecimal.ONE, BigDecimal.ONE);
        r.setIsReviewed(false);
        when(reportRepository.findTopByUser_IdAndQuiz_qIdOrderBySubmissionDateDesc(7L, 1L)).thenReturn(Optional.of(r));

        service.release(lecturer, 1L, 7L);

        verify(reportEmailService, never()).sendReviewedReport(any(), any());
        verify(notificationService, never()).resultReleased(any(), any());
    }

    @Test
    void releasingWhatIsNotWithheldIsRefused() {
        when(withholdRepository.findFirstByQuiz_qIdAndStudent_IdAndActiveTrue(1L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.release(lecturer, 1L, 7L)).isInstanceOf(IllegalArgumentException.class);
    }
}
