package com.exam.model.exam;

import com.exam.model.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row per attempt a student makes at a quiz. This is the ledger of every attempt and its marks.
 * {@link Report} stays the single "official" result per (student, quiz) and always mirrors the
 * latest non-voided attempt.
 */
@Entity
@Table(
    name = "quiz_attempt",
    uniqueConstraints = @UniqueConstraint(name = "uk_attempt_user_quiz_no", columnNames = {"user_id", "quiz_id", "attempt_number"}),
    indexes = @Index(name = "idx_attempt_user_quiz", columnList = "user_id, quiz_id")
)
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    /** 1, 2, 3 … Never reused, even after an attempt is voided. */
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** Objective (section A) marks. */
    @Column(name = "marks_a", precision = 10, scale = 1)
    private BigDecimal marksA;

    /** Theory (section B) marks. */
    @Column(name = "marks_b", precision = 10, scale = 1)
    private BigDecimal marksB;

    @Column(name = "objective_submitted", nullable = false)
    private boolean objectiveSubmitted = false;

    @Column(name = "theory_submitted", nullable = false)
    private boolean theorySubmitted = false;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    // Plain values, not a FK, so the audit trail survives deleting the staff account.
    @Column(name = "voided_by_id")
    private Long voidedById;

    @Column(name = "voided_by_name", length = 200)
    private String voidedByName;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Quiz getQuiz() { return quiz; }
    public void setQuiz(Quiz quiz) { this.quiz = quiz; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }
    public AttemptStatus getStatus() { return status; }
    public void setStatus(AttemptStatus status) { this.status = status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public BigDecimal getMarksA() { return marksA; }
    public void setMarksA(BigDecimal marksA) { this.marksA = marksA; }
    public BigDecimal getMarksB() { return marksB; }
    public void setMarksB(BigDecimal marksB) { this.marksB = marksB; }
    public boolean isObjectiveSubmitted() { return objectiveSubmitted; }
    public void setObjectiveSubmitted(boolean objectiveSubmitted) { this.objectiveSubmitted = objectiveSubmitted; }
    public boolean isTheorySubmitted() { return theorySubmitted; }
    public void setTheorySubmitted(boolean theorySubmitted) { this.theorySubmitted = theorySubmitted; }
    public LocalDateTime getVoidedAt() { return voidedAt; }
    public void setVoidedAt(LocalDateTime voidedAt) { this.voidedAt = voidedAt; }
    public Long getVoidedById() { return voidedById; }
    public void setVoidedById(Long voidedById) { this.voidedById = voidedById; }
    public String getVoidedByName() { return voidedByName; }
    public void setVoidedByName(String voidedByName) { this.voidedByName = voidedByName; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
}
