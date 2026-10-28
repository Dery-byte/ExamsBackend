package com.exam.model.fees;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One line of a bill: "School sweater x 2 at GHS 85.00". */
@Entity
@Table(name = "fee_bill_line")
@Getter @Setter
public class BillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private StudentBill bill;

    /** The price-list item, when the line came from one (null for a one-off line). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private PriceItem item;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private int quantity = 1;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** How many have been handed over to the student (stocked items). */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private int issuedQty;
}
