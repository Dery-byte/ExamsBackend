package com.exam.model.materials;

import com.exam.model.User;
import com.exam.model.exam.LlmProvider;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A student's work for an assignment (typed text and/or files). The student may replace it until
 * submissions close. The AI's suggestion is kept apart from the lecturer's mark: only the lecturer
 * saves a mark, and students see it once the lecturer releases marks.
 */
@Entity
@Table(name = "assignment_submission",
        uniqueConstraints = @UniqueConstraint(columnNames = {"assignment_id", "student_id"}))
@Getter @Setter
public class AssignmentSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id")
    private CourseMaterial assignment;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "student_id")
    private User student;

    @Column(columnDefinition = "TEXT")
    private String typedAnswer;

    private LocalDateTime submittedAt;
    private LocalDateTime updatedAt;

    /** Handed in (last changed) after the due date, while the lecturer had submissions reopened. */
    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean late = false;

    // ── Lecturer's mark ─────────────────────────────────────────────────────
    @Column(precision = 8, scale = 2)
    private BigDecimal score;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    private Long markedById;
    private String markedByName;
    private LocalDateTime markedAt;

    /** The mark came from accepting the AI's suggestion (possibly edited). */
    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean aiAssisted = false;

    // ── AI suggestion ───────────────────────────────────────────────────────
    @Column(precision = 8, scale = 2)
    private BigDecimal aiScore;

    @Column(columnDefinition = "TEXT")
    private String aiFeedback;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private LlmProvider aiProvider;

    private LocalDateTime aiAt;

    @Column(length = 500)
    private String aiError;

    /**
     * The AI's view on whether the work was written by an AI tool: LOW, MEDIUM or HIGH, with its specific
     * reasons. A pointer for the lecturer, never proof and never used in the mark.
     */
    @Column(length = 10)
    private String aiWriting;

    @Column(columnDefinition = "TEXT")
    private String aiWritingNotes;

    /** Queued by "AI-suggest all"; cleared when done. */
    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean aiQueued = false;
}
