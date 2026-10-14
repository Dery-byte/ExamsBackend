package com.exam.model.attendance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A helper's scanner link for one meeting (a teaching assistant scanning students' QR codes on
 * their own phone, without an account). Only the link's hash is stored; it works while the meeting
 * is live and until the lecturer cancels it, and the helper's phone must be in the room.
 */
@Entity
@Table(name = "attendance_scanner", indexes = @Index(name = "idx_att_scanner_session", columnList = "session_id"))
@Getter @Setter
public class AttendanceScanner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private AttendanceSession session;

    /** The helper's name, shown on every record they scan. */
    @Column(nullable = false, length = 80)
    private String label;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(length = 120)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private int scans;
}
