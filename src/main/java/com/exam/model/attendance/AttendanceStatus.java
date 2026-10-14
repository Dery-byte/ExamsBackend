package com.exam.model.attendance;

/** A student's attendance at one class meeting. PENDING waits for the lecturer to approve it. */
public enum AttendanceStatus {
    PRESENT, LATE, PENDING, REJECTED, EXCUSED, ABSENT;

    /** Counts as attended in the attendance percentage. */
    public boolean attended() { return this == PRESENT || this == LATE; }
}
