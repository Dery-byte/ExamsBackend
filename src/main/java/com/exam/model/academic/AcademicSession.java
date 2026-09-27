package com.exam.model.academic;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** An academic year, e.g. "2025/2026". Exactly one session is current at a time. */
@Entity
@Table(name = "academic_session", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
@Getter @Setter
public class AcademicSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(name = "is_current", nullable = false)
    private boolean current = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
