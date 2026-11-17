package com.exam.model.materials;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A student opened or downloaded a material ("34 of 52 opened this"). */
@Entity
@Table(name = "material_view",
        uniqueConstraints = @UniqueConstraint(columnNames = {"material_id", "student_id"}))
@Getter @Setter
public class MaterialView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    private LocalDateTime firstAt = LocalDateTime.now();
    private LocalDateTime lastAt = LocalDateTime.now();
    @Column(name = "open_count", nullable = false)
    private int openCount = 1;
}
