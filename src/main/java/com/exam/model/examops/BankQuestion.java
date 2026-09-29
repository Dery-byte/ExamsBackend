package com.exam.model.examops;

import com.exam.model.exam.Category;
import com.exam.model.exam.QuestionType;
import com.exam.model.exam.StringArrayConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A reusable objective question stored per course. Drawing it into a quiz copies it into a
 * normal {@code Questions} row, so later edits to the bank never change a quiz already set.
 */
@Entity
@Table(name = "bank_question", indexes = @Index(name = "idx_bank_course", columnList = "course_id"))
@Getter @Setter
public class BankQuestion {

    public enum Difficulty { EASY, MEDIUM, HARD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id")
    @JsonIgnore
    private Category course;

    @Column(length = 120)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Difficulty difficulty = Difficulty.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType questionType = QuestionType.MCQ;

    @Column(length = 5000, nullable = false)
    private String content;

    private String image;

    private String option1;
    private String option2;
    private String option3;
    private String option4;

    @Convert(converter = StringArrayConverter.class)
    @Column(columnDefinition = "TEXT")
    private String[] correctAnswer;

    /** NUMERIC only: allowed difference from the correct value. */
    private Double tolerance;

    /** MATCHING only: JSON array of {prompt, answer} in display order. */
    @Column(columnDefinition = "TEXT")
    private String matchingPairsJson;

    private Long authorId;
    private String authorName;

    /** How many times this question has been drawn into a quiz. */
    private int timesUsed = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
