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
                @Index(name = "idx_fee_payment_status_created", columnList = "status, created_at")
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private FeeSchedule schedule;

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

    @Column(length = 300)
    private String note;

    /** The items of an itemised fee this payment is for; empty when it is for the fee as a whole. */
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    private java.util.List<FeePaymentItem> items = new java.util.ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}
