package com.exam.model.fees;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The part of a payment meant for one item of an itemised fee, e.g. "SRC dues — GHS 50".
 * Stored by name rather than linked to the fee component, so editing the fee (which rebuilds its
 * items) never breaks a past payment; a payment for an item that no longer exists simply counts
 * towards the fee as a whole.
 */
@Entity
@Getter
@Setter
@Table(name = "fee_payment_item", indexes = @Index(name = "idx_fee_payment_item_payment", columnList = "payment_id"))
public class FeePaymentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private FeePayment payment;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
}
