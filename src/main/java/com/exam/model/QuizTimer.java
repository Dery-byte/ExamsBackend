package com.exam.model;

import com.exam.model.exam.Quiz;
import jakarta.persistence.*;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "quiz_timers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "quiz_id"}))
public class QuizTimer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @Column(name = "remaining_time", nullable = false)
    private Integer remainingTime; // in seconds

    /** Length in seconds of the current (or last) violation lock-out. */
    @Column(name = "violation_delay_time")
    private Integer violationDelayTime;

    /** When the current violation lock-out ends; null when none has been started. */
    @Column(name = "violation_delay_until")
    private LocalDateTime violationDelayUntil;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "total_violation_count")
    private Integer totalViolationCount;

    /**
     * Goes up by one each time staff reset the student's violations. The exam page stamps every
     * violation and lock-out with the number it knows, so ones from before a reset (still queued on
     * the student's device, or sent before the page heard of the reset) can't bring the count back.
     */
    @Column(name = "violation_reset_no")
    private Integer violationResetNo;

    public int getViolationResetNo() {
        return violationResetNo == null ? 0 : violationResetNo;
    }

    public void setViolationResetNo(Integer violationResetNo) {
        this.violationResetNo = violationResetNo;
    }


    public Integer getTotalViolationCount() {
        return totalViolationCount;
    }

    public void setTotalViolationCount(Integer totalViolationCount) {
        this.totalViolationCount = totalViolationCount;
    }

    public Integer getViolationDelayTime() {
        return violationDelayTime;
    }

    public LocalDateTime getViolationDelayUntil() {
        return violationDelayUntil;
    }

    public void setViolationDelayUntil(LocalDateTime violationDelayUntil) {
        this.violationDelayUntil = violationDelayUntil;
    }

    public void setViolationDelayTime(Integer violationDelayTime) {
        this.violationDelayTime = violationDelayTime;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }

    public Integer getRemainingTime() {
        return remainingTime;
    }

    public void setRemainingTime(Integer remainingTime) {
        this.remainingTime = remainingTime;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}