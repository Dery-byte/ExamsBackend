package com.exam.model.materials;

import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Category;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One course's materials for one academic session: the lecturer's switches for the whole course
 * (shown to students, hidden during a quiz, weekly automatic release) and its week calendar.
 */
@Entity
@Table(name = "course_space",
        uniqueConstraints = @UniqueConstraint(columnNames = {"course_id", "session_id"}))
@Getter @Setter
public class CourseSpace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "course_id")
    private Category course;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "session_id")
    private AcademicSession academicSession;

    /** The lecturer's master switch: students see this course's materials at all. */
    @Column(nullable = false)
    @ColumnDefault("1")
    private boolean visibleToStudents = true;

    /** While a student is taking a quiz of this course, that student can't open its materials. */
    @Column(nullable = false)
    @ColumnDefault("1")
    private boolean hideDuringQuiz = true;

    /** Week 1's first day when the term calendar has none (or to override it for this course). */
    private LocalDate weekOneStart;

    /** Number of weeks shown when the term calendar has none. */
    private Integer weeksCount;

    /** Weekly automatic release: future weeks open on this day and time of their own week. */
    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean autoRelease = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private DayOfWeek autoReleaseDay;

    private LocalTime autoReleaseTime;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;
}
