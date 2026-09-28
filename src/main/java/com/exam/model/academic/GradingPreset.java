package com.exam.model.academic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A grading scale (grades + optional classes) saved by the Super Admin to reuse later. */
@Entity
@Table(name = "grading_preset", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
@Getter @Setter
public class GradingPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 300)
    private String description;

    /** JSON array of {letter, minScore, gradePoint, remark, passing}. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String bandsJson;

    /** JSON array of {name, minCgpa}; may be empty (schools have no classes of degree). */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String classesJson;

    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
