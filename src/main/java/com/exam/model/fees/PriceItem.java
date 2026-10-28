package com.exam.model.fees;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Something the school charges for apart from fees: uniform, books, PTA dues, an excursion, a fine (the bursar's price list). */
@Entity
@Table(name = "fee_price_item", uniqueConstraints = @UniqueConstraint(name = "uk_fee_price_item_name", columnNames = "name_key"))
@Getter @Setter
@org.hibernate.annotations.BatchSize(size = 50)
public class PriceItem {

    public enum Category { UNIFORM, BOOKS, LEVY, EXAM, TRIP, FEEDING, FINE, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    /** Lower-case name while in use, null once retired (so the name can be used again). */
    @Column(name = "name_key", length = 80)
    private String nameKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Category category = Category.OTHER;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(length = 200)
    private String description;

    /** Goods the store keeps (uniform, books): handing them over is counted against what it holds. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private boolean stocked;

    /** What the store holds now (stocked items). Never below zero. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private int stockOnHand;

    /** Warn when the store holds this many or fewer. */
    private Integer reorderLevel;

    /** What one cost the school at the last delivery. */
    @Column(precision = 12, scale = 2)
    private BigDecimal unitCost;

    /** False once retired: kept for old bills, not offered for new ones. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 120)
    private String updatedBy;

    private LocalDateTime updatedAt;
}
