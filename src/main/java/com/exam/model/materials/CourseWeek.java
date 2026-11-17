package com.exam.model.materials;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A week of a course's materials (week 0 = "General": course outline, reading list). Only weeks the
 * lecturer has named, hidden or scheduled have a row; a week without one is open with no topic.
 */
@Entity
@Table(name = "course_week",
        uniqueConstraints = @UniqueConstraint(columnNames = {"space_id", "number"}))
@Getter @Setter
public class CourseWeek {

    public enum State { OPEN, HIDDEN, SCHEDULED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private CourseSpace space;

    @Column(nullable = false)
    private int number;

    @Column(length = 200)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private State state = State.OPEN;

    /** SCHEDULED: the week opens at this time. */
    private LocalDateTime releaseAt;

    private LocalDateTime updatedAt;
}
