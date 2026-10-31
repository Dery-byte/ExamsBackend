package com.exam.service.attendance;

import com.exam.service.attendance.AttendanceGeo.Fit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The classroom geofence rule on its own: a 50 m room, readings precise to ±50 m confirm a student. */
class AttendanceGeoTest {

    private static Fit fit(int distance, Integer centre, int accuracy) {
        return AttendanceGeo.judge(distance, centre, 50, 50, accuracy).fit();
    }

    @Test
    void phonesNextToTheLecturerPassWithTypicalIndoorReadings() {
        // The old rule (distance + accuracy within 50 m, accuracy ±30 m or better) refused every one of these
        assertThat(fit(25, 10, 30)).isEqualTo(Fit.INSIDE);
        assertThat(fit(40, 8, 45)).isEqualTo(Fit.INSIDE);
        assertThat(fit(70, 20, 35)).as("both phones ±30-ish m off in opposite directions").isEqualTo(Fit.INSIDE);
        assertThat(fit(0, 8, 50)).isEqualTo(Fit.INSIDE);
    }

    @Test
    void roughOrJustOutsideReadingsAreUnclearNotRefused() {
        assertThat(fit(5, 10, 51)).as("one metre too rough").isEqualTo(Fit.UNCLEAR);
        assertThat(fit(5, 10, 400)).as("Wi-Fi-only reading indoors").isEqualTo(Fit.UNCLEAR);
        assertThat(fit(90, 10, 30)).isEqualTo(Fit.UNCLEAR);
        assertThat(fit(70, 5, 5)).as("a confident reading 20 m past the edge").isEqualTo(Fit.UNCLEAR);
    }

    @Test
    void clearlyElsewhereIsOutside() {
        assertThat(fit(150, 8, 5)).isEqualTo(Fit.OUTSIDE);
        assertThat(fit(400, 10, 200)).as("the unclear margin is capped at 300 m").isEqualTo(Fit.OUTSIDE);
        assertThat(fit(300, 10, 200)).isEqualTo(Fit.UNCLEAR);
        assertThat(fit(3000, 10, 10)).isEqualTo(Fit.OUTSIDE);
    }

    @Test
    void theCentresOwnErrorCountsWithinLimits() {
        // Unknown centre error is taken as ±15 m
        assertThat(AttendanceGeo.judge(0, null, 50, 50, 20).combined()).isEqualTo(25);
        // A very rough saved room counts as ±50 m at most, so it can't stretch the room without limit
        assertThat(AttendanceGeo.judge(0, 900, 50, 50, 0).combined()).isEqualTo(50);
        assertThat(fit(101, 900, 20)).as("radius + at most one radius").isEqualTo(Fit.UNCLEAR);
        assertThat(fit(100, 900, 20)).isEqualTo(Fit.INSIDE);
    }

    @Test
    void realCoordinatesAreMeasuredInMetres() {
        // 0.0009° of latitude is about 100 m
        AttendanceGeo.Verdict v = AttendanceGeo.judge(5.6037, -0.1870, 8, 50, 50, 5.6037 + 0.0009, -0.1870, 10);
        assertThat(v.distance()).isBetween(99, 101);
        assertThat(v.fit()).as("100 m ± 10 m is past even the unclear margin (3 × 13 m)").isEqualTo(Fit.OUTSIDE);
    }
}
