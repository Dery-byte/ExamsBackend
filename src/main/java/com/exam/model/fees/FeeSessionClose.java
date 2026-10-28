package com.exam.model.fees;

import com.exam.model.academic.AcademicSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A session whose school fees are closed: what each student still owed became arrears, and its fees
 * are no longer owed directly. Reopened (deleted) only while none of those arrears has been paid.
 */
@Entity
@Table(name = "fee_session_close", uniqueConstraints = @UniqueConstraint(name = "uk_fee_session_close", columnNames = "session_id"))
@Getter @Setter
public class FeeSessionClose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AcademicSession session;

    @Column(nullable = false)
    private int students;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    /** Closed by the system when the next session was made current. */
    @Column(nullable = false)
    private boolean automatic;

    @Column(length = 120)
    private String closedBy;

    @Column(nullable = false)
    private LocalDateTime closedAt = LocalDateTime.now();
}
