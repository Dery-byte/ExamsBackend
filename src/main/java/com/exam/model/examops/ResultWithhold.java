package com.exam.model.examops;

import com.exam.model.User;
import com.exam.model.exam.Quiz;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One student's result for one quiz held back by staff (e.g. violations or an unusually long time
 * taken), while classmates' results are out. While active, the student sees "withheld" instead of
 * their marks everywhere, and the marks are not copied into a marks sheet. Released rows are kept as
 * history; at most one active row per student and quiz.
 */
@Entity
@Table(name = "result_withhold", indexes = @Index(name = "idx_result_withhold_quiz_student", columnList = "quiz_id, student_id"))
@Getter @Setter
public class ResultWithhold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id")
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private User student;

    /** Staff only: never shown to the student. */
    @Column(name = "reason", length = 600, nullable = false)
    private String reason;

    @Column(name = "withheld_by_name", length = 160)
    private String withheldByName;

    @Column(name = "withheld_at", nullable = false)
    private LocalDateTime withheldAt = LocalDateTime.now();

    @Column(name = "released_by_name", length = 160)
    private String releasedByName;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
