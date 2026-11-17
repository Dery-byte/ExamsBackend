package com.exam.model.materials;

import com.exam.model.academic.AcademicSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * When a term (semester) of an academic session starts and how many teaching weeks it has, so
 * course materials can show real dates ("Week 5 · 10–14 Nov"). Set by the Super Admin.
 */
@Entity
@Table(name = "term_calendar",
        uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "semester"}))
@Getter @Setter
public class TermCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "session_id")
    private AcademicSession academicSession;

    /** 1, 2 or 3 (the course's semester / term). */
    @Column(nullable = false)
    private int semester;

    /** The first day of week 1 (any day; weeks are counted from its Monday). */
    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    @ColumnDefault("14")
    private int weeks = 14;

    private String updatedBy;
    private LocalDateTime updatedAt;
}
