package com.exam.model.fees;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * A student asking for wallet money back. It goes REQUESTED → REVIEWED (bursar) → APPROVED (Super
 * Admin) → PAID (accounts office, with a refund voucher), each step by a different person; the bursar
 * or Super Admin may reject it and the student may cancel it until it is paid. From the request until
 * it ends, its amount is held: it can't be spent or asked for twice. REVERSED: the refund voucher was
 * cancelled afterwards, so the money is back in the wallet.
 */
@Entity
@Table(name = "wallet_withdrawal", indexes = {
        @Index(name = "idx_wallet_wd_student", columnList = "student_id"),
        @Index(name = "idx_wallet_wd_status", columnList = "status")
})
@Getter @Setter
public class WalletWithdrawal {

    public enum Status { REQUESTED, REVIEWED, APPROVED, PAID, REJECTED, CANCELLED, REVERSED }

    /** How the student would like to receive it. */
    public enum Payout { CASH, MOBILE_MONEY, BANK }

    /** Still in progress: the amount is held. */
    public static final Set<Status> OPEN = EnumSet.of(Status.REQUESTED, Status.REVIEWED, Status.APPROVED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    /** WD-2026-000001. */
    @Column(nullable = false, length = 30, unique = true)
    private String number;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Payout payout;

    /** MoMo number and name, or bank, account number and name. */
    @Column(length = 200)
    private String payoutDetails;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.REQUESTED;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(length = 120) private String reviewedBy;
    private Long reviewedById;
    private LocalDateTime reviewedAt;
    @Column(length = 300) private String reviewNote;

    @Column(length = 120) private String approvedBy;
    private Long approvedById;
    private LocalDateTime approvedAt;
    @Column(length = 300) private String approvalNote;

    @Column(length = 120) private String paidBy;
    private Long paidById;
    private LocalDateTime paidAt;

    /** The voucher it was paid out with. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id")
    private FeeRefund refund;

    @Column(length = 120) private String rejectedBy;
    private LocalDateTime rejectedAt;
    @Column(length = 300) private String rejectReason;
    /** The step it was rejected at (REQUESTED, REVIEWED or APPROVED). */
    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private Status rejectedAtStep;

    private LocalDateTime cancelledAt;

    public boolean isOpen() { return OPEN.contains(status); }
}
