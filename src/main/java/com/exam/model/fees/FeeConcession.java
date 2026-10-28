package com.exam.model.fees;

import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Money the school will not collect from a student's school fees for one session: a scholarship or
 * bursary the school funds, a sibling or staff-child discount, a waiver. A percentage of the fee (or of
 * some items), or a fixed amount off each fee (each term, or the whole-year fee). A sponsor who will
 * actually pay is not a concession: their money is recorded as a payment when it arrives.
 */
@Entity
@Table(name = "fee_concession", indexes = @Index(name = "idx_fee_concession_session", columnList = "session_id, active"))
@Getter @Setter
public class FeeConcession {

    public enum Kind { SCHOLARSHIP, BURSARY, SIBLING, STAFF_CHILD, WAIVER, OTHER }
    public enum Mode { PERCENT, AMOUNT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AcademicSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    /** "Old Students' scholarship", "Second child". */
    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Mode mode;

    /** Percent (0–100) or the amount off each fee. */
    @Column(name = "concession_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal value;

    /** Item names it is limited to (one per line); null = the whole fee. */
    @Column(length = 500)
    private String items;

    /** One term only; null = every fee of the session. */
    private Integer term;

    @Column(length = 300)
    private String note;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 120)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(length = 120)
    private String revokedBy;

    private LocalDateTime revokedAt;

    @Column(length = 200)
    private String revokeReason;
}
