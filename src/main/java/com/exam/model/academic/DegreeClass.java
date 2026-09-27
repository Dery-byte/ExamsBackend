package com.exam.model.academic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Class of degree awarded for a CGPA of at least {@code minCgpa}, e.g. "First Class" from 3.60. */
@Entity
@Table(name = "degree_class")
@Getter @Setter
public class DegreeClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal minCgpa;
}
