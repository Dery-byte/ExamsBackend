package com.exam.model.fees;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Money paid back to a student (or their parent). Only credit can be refunded, never money counted
 * against something owed; the refund takes it from the credit of their payments, oldest first
 * (allocations with this refund as the target). Voiding a refund puts the money back as credit.
 */
@Entity
@Table(name = "fee_refund", indexes = {
        @Index(name = "idx_fee_refund_student", columnList = "student_id"),
        @Index(name = "idx_fee_refund_paid", columnList = "paid_at")
})
@Getter @Setter
public class FeeRefund {

    public enum Status { DONE, VOIDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    /** RF-2026-000001. */
    @Column(nullable = false, length = 30, unique = true)
    private String number;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FeePayment.Method method;

    /** Who received it and how: "Paid to Mr. Owusu (father), MoMo 024…". */
    @Column(length = 200)
    private String paidTo;

    @Column(nullable = false, length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status = Status.DONE;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt = LocalDateTime.now();

    @Column(length = 120)
    private String paidBy;

    private Long paidById;

    /** The cash-up that accounted for it (cash refunds by a cashier). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_up_id")
    private CashUp cashUp;

    @Column(length = 120)
    private String voidedBy;

    private LocalDateTime voidedAt;

    @Column(length = 200)
    private String voidReason;
}
