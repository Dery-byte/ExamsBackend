package com.exam.model;

import com.exam.model.exam.Quiz;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
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

    @Column(name = "violation_delay_time")
    private Integer violationDelayTime;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "total_violation_count")
    private Integer totalViolationCount;


    public Integer getTotalViolationCount() {
        return totalViolationCount;
    }

    public void setTotalViolationCount(Integer totalViolationCount) {
        this.totalViolationCount = totalViolationCount;
    }

    public Integer getViolationDelayTime() {
        return violationDelayTime;
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