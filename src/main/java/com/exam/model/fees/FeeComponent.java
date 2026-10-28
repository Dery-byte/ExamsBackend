package com.exam.model.fees;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One line of an itemised fee, e.g. "Sanitation — GHS 50". */
@Entity
@Getter
@Setter
@Table(name = "fee_component", indexes = @Index(name = "idx_fee_component_schedule", columnList = "schedule_id"))
public class FeeComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private FeeSchedule schedule;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Who pays this item (with day students and boarders): everyone, boarders only or day students only. */
    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", length = 10)
    private FeeAppliesTo appliesTo = FeeAppliesTo.EVERYONE;

    public FeeAppliesTo appliesTo() { return appliesTo == null ? FeeAppliesTo.EVERYONE : appliesTo; }
}
