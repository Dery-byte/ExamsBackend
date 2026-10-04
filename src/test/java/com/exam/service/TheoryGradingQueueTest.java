package com.exam.service;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.exam.GeminiRequest;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.TheoryGradingJob;
import com.exam.model.exam.TheoryGradingJob.Status;
import com.exam.repository.QuizRepository;
import com.exam.repository.TheoryGradingJobRepository;
import com.exam.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Background theory marking: submit returns at once, failures retry, refusals stop. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TheoryGradingQueueTest {

    @Mock TheoryGradingJobRepository jobs;
    @Mock UserRepository users;
    @Mock QuizRepository quizzes;
    @Mock AttemptService attemptService;
    @Mock SubjectiveEvaluationService evaluation;

    TheoryGradingQueue queue;
    User student;
    Quiz quiz;
    GeminiRequest request = new GeminiRequest("quizId 1: tqid 3: Question Number 1a: Answer: x Marks: 10 Criteria: -");

    @BeforeEach
    void setUp() {
        queue = new TheoryGradingQueue(2);
        ReflectionTestUtils.setField(queue, "jobs", jobs);
        ReflectionTestUtils.setField(queue, "users", users);
        ReflectionTestUtils.setField(queue, "quizzes", quizzes);
        ReflectionTestUtils.setField(queue, "attemptService", attemptService);
        ReflectionTestUtils.setField(queue, "evaluation", evaluation);
        ReflectionTestUtils.setField(queue, "objectMapper", new ObjectMapper());

        student = new User();
        student.setId(7L);
        quiz = new Quiz();
        quiz.setqId(1L);
        when(users.findById(7L)).thenReturn(Optional.of(student));
        when(quizzes.findById(1L)).thenReturn(Optional.of(quiz));
        when(evaluation.quizOf(any())).thenReturn(quiz);
        when(attemptService.attemptForTheorySubmission(student, quiz)).thenReturn(42L);
        when(jobs.save(any(TheoryGradingJob.class))).thenAnswer(i -> i.getArgument(0));
    }

    private TheoryGradingJob storedJob() throws Exception {
        TheoryGradingJob job = new TheoryGradingJob();
        job.setId(9L);
        job.setUserId(7L);
        job.setQuizId(1L);
        job.setAttemptId(42L);
        job.setPayload(new ObjectMapper().writeValueAsString(request));
        job.setStatus(Status.RUNNING);
        job.setNextAttemptAt(LocalDateTime.now());
        when(jobs.findById(9L)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void submitStoresTheAnswersWithoutCallingTheAi() {
        when(jobs.findFirstByAttemptIdAndStatusIn(eq(42L), any())).thenReturn(Optional.empty());

        TheoryGradingJob job = queue.submit(request, student);

        assertThat(job.getStatus()).isEqualTo(Status.PENDING);
        assertThat(job.getAttemptId()).isEqualTo(42L);
        assertThat(job.getPayload()).contains("tqid 3");
        verify(evaluation, never()).grade(any(), any(), any());
    }

    @Test
    void resubmittingTheSameAttemptReturnsTheQueuedJob() {
        TheoryGradingJob existing = new TheoryGradingJob();
        existing.setId(5L);
        when(jobs.findFirstByAttemptIdAndStatusIn(eq(42L), any())).thenReturn(Optional.of(existing));

        assertThat(queue.submit(request, student)).isSameAs(existing);
        verify(jobs, never()).save(any());
    }

    @Test
    void markingSucceeds() throws Exception {
        TheoryGradingJob job = storedJob();

        queue.run(9L);

        verify(evaluation).grade(any(GeminiRequest.class), eq(student), eq(quiz));
        assertThat(job.getStatus()).isEqualTo(Status.DONE);
    }

    @Test
    void providerFailureIsRetriedLater() throws Exception {
        TheoryGradingJob job = storedJob();
        when(evaluation.grade(any(), any(), any())).thenThrow(new RuntimeException("AI provider timed out"));

        queue.run(9L);

        assertThat(job.getStatus()).isEqualTo(Status.PENDING);
        assertThat(job.getTries()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isAfter(LocalDateTime.now().plusSeconds(20));
    }

    @Test
    void givesUpAfterFiveTries() throws Exception {
        TheoryGradingJob job = storedJob();
        job.setTries(4);
        when(evaluation.grade(any(), any(), any())).thenThrow(new RuntimeException("still down"));

        queue.run(9L);

        assertThat(job.getStatus()).isEqualTo(Status.FAILED);
    }

    @Test
    void alreadyMarkedAttemptIsNotRetried() throws Exception {
        TheoryGradingJob job = storedJob();
        when(evaluation.grade(any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "already submitted"));

        queue.run(9L);

        assertThat(job.getStatus()).isEqualTo(Status.FAILED);
        assertThat(job.getTries()).isEqualTo(1);
    }

    private User staff(Role role) {
        User u = new User();
        u.setId(99L);
        u.setUsername("staff");
        u.setRole(role);
        return u;
    }

    @Test
    void staffCanRestartAFailedSubmission() throws Exception {
        TheoryGradingJob job = storedJob();
        job.setStatus(Status.FAILED);
        job.setTries(5);

        queue.retry(1L, 9L, staff(Role.SUPER_ADMIN));

        assertThat(job.getStatus()).isEqualTo(Status.PENDING);
        assertThat(job.getTries()).isZero();
        assertThat(job.getNextAttemptAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void onlyFailedSubmissionsOfThisQuizCanBeRestarted() throws Exception {
        TheoryGradingJob job = storedJob();   // RUNNING
        assertThatThrownBy(() -> queue.retry(1L, 9L, staff(Role.SUPER_ADMIN)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not in a failed state");

        job.setStatus(Status.FAILED);
        Quiz other = new Quiz();
        other.setqId(2L);
        when(quizzes.findById(2L)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> queue.retry(2L, 9L, staff(Role.SUPER_ADMIN)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");
    }

    @Test
    void studentsCannotSeeOrRestartMarking() {
        assertThatThrownBy(() -> queue.markingStatus(1L, staff(Role.NORMAL))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queue.retry(1L, 9L, staff(Role.NORMAL))).isInstanceOf(AccessDeniedException.class);
    }
}
