package com.exam.model.fees;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One change to what the store holds of a price-list item: a delivery received, items handed over
 * to a student (against a bill line) or given back, or a count adjustment (damage, loss, recount).
 * quantity is signed (+ in, − out); balanceAfter is what the store held right after.
 */
@Entity
@Table(name = "fee_stock_movement", indexes = {
        @Index(name = "idx_fee_stock_item", columnList = "item_id, created_at"),
        @Index(name = "idx_fee_stock_line", columnList = "bill_line_id")
})
@Getter @Setter
public class StockMovement {

    public enum Kind { RECEIVED, ISSUED, RETURNED, ADJUSTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private PriceItem item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int balanceAfter;

    /** What each one cost the school (deliveries). */
    @Column(precision = 12, scale = 2)
    private BigDecimal unitCost;

    /** Supplier and delivery note / invoice number (deliveries). */
    @Column(length = 160)
    private String reference;

    @Column(length = 300)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_line_id")
    private BillLine billLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private User student;

    @Column(length = 120)
    private String byName;

    private Long byId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
