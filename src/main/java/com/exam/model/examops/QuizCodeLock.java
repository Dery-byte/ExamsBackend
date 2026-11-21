package com.exam.model.examops;

import com.exam.model.User;
import com.exam.model.exam.Quiz;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Wrong quiz access codes one student has entered for one quiz since their last correct one. At
 * {@link com.exam.service.examops.QuizCodeLockService#MAX_WRONG} the code box locks for that quiz
 * until staff allow attempts again. One row per student and quiz.
 */
@Entity
@Table(name = "quiz_code_lock",
        uniqueConstraints = @UniqueConstraint(name = "uk_quiz_code_lock_quiz_student", columnNames = {"quiz_id", "student_id"}))
@Getter @Setter
public class QuizCodeLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id")
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private User student;

    @Column(name = "wrong_count", nullable = false)
    private int wrongCount;

    @Column(name = "last_wrong_at")
    private LocalDateTime lastWrongAt;

    /** Set when the student reached the limit; null while they may still try. */
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /** The staff member who last allowed attempts again, and when. */
    @Column(name = "reopened_by_name", length = 160)
    private String reopenedByName;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;
}
