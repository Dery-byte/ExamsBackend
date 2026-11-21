package com.exam.model.examops;

import com.exam.model.User;
import com.exam.model.exam.Quiz;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One proctoring violation detected in a student's browser during a quiz (tab switch, screenshot …). */
@Entity
@Table(name = "proctoring_event", indexes = @Index(name = "idx_proctoring_quiz_user", columnList = "quiz_id, user_id"))
@Getter @Setter
public class ProctoringEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id")
    private Quiz quiz;

    /** Detector name from the exam page, e.g. tab-switch, window-blur, fullscreen-exit, screenshot-attempt, auto-submit. */
    @Column(nullable = false, length = 60)
    private String type;

    /** The student's running violation count after this event (null for informational events). */
    private Integer violationNumber;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    private String ipAddress;

    /**
     * True for a violation stamped with a reset number older than the student's current one: it
     * happened (or was queued) before staff reset the count, so it is shown but not counted.
     */
    @Column(name = "before_reset")
    private Boolean beforeReset;

    /** A "violations-reset" event: the staff member who reset the count, and their reason. */
    @Column(name = "actor_name", length = 160)
    private String actorName;

    @Column(name = "note", length = 600)
    private String note;
}
