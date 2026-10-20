package com.daman.admin.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Throttle and request guard for the ANONYMOUS {@code POST /api/trial/register}.
 *
 * <p>Sliding window, in memory: 5 attempts per IP and 3 per machine id per hour. Only attempts
 * that pass BOTH buckets are recorded, so a blocked caller cannot burn someone else's quota.
 * The table is fed by anonymous input, so it is bounded twice over: expired timestamps (and
 * emptied keys) are swept at most once a minute, and above {@code maxKeys} live keys the limiter
 * fails closed (denies) rather than growing. State is per-process and lost on restart, which is
 * acceptable: the unique index on machine id is the real one-trial-per-machine guarantee.
 *
 * <p>Also owns the body-size cap ({@link #MAX_BODY_BYTES}) so the abuse controls for this one
 * endpoint live in one reviewable place.
 */
@Component
public class TrialRateLimiter {

    public static final int MAX_BODY_BYTES = 8 * 1024;
    static final int IP_LIMIT = 5;
    static final int MACHINE_LIMIT = 3;
    static final Duration WINDOW = Duration.ofHours(1);
    static final int DEFAULT_MAX_KEYS = 100_000;
    private static final long SWEEP_EVERY_MS = 60_000;
    private static final int MAX_KEY_PART = 128;

    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Deque<Long>> hits = new HashMap<>();
    private long lastSweep;

    @Autowired
    public TrialRateLimiter() {
        this(Clock.systemUTC(), DEFAULT_MAX_KEYS);
    }

    TrialRateLimiter(Clock clock) {
        this(clock, DEFAULT_MAX_KEYS);
    }

    TrialRateLimiter(Clock clock, int maxKeys) {
        this.clock = clock;
        this.maxKeys = maxKeys;
        this.lastSweep = clock.millis();
    }

    /** True and recorded when both buckets have room; false (nothing recorded) when either is full. */
    public synchronized boolean allow(String ip, String machineId) {
        long now = clock.millis();
        long cutoff = now - WINDOW.toMillis();
        if (now - lastSweep >= SWEEP_EVERY_MS) {
            sweep(cutoff);
            lastSweep = now;
        }
        String ipKey = "ip:" + clip(ip);
        String machineKey = "m:" + clip(machineId);

        if (count(ipKey, cutoff) >= IP_LIMIT || count(machineKey, cutoff) >= MACHINE_LIMIT) {
            return false;
        }
        int newKeys = (hits.containsKey(ipKey) ? 0 : 1) + (hits.containsKey(machineKey) ? 0 : 1);
        if (hits.size() + newKeys > maxKeys) {
            sweep(cutoff);
            lastSweep = now;
            if (hits.size() + newKeys > maxKeys) {
                return false; // fail closed
            }
        }
        hits.computeIfAbsent(ipKey, k -> new ArrayDeque<>()).addLast(now);
        hits.computeIfAbsent(machineKey, k -> new ArrayDeque<>()).addLast(now);
        return true;
    }

    synchronized int trackedKeys() {
        return hits.size();
    }

    private int count(String key, long cutoff) {
        Deque<Long> q = hits.get(key);
        if (q == null) {
            return 0;
        }
        while (!q.isEmpty() && q.peekFirst() <= cutoff) {
            q.pollFirst();
        }
        return q.size();
    }

    private void sweep(long cutoff) {
        for (Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator(); it.hasNext(); ) {
            Deque<Long> q = it.next().getValue();
            while (!q.isEmpty() && q.peekFirst() <= cutoff) {
                q.pollFirst();
            }
            if (q.isEmpty()) {
                it.remove();
            }
        }
    }

    private static String clip(String s) {
        String v = s == null ? "" : s.trim();
        return v.length() > MAX_KEY_PART ? v.substring(0, MAX_KEY_PART) : v;
    }

    /**
     * Reads at most {@link #MAX_BODY_BYTES} from the stream. Returns null if the body is larger,
     * having read no more than one byte past the cap, so an oversized body is never buffered.
     */
    public static byte[] readCapped(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
        return data.length > MAX_BODY_BYTES ? null : data;
    }
}
