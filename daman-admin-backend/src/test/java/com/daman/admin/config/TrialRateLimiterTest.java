package com.daman.admin.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TrialRateLimiterTest {

    /** Mutable clock so the recovery tests never sleep. */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private MutableClock clock;
    private TrialRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        limiter = new TrialRateLimiter(clock);
    }

    @Test
    void allowsTheFirstFewAttempts() {
        assertThat(limiter.allow("1.2.3.4", "M1")).isTrue();
    }

    @Test
    void blocksAfterTheIpBurstIsExhausted() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allow("1.2.3.4", "M" + i)).isTrue();
        }
        assertThat(limiter.allow("1.2.3.4", "M99")).isFalse();
    }

    @Test
    void blocksAfterTheMachineIdBurstIsExhausted() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.allow("1.2.3." + i, "M1")).isTrue();
        }
        assertThat(limiter.allow("9.9.9.9", "M1")).isFalse();
    }

    @Test
    void recoversAfterTheWindowPasses() {
        for (int i = 0; i < 5; i++) {
            limiter.allow("1.2.3.4", "M" + i);
        }
        assertThat(limiter.allow("1.2.3.4", "M99")).isFalse();
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(limiter.allow("1.2.3.4", "M99")).isTrue();
    }

    @Test
    void oneMachineCannotExhaustAnother() {
        for (int i = 0; i < 3; i++) {
            limiter.allow("1.2.3." + i, "M1");
        }
        assertThat(limiter.allow("5.5.5.5", "M2")).isTrue();
    }

    @Test
    void aBlockedAttemptDoesNotBurnTheOtherBucket() {
        for (int i = 0; i < 3; i++) {
            limiter.allow("1.2.3." + i, "M1");
        }
        // Blocked on the machine bucket: must not have been counted against 7.7.7.7.
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.allow("7.7.7.7", "M1")).isFalse();
        }
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allow("7.7.7.7", "Fresh" + i)).isTrue();
        }
    }

    @Test
    void expiredEntriesAreEvictedSoTheMapCannotGrowWithoutBound() {
        for (int i = 0; i < 500; i++) {
            limiter.allow("10.0." + (i / 200) + "." + (i % 200), "M" + i);
        }
        assertThat(limiter.trackedKeys()).isGreaterThan(500);

        clock.advance(Duration.ofHours(2));
        limiter.allow("1.1.1.1", "Fresh");

        assertThat(limiter.trackedKeys()).isEqualTo(2); // only 1.1.1.1 and Fresh remain
    }

    @Test
    void failsClosedWhenTheKeyTableIsFull() {
        TrialRateLimiter tiny = new TrialRateLimiter(clock, 10);
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (tiny.allow("10.0.0." + i, "M" + i)) allowed++;
        }
        assertThat(allowed).isLessThan(100);
        assertThat(tiny.trackedKeys()).isLessThanOrEqualTo(10);
    }

    @Test
    void ipAndMachineKeyspacesDoNotCollide() {
        // A machine id textually equal to an IP must not share a bucket with that IP.
        for (int i = 0; i < 3; i++) {
            limiter.allow("1.2.3." + i, "1.2.3.4");
        }
        assertThat(limiter.allow("1.2.3.4", "other")).isTrue();
    }

    @Test
    void aNullMachineIdIsHandled() {
        assertThat(limiter.allow("1.2.3.4", null)).isTrue();
    }
}
