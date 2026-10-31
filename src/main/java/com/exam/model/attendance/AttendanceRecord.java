package com.exam.model.attendance;

import com.exam.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A student's attendance at one meeting. Only the distance from the classroom and the reading's
 * accuracy are kept, never the student's coordinates.
 * One phone checks in one student per meeting (unique session + device).
 */
@Entity
@Table(name = "attendance_record",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_att_record_student", columnNames = {"session_id", "student_id"}),
                @UniqueConstraint(name = "uk_att_record_device", columnNames = {"session_id", "device_hash"})
        },
        indexes = @Index(name = "idx_att_record_student", columnList = "student_id"))
@Getter @Setter
public class AttendanceRecord {

    public static final String METHOD_CODE = "CODE";
    public static final String METHOD_MANUAL = "MANUAL";
    public static final String METHOD_AUTO = "AUTO";
    /** Staff scanned the student's QR code (reviewedBy holds who scanned). */
    public static final String METHOD_SCAN = "SCAN";

    public static final String FLAG_LATE = "LATE";
    public static final String FLAG_NEW_PHONE = "NEW_PHONE";
    public static final String FLAG_MISSED_SECOND_CHECK = "MISSED_SECOND_CHECK";
    /**
     * The phone's location couldn't confirm the student (too rough, or just outside), so the check is
     * waiting for the lecturer. Cleared as soon as the student is Present or Late: by a later precise
     * reading while that check is open, the lecturer's approval or marking, or a staff scan.
     */
    public static final String FLAG_LOCATION_UNCLEAR = "LOCATION_UNCLEAR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private AttendanceSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private User student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    private LocalDateTime firstCheckAt;
    private Integer firstDistanceMeters;
    private Integer firstAccuracyMeters;

    private LocalDateTime secondCheckAt;
    private Integer secondDistanceMeters;
    private Integer secondAccuracyMeters;

    /** Students scan the class QR: whose QR they scanned (the lecturer's screen or a helper's phone), per check. */
    @Column(length = 120)
    private String qrSource;
    @Column(length = 120)
    private String secondQrSource;

    @Column(name = "device_hash", length = 64)
    private String deviceHash;

    @Column(length = 64)
    private String ipAddress;

    /** Comma-separated FLAG_* values. */
    @Column(length = 200)
    private String flags;

    /** CODE (student checked in), MANUAL (marked by staff) or AUTO (absent when the meeting ended). */
    @Column(nullable = false, length = 10)
    private String method;

    private String reviewedBy;
    private LocalDateTime reviewedAt;

    @Column(length = 300)
    private String note;

    public boolean hasFlag(String flag) {
        return flags != null && java.util.Arrays.asList(flags.split(",")).contains(flag);
    }

    public void addFlag(String flag) {
        if (hasFlag(flag)) return;
        flags = flags == null || flags.isBlank() ? flag : flags + "," + flag;
    }

    public void removeFlag(String flag) {
        if (!hasFlag(flag)) return;
        String rest = java.util.Arrays.stream(flags.split(",")).filter(f -> !f.equals(flag)).collect(java.util.stream.Collectors.joining(","));
        flags = rest.isEmpty() ? null : rest;
    }
}
