package com.exam.model.fees;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A student using wallet money on what they owe. The money itself moves in the deposits' allocations
 * (see FeeLedger#spendWallet); this keeps the record the student and the office read, with a number
 * (WP-2026-000001) and what it paid.
 */
@Entity
@Table(name = "wallet_spend", indexes = @Index(name = "idx_wallet_spend_student", columnList = "student_id"))
@Getter @Setter
public class WalletSpend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @Column(nullable = false, length = 30, unique = true)
    private String number;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** "Term 1 fees · 2026/2027 (GHS 300.00), School uniform (GHS 50.00)". */
    @Column(nullable = false, length = 500)
    private String paidFor;

    @Column(name = "spent_at", nullable = false)
    private LocalDateTime spentAt = LocalDateTime.now();

    /** The student, or the Super Admin / bursar who paid it from their wallet. */
    @Column(length = 120)
    private String spentBy;

    private Long spentById;

    /** Why staff paid it from the wallet ("Asked at the counter to pay the uniform bill"); null when the student did. */
    @Column(length = 300)
    private String note;
}
