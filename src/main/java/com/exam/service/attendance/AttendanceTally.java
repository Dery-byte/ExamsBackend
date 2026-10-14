package com.exam.service.attendance;

import com.exam.model.attendance.AttendanceStatus;

/**
 * One student's attendance over finished meetings, worked out the same way everywhere (the student's
 * page, the quiz minimum and the reports): present and late count as attended; excused meetings and
 * check-ins still waiting for the lecturer are left out of the percentage; absent and not accepted
 * count against it.
 */
public final class AttendanceTally {

    private int held, attended, late, absent, excused, pending;

    public void add(AttendanceStatus s) {
        if (s == null) return;
        held++;
        switch (s) {
            case PRESENT -> attended++;
            case LATE -> { attended++; late++; }
            case ABSENT, REJECTED -> absent++;
            case EXCUSED -> excused++;
            case PENDING -> pending++;
        }
    }

    public int held() { return held; }
    public int attended() { return attended; }
    public int late() { return late; }
    public int absent() { return absent; }
    public int excused() { return excused; }
    public int pending() { return pending; }

    /** Meetings the percentage is taken over. */
    public int counted() { return held - excused - pending; }

    /** One decimal, or null when no meeting counts yet. */
    public Double percent() {
        int c = counted();
        return c <= 0 ? null : Math.round(attended * 1000.0 / c) / 10.0;
    }

    /** Below the minimum (false while nothing counts yet, so a new course never blocks anyone). */
    public boolean below(int minimumPercent) {
        Double p = percent();
        return p != null && p < minimumPercent;
    }
}
