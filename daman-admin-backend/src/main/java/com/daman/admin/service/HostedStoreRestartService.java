package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The shared-jar release walk: restarts every ACTIVE hosted store one at a time. Lives outside the
 * controller so a background caller can run it too. It decides no HTTP status; the caller maps a
 * halt (any {@code walked} entry with {@code healthy() == false}) to whatever it needs.
 */
@Service
@Slf4j
public class HostedStoreRestartService {

    /** A fresh store boots Spring in up to ~a minute; poll this long before calling it unhealthy. */
    private static final int HEALTH_ATTEMPTS = 40;
    private static final long HEALTH_INTERVAL_MS = 3000;

    private final HostedInstanceRepository repository;
    private final ServerOps ops;
    private final int healthAttempts;
    private final long healthIntervalMs;

    @Autowired
    public HostedStoreRestartService(HostedInstanceRepository repository, ServerOps ops) {
        this(repository, ops, HEALTH_ATTEMPTS, HEALTH_INTERVAL_MS);
    }

    /** Test seam over the health-poll constants. */
    public HostedStoreRestartService(HostedInstanceRepository repository, ServerOps ops,
                                     int healthAttempts, long healthIntervalMs) {
        this.repository = repository;
        this.ops = ops;
        this.healthAttempts = healthAttempts;
        this.healthIntervalMs = healthIntervalMs;
    }

    /** One walked store. A store never reached (after a halt) has no entry at all. */
    public record RestartResult(String clientCode, boolean restarted, boolean healthy, String error) {}

    /** One store the release walk deliberately did not touch, and why. */
    public record SkippedStore(String clientCode, String reason) {}

    /**
     * The whole outcome of a release walk: what was restarted, and what was passed over. The
     * skipped list exists because an empty {@code walked} list used to be indistinguishable from a
     * successful release — the script printed "every hosted store restarted healthy" when it had
     * restarted nothing at all, which is what a stale registry (a store stuck at {@code FAILED}
     * while its unit runs fine) actually produces. It also makes the viora exclusion auditable at
     * release time instead of only in a unit test.
     */
    public record RestartReport(List<RestartResult> walked, List<SkippedStore> skipped) {}

    /**
     * Restarts every ACTIVE store one at a time, polling its health before moving to the next, and
     * HALTS at the first store that fails to restart or come up. That delays and sequences the
     * exposure to a bad jar; it does NOT contain it: the new jar is already on disk and every unit
     * has {@code Restart=always}, so a store not yet reached loads it on any restart, and the health
     * check only catches "does not boot". Stores are walked in id order (oldest first, so the oldest
     * store is the canary).
     *
     * <p>{@code walked} is the stores actually restarted (the unhealthy one last; stores after a
     * halt are untouched and absent), and {@code skipped} is every registry row deliberately passed
     * over, with the reason. An empty {@code walked} list means the release restarted <b>nothing</b>.
     */
    public RestartReport walk() {
        return walk(clientCode -> {});
    }

    /**
     * As {@link #walk()}, calling {@code onStoreStarting} with a store's client code just before
     * that store is restarted, so a caller can show which store a long walk is on.
     */
    public RestartReport walk(java.util.function.Consumer<String> onStoreStarting) {
        List<HostedInstance> rows = repository.findAll().stream()
                .sorted(java.util.Comparator.comparing(HostedInstance::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        List<RestartResult> walked = new java.util.ArrayList<>();
        List<SkippedStore> skipped = new java.util.ArrayList<>();
        for (HostedInstance i : rows) {
            String skipReason = skipReason(i);
            if (skipReason != null) {
                skipped.add(new SkippedStore(i.getClientCode(), skipReason));
                continue;
            }
            onStoreStarting.accept(i.getClientCode());
            RestartResult r = restartOne(i);
            walked.add(r);
            if (!r.healthy()) {
                // Halt: every row after this one is neither walked nor "skipped by rule" — it was
                // simply never reached, which the halt itself says.
                return new RestartReport(walked, skipped);
            }
        }
        if (walked.isEmpty()) {
            log.warn("restart-all restarted NOTHING: {} registry row(s), all skipped {}",
                    rows.size(), skipped);
        }
        return new RestartReport(walked, skipped);
    }

    /**
     * Why this row is not part of a shared-jar release, or null when it is.
     *
     * <p>Status is now the only reason. viora used to be excluded here by service name, because
     * its unit ran its own {@code /root/daman-store-viora.jar}; it was moved onto the shared jar
     * on 2026-10-26 (it was the only store a release could not reach, and so the only one that
     * never received a backend fix). Its unit is still the one hand-written unit on the box — it
     * keeps the legacy {@code /root/.daman/viora} data directory and therefore carries no
     * {@code -Duser.home} — so it must never be re-rendered by {@code StoreUnitRenderer}. That is
     * guarded elsewhere, by {@code viora} being a reserved client code that provisioning refuses.
     */
    private static String skipReason(HostedInstance i) {
        if (!"ACTIVE".equals(i.getStatus())) {
            return "not ACTIVE (" + i.getStatus()
                    + (i.getFailedStage() == null ? "" : " at stage " + i.getFailedStage()) + ")";
        }
        return null;
    }

    private RestartResult restartOne(HostedInstance i) {
        try {
            ops.restart(i.getServiceName());
        } catch (RuntimeException e) {
            log.warn("Restart of {} failed", i.getServiceName(), e);
            return new RestartResult(i.getClientCode(), false, false,
                    "restart failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        for (int attempt = 1; attempt <= healthAttempts; attempt++) {
            if (ops.health(i.getPort())) {
                return new RestartResult(i.getClientCode(), true, true, null);
            }
            if (attempt < healthAttempts && healthIntervalMs > 0) {
                try {
                    Thread.sleep(healthIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new RestartResult(i.getClientCode(), true, false,
                "restarted but /api/health never answered 200 within " + healthAttempts + " polls");
    }
}
