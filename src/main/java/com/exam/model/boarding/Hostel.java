package com.exam.model.boarding;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A boarding house, hall or hostel. Its rooms sit straight under it, or under its blocks (wings)
 * when it has any. {@link #floors} = 1 is a single-storey building.
 */
@Entity
@Table(name = "boarding_hostel",
        uniqueConstraints = @UniqueConstraint(name = "uk_boarding_hostel_name", columnNames = "name_key"))
@Getter @Setter
public class Hostel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    /** Lower-case name while in use, null once archived, so an archived name can be reused. */
    @Column(name = "name_key", length = 80)
    private String nameKey;

    @Column(length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HostelGender gender = HostelGender.MIXED;

    /** Floors of the building when it has no blocks (1 = single storey; floor 0 is the ground floor). */
    @Column(nullable = false)
    private int floors = 1;

    /** Levels that may live here, e.g. "1,2" (blank = every level). Checked when a student is given a room. */
    @Column(name = "allowed_levels", length = 100)
    private String allowedLevels;

    @Column(length = 300)
    private String note;

    /** False once archived: hidden, takes no students, history kept. */
    @Column(nullable = false)
    private boolean active = true;

    private LocalDateTime createdAt;
}
