package com.exam.model.exam;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One student's submitted theory answers waiting for (or done with) AI marking. Submitting only
 * stores this row; a background worker marks it a few at a time, so a whole exam hall submitting
 * at once never ties up the server waiting on the AI provider. Kept in the database so a restart
 * or redeploy does not lose submissions.
 */
@Entity
@Getter
@Setter
@Table(name = "theory_grading_jobs", indexes = {
        @Index(name = "idx_tgj_status_due", columnList = "status, next_attempt_at"),
        @Index(name = "idx_tgj_attempt", columnList = "attempt_id")
})
public class TheoryGradingJob {

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    /** The attempt the marks belong to (the student may have finished it before marking runs). */
    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    /** The submission exactly as the exam page sent it (GeminiRequest JSON). */
    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "tries", nullable = false)
    private int tries;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;
}
