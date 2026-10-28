package com.exam.model.fees;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Part of a payment counted against one target: a fee (term), a bill, arrears, or a refund (money paid
 * back). No target = credit: money paid beyond everything owed, kept for whatever comes next; or, on a
 * wallet deposit, wallet money, kept until the student spends it.
 */
@Entity
@Table(name = "fee_payment_allocation", indexes = {
        @Index(name = "idx_fee_alloc_payment", columnList = "payment_id"),
        @Index(name = "idx_fee_alloc_schedule", columnList = "schedule_id"),
        @Index(name = "idx_fee_alloc_bill", columnList = "bill_id"),
        @Index(name = "idx_fee_alloc_arrears", columnList = "arrears_id"),
        @Index(name = "idx_fee_alloc_refund", columnList = "refund_id")
})
@Getter @Setter
public class FeeAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private FeePayment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private FeeSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id")
    private StudentBill bill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "arrears_id")
    private FeeArrears arrears;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id")
    private FeeRefund refund;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    public boolean isCredit() { return untargeted() && !payment.isWalletDeposit(); }

    public boolean isWallet() { return untargeted() && payment.isWalletDeposit(); }

    private boolean untargeted() { return schedule == null && bill == null && arrears == null && refund == null; }
}
