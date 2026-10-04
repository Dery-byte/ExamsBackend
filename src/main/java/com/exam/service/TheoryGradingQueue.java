package com.exam.service;

import com.exam.model.User;
import com.exam.model.exam.GeminiRequest;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.TheoryGradingJob;
import com.exam.model.exam.TheoryGradingJob.Status;
import com.exam.repository.QuizRepository;
import com.exam.repository.TheoryGradingJobRepository;
import com.exam.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background AI marking of theory answers.
 * <p>
 * Submitting only checks the submission and stores it as a {@link TheoryGradingJob} (milliseconds,
 * no AI call), so a whole exam hall submitting when the clock runs out does not hold server threads
 * and database connections while the AI provider answers. A worker then marks at most
 * {@code app.grading.concurrency} submissions at a time, retrying failures with backoff. Results
 * are released to students after lecturer review, so marking a little later costs nothing.
 */
@Service
public class TheoryGradingQueue {

    private static final Logger log = LoggerFactory.getLogger(TheoryGradingQueue.class);
    private static final int MAX_TRIES = 5;
    /** A RUNNING job not updated for this long was left behind by a stopped server. */
    private static final int STUCK_AFTER_MINUTES = 15;

    @Autowired private TheoryGradingJobRepository jobs;
    @Autowired private UserRepository users;
    @Autowired private QuizRepository quizzes;
    @Autowired private AttemptService attemptService;
    @Autowired private SubjectiveEvaluationService evaluation;
    @Autowired private ObjectMapper objectMapper;

    private final int concurrency;
    private final ExecutorService workers;
    private final AtomicInteger busy = new AtomicInteger();

    public TheoryGradingQueue(@Value("${app.grading.concurrency:4}") int concurrency) {
        this.concurrency = Math.max(1, concurrency);
        this.workers = Executors.newFixedThreadPool(this.concurrency, r -> {
            Thread t = new Thread(r, "theory-grading");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Accepts a student's theory submission for marking. Idempotent per attempt: a retried submit
     * (e.g. the response was lost on a busy network) returns the job already queued.
     */
    public TheoryGradingJob submit(GeminiRequest request, User student) {
        Quiz quiz = evaluation.quizOf(request);
        Long attemptId = attemptService.attemptForTheorySubmission(student, quiz);
        return jobs.findFirstByAttemptIdAndStatusIn(attemptId, EnumSet.of(Status.PENDING, Status.RUNNING, Status.DONE))
                .orElseGet(() -> {
                    TheoryGradingJob job = new TheoryGradingJob();
                    job.setUserId(student.getId());
                    job.setQuizId(quiz.getqId());
                    job.setAttemptId(attemptId);
                    job.setPayload(toJson(request));
                    LocalDateTime now = LocalDateTime.now();
                    job.setCreatedAt(now);
                    job.setUpdatedAt(now);
                    job.setNextAttemptAt(now);
                    return jobs.save(job);
                });
    }

    // ── Staff: marking status on the review page ────────────────────────────────

    /**
     * For the review page: how many submissions of this quiz are still being marked, and the ones
     * whose marking failed (those students have no result yet, so they are not in the results table).
     */
    public Map<String, Object> markingStatus(Long quizId, User staff) {
        requireManages(quizId, staff);
        List<TheoryGradingJob> open = jobs.findByQuizIdAndStatusInOrderByIdAsc(
                quizId, EnumSet.of(Status.PENDING, Status.RUNNING, Status.FAILED));
        Map<Long, User> students = new HashMap<>();
        users.findAllById(open.stream().map(TheoryGradingJob::getUserId).distinct().toList())
                .forEach(u -> students.put(u.getId(), u));

        List<Map<String, Object>> failed = new ArrayList<>();
        int marking = 0, retrying = 0;
        for (TheoryGradingJob j : open) {
            if (j.getStatus() != Status.FAILED) {
                marking++;
                if (j.getTries() > 0) retrying++;
                continue;
            }
            User s = students.get(j.getUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", j.getId());
            row.put("studentId", j.getUserId());
            row.put("studentName", s == null ? "Unknown student" : com.exam.service.comms.CurrentUserService.displayName(s));
            row.put("username", s == null ? null : s.getUsername());
            row.put("tries", j.getTries());
            row.put("error", j.getLastError());
            row.put("failedAt", j.getUpdatedAt());
            failed.add(row);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("marking", marking);
        res.put("retrying", retrying);
        res.put("failed", failed);
        return res;
    }

    /** Puts a failed submission back in the queue for another round of tries. */
    public void retry(Long quizId, Long jobId, User staff) {
        requireManages(quizId, staff);
        TheoryGradingJob job = jobs.findById(jobId)
                .filter(j -> j.getQuizId().equals(quizId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Marking job not found."));
        if (job.getStatus() != Status.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This submission is not in a failed state.");
        }
        log.info("Theory marking job {} re-queued by {}", jobId, staff.getUsername());
        job.setTries(0);
        job.setNextAttemptAt(LocalDateTime.now());
        finish(job, Status.PENDING, job.getLastError());
    }

    private void requireManages(Long quizId, User staff) {
        Quiz quiz = quizzes.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz not found."));
        com.exam.service.examops.ExamAccess.requireQuiz(staff, quiz);
    }

    // ── Worker ─────────────────────────────────────────────────────────────────

    /** Hands due jobs to free workers. Cheap when idle: one indexed query every 2 s. */
    @Scheduled(fixedDelay = 2000, initialDelay = 10_000)
    public void dispatch() {
        int free = concurrency - busy.get();
        if (free <= 0) return;
        LocalDateTime now = LocalDateTime.now();
        for (TheoryGradingJob job : jobs.findDue(Status.PENDING, now, PageRequest.of(0, free))) {
            if (jobs.claim(job.getId(), now) != 1) continue;   // another server instance took it
            busy.incrementAndGet();
            workers.submit(() -> {
                try { run(job.getId()); } finally { busy.decrementAndGet(); }
            });
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void requeueAfterRestart() {
        requeueStuck();
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void requeueStuck() {
        LocalDateTime now = LocalDateTime.now();
        int n = jobs.requeueStuck(now.minusMinutes(STUCK_AFTER_MINUTES), now);
        if (n > 0) log.warn("Re-queued {} theory marking job(s) left running by a stopped server", n);
    }

    void run(Long jobId) {
        TheoryGradingJob job = jobs.findById(jobId).orElse(null);
        if (job == null) return;
        try {
            User student = users.findById(job.getUserId()).orElseThrow(() -> new IllegalStateException("Student not found"));
            Quiz quiz = quizzes.findById(job.getQuizId()).orElseThrow(() -> new IllegalStateException("Quiz not found"));
            GeminiRequest request = objectMapper.readValue(job.getPayload(), GeminiRequest.class);
            AttemptService.recordingTheoryFor(job.getAttemptId(), () -> evaluation.grade(request, student, quiz));
            finish(job, Status.DONE, null);
        } catch (ResponseStatusException e) {
            // Refused for good (e.g. this attempt's theory was already marked): retrying cannot help
            boolean permanent = e.getStatusCode().is4xxClientError();
            retryOrFail(job, e, permanent);
        } catch (IllegalStateException | IllegalArgumentException e) {
            retryOrFail(job, e, true);
        } catch (Exception e) {
            retryOrFail(job, e, false);
        }
    }

    private void retryOrFail(TheoryGradingJob job, Exception e, boolean permanent) {
        job.setTries(job.getTries() + 1);
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (permanent || job.getTries() >= MAX_TRIES) {
            log.error("Theory marking failed for good — job {}, student {}, quiz {}: {}",
                    job.getId(), job.getUserId(), job.getQuizId(), message, e);
            finish(job, Status.FAILED, message);
        } else {
            long backoffSeconds = Math.min(600, 30L << (job.getTries() - 1));   // 30 s, 1 min, 2 min, 4 min
            log.warn("Theory marking failed (try {}/{}) — job {}, retrying in {} s: {}",
                    job.getTries(), MAX_TRIES, job.getId(), backoffSeconds, message);
            job.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds));
            finish(job, Status.PENDING, message);
        }
    }

    private void finish(TheoryGradingJob job, Status status, String error) {
        job.setStatus(status);
        job.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), 1000)));
        job.setUpdatedAt(LocalDateTime.now());
        jobs.save(job);
    }

    private String toJson(GeminiRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the submission.");
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();   // jobs still running are re-queued on the next start
    }
}
