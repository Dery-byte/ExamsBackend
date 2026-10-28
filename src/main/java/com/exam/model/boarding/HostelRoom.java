package com.exam.model.boarding;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A room with a number of beds. Its gender falls back to its block's, then its hostel's.
 * Rooms with history are archived rather than deleted.
 */
@Entity
@Table(name = "boarding_room",
        uniqueConstraints = @UniqueConstraint(name = "uk_boarding_room_name", columnNames = {"hostel_id", "name_key"}),
        indexes = @Index(name = "idx_boarding_room_block", columnList = "block_id"))
@Getter @Setter
public class HostelRoom {

    public enum Status { OPEN, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hostel_id", nullable = false)
    private Hostel hostel;

    /** Null when the hostel has no blocks. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_id")
    private HostelBlock block;

    /** 0 = ground floor. */
    @Column(nullable = false)
    private int floor;

    @Column(nullable = false, length = 30)
    private String name;

    /** "{blockId or -}/{lower-case name}" while in use, null once archived: room names are unique per block. */
    @Column(name = "name_key", length = 60)
    private String nameKey;

    /** Number of beds. */
    @Column(nullable = false)
    private int capacity;

    /** Null = as the block / hostel. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private HostelGender gender;

    /** CLOSED (repairs …) takes no new students; those already in it stay until moved. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    /** e.g. "Prefects", "Sick bay". */
    @Column(length = 60)
    private String label;

    /** Levels that may live here (blank = as the hostel). */
    @Column(name = "allowed_levels", length = 100)
    private String allowedLevels;

    @Column(nullable = false)
    private boolean active = true;

    public static String keyFor(HostelBlock block, String name) {
        return (block == null ? "-" : String.valueOf(block.getId())) + "/" + name.trim().toLowerCase();
    }

    public HostelGender effectiveGender() {
        if (gender != null) return gender;
        if (block != null && block.getGender() != null) return block.getGender();
        return hostel.getGender();
    }

    public String effectiveLevels() {
        return allowedLevels != null && !allowedLevels.isBlank() ? allowedLevels : hostel.getAllowedLevels();
    }
}
