package com.exam.model.attendance;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A student's location reading during one meeting and how it fitted the room, so the lecturer can
 * see who is trying and why it isn't working. Like the attendance record, only the distance from
 * the room and the accuracy are kept, never coordinates. Rows are only ever added (no clashes when
 * a phone sends two at once); the newest per student is what counts.
 */
@Entity
@Table(name = "attendance_place_log", indexes = @Index(name = "idx_att_place_session", columnList = "session_id, student_id"))
@Getter @Setter
public class AttendancePlaceLog {

    public static final String SOURCE_LOCATE = "LOCATE";
    public static final String SOURCE_CHECK_IN = "CHECK_IN";
    public static final String SOURCE_QR_KEY = "QR_KEY";

    /** Not an {@link com.exam.service.attendance.AttendanceGeo.Fit}: the phone gave only an approximate (internet) location. */
    public static final String FIT_NO_GPS = "NO_GPS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private AttendanceSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private User student;

    /** INSIDE, UNCLEAR, OUTSIDE or NO_GPS. */
    @Column(nullable = false, length = 12)
    private String fit;

    private Integer distanceMeters;
    private Integer accuracyMeters;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(nullable = false)
    private LocalDateTime checkedAt;
}
