package com.exam.model.boarding;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** A block or wing of a hostel with its own number of floors (and, optionally, its own gender). */
@Entity
@Table(name = "boarding_block",
        uniqueConstraints = @UniqueConstraint(name = "uk_boarding_block_name", columnNames = {"hostel_id", "name_key"}))
@Getter @Setter
public class HostelBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hostel_id", nullable = false)
    private Hostel hostel;

    @Column(nullable = false, length = 60)
    private String name;

    /** Lower-case name while in use, null once archived. */
    @Column(name = "name_key", length = 60)
    private String nameKey;

    @Column(nullable = false)
    private int floors = 1;

    /** Null = as the hostel. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private HostelGender gender;

    @Column(nullable = false)
    private boolean active = true;
}
