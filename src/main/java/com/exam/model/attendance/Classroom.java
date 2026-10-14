package com.exam.model.attendance;

import com.exam.model.exam.Department;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A room whose position was measured once, so sessions held there needn't use the lecturer's own location. */
@Entity
@Table(name = "attendance_classroom")
@Getter @Setter
public class Classroom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String building;

    /** Null = shared by every department. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    /** Students must be within this distance (their reading's accuracy included). */
    @Column(nullable = false)
    private int radiusMeters;

    /** How precise the measurement was, for information. */
    private Integer measuredAccuracyMeters;

    private Long createdById;
    private String createdByName;
    private LocalDateTime updatedAt;
}
