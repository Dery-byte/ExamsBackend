package com.exam.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The limiter behind the access-code and sign-in limits, and how waits are worded. */
class RateLimiterTest {

    @Test
    void aOneMinuteWindowNeverAsksForMoreThanAMinute() {
        RateLimiter limiter = new RateLimiter();
        String key = "quiz-unlock:7:1";
        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire(key, 5, 60_000L)).isTrue();
        assertThat(limiter.tryAcquire(key, 5, 60_000L)).isFalse();   // the 6th try in the minute
        assertThat(limiter.retryAfterSeconds(key, 60_000L)).isBetween(1L, 60L);
    }

    @Test
    void aFiveMinuteWindowNeverAsksForMoreThanFiveMinutes() {
        RateLimiter limiter = new RateLimiter();
        String key = "login-fail:stu";
        for (int i = 0; i < 5; i++) limiter.record(key);
        assertThat(limiter.count(key, 300_000L)).isEqualTo(5);
        assertThat(limiter.retryAfterSeconds(key, 300_000L)).isBetween(1L, 300L);
    }

    @Test
    void waitsAreWordedForPeople() {
        assertThat(RateLimiter.describeWait(1)).isEqualTo("1 second");
        assertThat(RateLimiter.describeWait(45)).isEqualTo("45 seconds");
        assertThat(RateLimiter.describeWait(60)).isEqualTo("1 minute");
        assertThat(RateLimiter.describeWait(245)).isEqualTo("4 min 05 s");
        assertThat(RateLimiter.describeWait(300)).isEqualTo("5 minutes");
    }
}
