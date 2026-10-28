package com.exam.model.fees;

import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A student's payment towards a fee schedule: online through Paystack (card or Mobile Money) or
 * recorded by the Super Admin (cash, bank). Only SUCCESS rows count towards what has been paid.
 * The programme and level are copied in so receipts still read correctly after a promotion.
 */
@Entity
@Getter
@Setter
@Table(name = "fee_payment",
        uniqueConstraints = @UniqueConstraint(name = "uk_fee_payment_reference", columnNames = "reference"),
        indexes = {
                @Index(name = "idx_fee_payment_student", columnList = "student_id"),
                @Index(name = "idx_fee_payment_schedule_status", columnList = "schedule_id, status"),
                @Index(name = "idx_fee_payment_session_status", columnList = "session_id, status"),
                @Index(name = "idx_fee_payment_status_created", columnList = "status, created_at"),
                @Index(name = "idx_fee_payment_paid_at", columnList = "paid_at"),
                @Index(name = "idx_fee_payment_cashier", columnList = "recorded_by_id, cash_up_id")
        })
public class FeePayment {

    /** VOIDED: a recorded cash / bank payment the Super Admin cancelled (kept for the audit trail). */
    public enum Status { PENDING, SUCCESS, FAILED, ABANDONED, VOIDED }

    public enum Method { PAYSTACK, CASH, BANK_TRANSFER, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    /**
     * What the payer chose to pay: one fee (term), or {@link #bill}, or neither (everything owed,
     * oldest first). Where the money actually went is in {@link #allocations}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private FeeSchedule schedule;

    /** A bill the payer chose to pay instead of a fee. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id")
    private StudentBill bill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AcademicSession session;

    @Column(name = "program_name", length = 255)
    private String programName;

    @Column(name = "level")
    private Integer level;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * Money paid into the student's wallet rather than towards what they owe: it stays there (an
     * allocation with no target) until the student spends it or it is paid back (see FeeLedger).
     */
    @Column(name = "wallet_deposit", nullable = false)
    private boolean walletDeposit;

    /** Our reference, also sent to Paystack; printed on the receipt. */
    @Column(nullable = false, length = 64)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Method method = Method.PAYSTACK;

    /** Paystack's channel: card, mobile_money, bank … */
    @Column(length = 32)
    private String channel;

    @Column(name = "gateway_message", length = 255)
    private String gatewayMessage;

    @Column(name = "gateway_transaction_id")
    private Long gatewayTransactionId;

    /** Paystack checkout page, reused if the student clicks Pay again for the same amount. */
    @Column(name = "authorization_url", length = 500)
    private String authorizationUrl;

    @Column(name = "payer_email", length = 255)
    private String payerEmail;

    /** Who recorded a cash / bank payment. */
    @Column(name = "recorded_by", length = 120)
    private String recordedBy;

    /** The staff account that recorded it (for cash-ups); null for online payments and older entries. */
    @Column(name = "recorded_by_id")
    private Long recordedById;

    /** The cash-up that handed this payment in; until then its cashier may still cancel it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_up_id")
    private CashUp cashUp;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "voided_by", length = 120)
    private String voidedBy;

    @Column(length = 300)
    private String note;

    /** RC-2026-000123: given when the money is received (cash entry, or Paystack confirming), never reused. */
    @Column(name = "receipt_no", length = 30, unique = true)
    private String receiptNo;

    /** The code printed on the receipt, checked on the public verification page (revoked when voided). */
    @Column(name = "verification_code", length = 20)
    private String verificationCode;

    /** The items of an itemised fee this payment is for; empty when it is for the fee as a whole. */
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    private java.util.List<FeePaymentItem> items = new java.util.ArrayList<>();

    /** Where the money went (fee terms, bills, credit): made when the payment succeeds. */
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    private java.util.List<FeeAllocation> allocations = new java.util.ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}
