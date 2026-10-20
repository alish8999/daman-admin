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

    @Test
    void atTheCapItEvictsTheStalestKeysInsteadOfDenyingNewCallers() {
        TrialRateLimiter tiny = new TrialRateLimiter(clock, 10);
        for (int i = 0; i < 100; i++) {
            assertThat(tiny.allow("10.0.0." + i, "M" + i)).isTrue(); // never refused for other keys' sake
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(tiny.trackedKeys()).isLessThanOrEqualTo(10);
    }

    @Test
    void evictionDropsTheOldestAndKeepsTheNewestEnforced() {
        TrialRateLimiter tiny = new TrialRateLimiter(clock, 10);
        for (int i = 0; i < 100; i++) {
            tiny.allow("10.0.0." + i, "M" + i);
            clock.advance(Duration.ofSeconds(1));
        }
        // The most recent machine is still tracked: burn its 3 allowances from fresh IPs.
        assertThat(tiny.allow("9.9.9.1", "M99")).isTrue();
        assertThat(tiny.allow("9.9.9.2", "M99")).isTrue();
        assertThat(tiny.allow("9.9.9.3", "M99")).isFalse();
    }

    @Test
    void evictionIsLoggedAtErrorWithTheKeyCount() {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TrialRateLimiter.class))
                .addAppender(appender);
        TrialRateLimiter tiny = new TrialRateLimiter(clock, 10);
        for (int i = 0; i < 20; i++) {
            tiny.allow("10.0.0." + i, "M" + i);
        }
        var errors = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).contains("10");
    }

    /** InputStream whose available()/length hints lie; only the bytes matter. */
    private static java.io.InputStream stream(int n) {
        return new java.io.ByteArrayInputStream(new byte[n]) {
            @Override public synchronized int available() { return 0; }
        };
    }

    @Test
    void readCappedReturnsABodyOfExactlyTheCap() throws Exception {
        assertThat(TrialRateLimiter.readCapped(stream(TrialRateLimiter.MAX_BODY_BYTES)))
                .hasSize(TrialRateLimiter.MAX_BODY_BYTES);
    }

    @Test
    void readCappedReturnsNullOneByteOverTheCapEvenIfTheStreamHidesItsLength() throws Exception {
        assertThat(TrialRateLimiter.readCapped(stream(TrialRateLimiter.MAX_BODY_BYTES + 1))).isNull();
    }

    @Test
    void readCappedNeverReadsMuchPastTheCapFromAHugeStream() throws Exception {
        java.util.concurrent.atomic.AtomicLong read = new java.util.concurrent.atomic.AtomicLong();
        java.io.InputStream endless = new java.io.InputStream() {
            @Override public int read() { read.incrementAndGet(); return 'x'; }
            @Override public int read(byte[] b, int off, int len) { read.addAndGet(len); java.util.Arrays.fill(b, off, off + len, (byte) 'x'); return len; }
        };
        assertThat(TrialRateLimiter.readCapped(endless)).isNull();
        assertThat(read.get()).isLessThan(TrialRateLimiter.MAX_BODY_BYTES * 4L);
    }
}
