package com.daman.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Throttle and request guard for the ANONYMOUS {@code POST /api/trial/register}.
 *
 * <p>Sliding window, in memory: 5 attempts per IP and 3 per machine id per hour. Only attempts
 * that pass BOTH buckets are recorded, so a blocked caller cannot burn someone else's quota.
 * The table is fed by anonymous input, so it is bounded twice over: expired timestamps (and
 * emptied keys) are swept at most once a minute, and above {@code maxKeys} live keys the limiter
 * the limiter evicts the least-recently-used keys down to ~90% of the cap (logged at ERROR) rather than
 * growing or denying everyone. State is per-process and lost on restart, which is
 * acceptable: the unique index on machine id is the real one-trial-per-machine guarantee.
 *
 * <p>Also owns the body-size cap ({@link #MAX_BODY_BYTES}) so the abuse controls for this one
 * endpoint live in one reviewable place.
 */
@Slf4j
@Component
public class TrialRateLimiter {

    public static final int MAX_BODY_BYTES = 8 * 1024;
    static final int IP_LIMIT = 5;
    static final int MACHINE_LIMIT = 3;
    static final int RESTORE_IP_LIMIT = 20;
    static final int RESTORE_MACHINE_LIMIT = 10;
    static final Duration WINDOW = Duration.ofHours(1);
    static final int DEFAULT_MAX_KEYS = 100_000;
    private static final long SWEEP_EVERY_MS = 60_000;
    private static final int MAX_KEY_PART = 128;

    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Deque<Long>> hits = new LinkedHashMap<>();
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
        return allow("ip:", IP_LIMIT, "m:", MACHINE_LIMIT, ip, machineId);
    }

    /**
     * Separate, more generous bucket for {@code restoreOnly} probes.
     *
     * <p>It must not share the registration bucket: the desktop probes once per "start a trial"
     * click, so three clicks would exhaust {@link #MACHINE_LIMIT} and leave the user unable to
     * actually register - the limiter would have blocked the very thing it exists to protect.
     *
     * <p>The higher limit does not widen the read-oracle noted in the design: a probe returns the
     * same key an ordinary registration already returns for a known machine id, and a machine id
     * is a hardware fingerprint - not guessable and not enumerable. An attacker who already has
     * one needs a single request, so 3/hour and 10/hour are equivalent to them; the limit only
     * ever matters to a real user clicking a button.
     */
    public synchronized boolean allowRestore(String ip, String machineId) {
        return allow("rip:", RESTORE_IP_LIMIT, "rm:", RESTORE_MACHINE_LIMIT, ip, machineId);
    }

    private boolean allow(String ipPrefix, int ipLimit, String machinePrefix, int machineLimit,
                          String ip, String machineId) {
        long now = clock.millis();
        long cutoff = now - WINDOW.toMillis();
        if (now - lastSweep >= SWEEP_EVERY_MS) {
            sweep(cutoff);
            lastSweep = now;
        }
        String ipKey = ipPrefix + clip(ip);
        String machineKey = machinePrefix + clip(machineId);

        if (count(ipKey, cutoff) >= ipLimit || count(machineKey, cutoff) >= machineLimit) {
            return false;
        }
        evictIfFull();
        record(ipKey, now);
        record(machineKey, now);
        return true;
    }

    /** Appends a hit and moves the key to the tail, so the head is always the least recently used key. */
    private void record(String key, long now) {
        Deque<Long> q = hits.remove(key);
        if (q == null) {
            q = new ArrayDeque<>();
        }
        q.addLast(now);
        hits.put(key, q);
    }

    /**
     * At the cap, drop the least recently used keys down to ~90% and carry on serving. A caller is
     * never refused because OTHER keys exist; the unique machineId index is the real guarantee, so
     * this map is a convenience and must not become a global kill switch. O(evicted), not O(n),
     * and it only runs when the table is full.
     */
    private void evictIfFull() {
        if (hits.size() + 2 <= maxKeys) {
            return;
        }
        int before = hits.size();
        int target = Math.max(0, (int) (maxKeys * 0.9) - 2);
        Iterator<String> it = hits.keySet().iterator();
        while (hits.size() > target && it.hasNext()) {
            it.next();
            it.remove();
        }
        log.error("Trial rate limiter reached its key cap ({} keys, max {}); evicted {} least-recently-used keys. "
                + "A very large number of distinct sources is hitting /api/trial/register.",
                before, maxKeys, before - hits.size());
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
