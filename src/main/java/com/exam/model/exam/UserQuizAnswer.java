package com.exam.model.exam;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "user_quiz_progress")
@Setter
@Getter
public class UserQuizAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @ElementCollection
    @CollectionTable(
            name = "user_answer_options",
            joinColumns = @JoinColumn(name = "user_quiz_progress_id")
    )
    @Column(name = "selected_option")
    private List<String> selectedOptions = new ArrayList<>();

    @Column(name = "quiz_id")
    private Long quizId; // Optional: if you want to track which quiz this belongs to

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "matching_answers", length = 4000)
    private String matchingAnswers;


    @Column(name = "violation_count")
    private Integer violationCount = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "violation_delay_time")
    private Integer violationDelayTime;

    @Column(name = "auto_submitted")
    private Boolean autoSubmitted = false;

    @Column(name = "completed")
    private Boolean completed = false;




    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
}