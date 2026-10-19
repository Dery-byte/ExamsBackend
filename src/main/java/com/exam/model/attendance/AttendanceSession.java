package com.exam.model.attendance;

import com.exam.model.User;
import com.exam.model.academic.AcademicSession;
import com.exam.model.exam.Category;
import com.exam.model.exam.Department;
import com.exam.model.exam.Program;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One class meeting where attendance is taken. The rules in force when it started (code timing,
 * distance, accuracy, attempts) are copied in, so changing the policy never changes a running class.
 * <p>
 * Timeline: check-in is open from {@code openedAt} to {@code checkInClosesAt}; an optional second
 * check runs from {@code secondCheckOpensAt} to {@code secondCheckClosesAt}; the meeting is
 * finalised (absentees recorded) when the lecturer ends it or {@code endsAt} passes.
 */
@Entity
@Table(name = "attendance_session", indexes = {
        @Index(name = "idx_att_session_open", columnList = "closed_at, ends_at"),
        @Index(name = "idx_att_session_course", columnList = "course_id, academic_session_id"),
        @Index(name = "idx_att_session_program", columnList = "program_id, academic_session_id")
})
@Getter @Setter
public class AttendanceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceMode scope;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "course_id")
    private Category course;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "program_id")
    private Program program;

    /** Programme sessions only; null = every level of the programme. */
    private Integer level;

    /** Department whose rules apply and whose HOD oversees the session (null for a course with no department). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "academic_session_id")
    private AcademicSession academicSession;

    private Integer semester;

    /** 1, 2, 3 … within the course (or programme and level) this academic session. */
    private int meetingNumber;

    @Column(length = 200)
    private String topic;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "opened_by_id")
    private User openedBy;

    private Long scheduleId;

    /** Typed code or staff scanning. Null on meetings from before scanning existed = typed code. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AttendanceMethod method;

    public AttendanceMethod getMethod() { return method == null ? AttendanceMethod.STUDENT_CODE : method; }

    /** Staff scan each student's personal QR code. */
    public boolean isScanned() { return getMethod() == AttendanceMethod.STAFF_SCAN; }

    /** Students scan the class QR code that staff show (otherwise like a typed code). */
    public boolean isStudentScan() { return getMethod() == AttendanceMethod.STUDENT_SCAN; }

    // ── Code ─────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CodeDisplay codeDisplay = CodeDisplay.PROJECTOR;

    /** Secret the codes are derived from. Never sent to a browser. */
    @Column(nullable = false, length = 64)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String secret;

    private int codeSeconds;
    private int graceSeconds;
    private int maxFailedAttempts;

    // ── Place ────────────────────────────────────────────────────────────────

    private Long classroomId;
    @Column(length = 200)
    private String placeName;
    private double latitude;
    private double longitude;
    private int radiusMeters;
    private int maxAccuracyMeters;

    // ── Timeline ─────────────────────────────────────────────────────────────

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;
    @Column(nullable = false)
    private LocalDateTime checkInClosesAt;
    @Column(nullable = false)
    private LocalDateTime lateAfter;
    private LocalDateTime secondCheckOpensAt;
    private LocalDateTime secondCheckClosesAt;
    /** How long a second check stays open (also when the lecturer starts one by hand). */
    private int secondCheckMinutes;
    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;
    /** Set when the meeting is finalised; null while it is live. */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** Students the lecturer counted in the room. */
    private Integer headcount;

    public boolean isLive() { return closedAt == null; }
}
