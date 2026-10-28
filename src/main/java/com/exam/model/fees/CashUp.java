package com.exam.model.fees;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A cashier handing in what they took: every payment they recorded (and cash refund they paid) since
 * their last cash-up. Expected cash is worked out; the cashier declares what they counted; the
 * difference is over (+) or short (−). The bursar confirms receiving it. A cashier may cancel their
 * own payment only until it is in a cash-up.
 */
@Entity
@Table(name = "fee_cash_up", indexes = @Index(name = "idx_fee_cash_up_cashier", columnList = "cashier_id, created_at"))
@Getter @Setter
public class CashUp {

    public enum Status { SUBMITTED, CONFIRMED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cashier_id", nullable = false)
    private Long cashierId;

    @Column(nullable = false, length = 120)
    private String cashierName;

    @Column(nullable = false)
    private int payments;

    /** Cash payments less cash refunds. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal expectedCash;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal declaredCash;

    /** Bank transfers and other non-cash payments in it (nothing to hand over). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal nonCash;

    @Column(length = 300)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.SUBMITTED;

    @Column(length = 120)
    private String confirmedBy;

    private LocalDateTime confirmedAt;

    @Column(length = 300)
    private String confirmNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public BigDecimal difference() { return declaredCash.subtract(expectedCash); }
}
