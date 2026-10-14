package com.exam.model.attendance;

import com.exam.model.exam.Department;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * Attendance rules. The row without a department is the system default (Super Admin); a row with a
 * department is that department's own choice (its HOD or the Super Admin), used instead of the default.
 */
@Entity
@Table(name = "attendance_policy")
@Getter @Setter
public class AttendancePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null = the system default. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id", unique = true)
    private Department department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceMode mode = AttendanceMode.COURSE;

    /** A projector code is replaced this often. */
    private int codeSeconds = 15;
    /** A read-aloud code (no projector) is replaced this often. */
    private int readAloudCodeSeconds = 90;
    /** The previous code is still accepted this long after it was replaced (time to finish typing). */
    private int graceSeconds = 5;

    /** Students can check in for this long after the session starts. */
    private int checkInMinutes = 15;
    /** Checking in later than this after the start counts as Late. */
    private int lateAfterMinutes = 10;

    /** Default allowed distance when the lecturer's own location is used (saved classrooms have their own). */
    private int radiusMeters = 50;
    /** Location readings less precise than this are refused. */
    private int maxAccuracyMeters = 30;
    /** Wrong or expired codes allowed per session before the student is locked out of it. */
    private int maxFailedAttempts = 5;

    /** A second code appears at a random time during class; both checks are needed for Present. */
    private boolean secondCheck = true;
    /** How long the second check stays open. */
    private int secondCheckMinutes = 3;

    /**
     * Students below {@link #minimumPercent} can't start a quiz: in course mode, any quiz of that course;
     * in programme mode (the student's programme's department), any quiz at all.
     */
    @ColumnDefault("0")
    private boolean minimumRequired = false;
    @ColumnDefault("75")
    private int minimumPercent = 75;

    private String updatedBy;
    private LocalDateTime updatedAt;

    /** A copy carrying the same rules, used to start a department's own policy from the default. */
    public AttendancePolicy copyRules() {
        AttendancePolicy p = new AttendancePolicy();
        p.mode = mode;
        p.codeSeconds = codeSeconds;
        p.readAloudCodeSeconds = readAloudCodeSeconds;
        p.graceSeconds = graceSeconds;
        p.checkInMinutes = checkInMinutes;
        p.lateAfterMinutes = lateAfterMinutes;
        p.radiusMeters = radiusMeters;
        p.maxAccuracyMeters = maxAccuracyMeters;
        p.maxFailedAttempts = maxFailedAttempts;
        p.secondCheck = secondCheck;
        p.secondCheckMinutes = secondCheckMinutes;
        p.minimumRequired = minimumRequired;
        p.minimumPercent = minimumPercent;
        return p;
    }
}
