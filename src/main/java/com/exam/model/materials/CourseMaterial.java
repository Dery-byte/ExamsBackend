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
 * One item in a course's week: notes (files), a link, or an assignment (instructions, files, a due
 * date, marks and online submission). Deleted items stay in the recycle bin for 30 days.
 */
@Entity
@Table(name = "course_material", indexes = {
        @Index(name = "idx_material_space", columnList = "space_id"),
        @Index(name = "idx_material_notified", columnList = "notified")})
@Getter @Setter
public class CourseMaterial {

    public enum Kind { NOTE, LINK, ASSIGNMENT }

    /** DRAFT: only staff see it. VISIBLE: students see it (once its week is open). SCHEDULED: from visibleFrom. HIDDEN: taken down again. */
    public enum Visibility { DRAFT, VISIBLE, SCHEDULED, HIDDEN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "space_id")
    private CourseSpace space;

    /** 0 = General. */
    @Column(nullable = false)
    private int weekNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    @Column(nullable = false, length = 200)
    private String title;

    /** Plain text (line breaks kept); for assignments, the instructions. */
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 1000)
    private String linkUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Visibility visibility = Visibility.DRAFT;

    private LocalDateTime visibleFrom;

    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean pinned = false;

    @Column(nullable = false)
    @ColumnDefault("0")
    private int sortOrder = 0;

    /** Students were told it is out (set when it first becomes visible). */
    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean notified = false;

    /** First time students could see it (drives the "New" badge). */
    private LocalDateTime publishedAt;

    // ── Assignments ─────────────────────────────────────────────────────────

    private LocalDateTime dueAt;

    /** Submissions are refused after this; starts equal to dueAt, the lecturer can reopen with a new one. */
    private LocalDateTime closesAt;

    @Column(precision = 8, scale = 2)
    private BigDecimal maxMarks;

    /** Model answer / marking scheme for the AI and the lecturer; never shown to students. */
    @Column(columnDefinition = "TEXT")
    private String markingGuide;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private LlmProvider aiProvider;

    /** Students may type their answer in the page. */
    @Column(nullable = false)
    @ColumnDefault("1")
    private boolean allowTyped = true;

    /** Students may upload files (PDF, Word, photos). */
    @Column(nullable = false)
    @ColumnDefault("1")
    private boolean allowFiles = true;

    /** The marks count towards the course's running assignment total (off for practice work). */
    @Column(nullable = false)
    @ColumnDefault("1")
    private boolean countsTowardTotal = true;

    /** Students see their marks and feedback. */
    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean marksReleased = false;

    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean reminderSent = false;

    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean closedNotified = false;

    // ── Bookkeeping ─────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    private String createdByName;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
    private String deletedByName;

    /** The item this was copied from (copy from an earlier term). */
    private Long copiedFromId;

    public boolean isAssignment() { return kind == Kind.ASSIGNMENT; }
}
