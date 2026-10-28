package com.exam.model.boarding;

import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A student's stay in a room, from {@link #startedAt} until {@link #endedAt} (null = living there now).
 * Rows are never deleted while the account exists, so who slept where, and when, stays answerable.
 * <p>
 * {@link #currentStudentId} and {@link #currentBedKey} are filled only while the stay is current and
 * carry unique keys (MySQL allows any number of NULLs), so the database itself refuses a second
 * current room for a student or two students in one bed.
 */
@Entity
@Table(name = "boarding_allocation",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_boarding_alloc_current_student", columnNames = "current_student_id"),
                @UniqueConstraint(name = "uk_boarding_alloc_current_bed", columnNames = "current_bed_key")
        },
        indexes = {
                @Index(name = "idx_boarding_alloc_room", columnList = "room_id, ended_at"),
                @Index(name = "idx_boarding_alloc_student", columnList = "student_id")
        })
@Getter @Setter
public class RoomAllocation {

    /** Why a stay ended; NEW_YEAR = cleared by the head of boarding before rooms were given out again. */
    public enum EndReason { MOVED, SWAPPED, BECAME_DAY, DEACTIVATED, VACATED, NEW_YEAR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private HostelRoom room;

    /** 1 … room capacity, or null when the school doesn't number beds. */
    private Integer bed;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    /** The academic session when the stay began. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private AcademicSession session;

    @Column(length = 120)
    private String assignedBy;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private EndReason endReason;

    @Column(length = 120)
    private String endedBy;

    @Column(length = 200)
    private String note;

    @Column(name = "current_student_id")
    private Long currentStudentId;

    @Column(name = "current_bed_key", length = 40)
    private String currentBedKey;

    public boolean isCurrent() { return endedAt == null; }

    public static String bedKey(Long roomId, Integer bed) {
        return bed == null ? null : roomId + ":" + bed;
    }
}
