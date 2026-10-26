package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Per-store lifecycle actions on the hosted-store fleet: suspend, resume, restart. Every OS fact
 * goes through {@link ServerOps}; this class never shells out.
 *
 * <p><b>Suspend is not a stop.</b> Every store unit is {@code enabled} with {@code Restart=always},
 * so a plain {@code systemctl stop} comes back on the next reboot and a release walk would restart
 * it. Suspend is {@code disable --now} <em>plus</em> the {@code SUSPENDED} registry status, which
 * the release walk and {@link HostedStoreProvisioningService#prepare} both honour.
 *
 * <p>Every wait here is bounded to {@link #HEALTH_ATTEMPTS} x {@link #HEALTH_INTERVAL_MS} = 75 s:
 * these calls cross Cloudflare's edge, which answers 524 after 100 s of origin silence.
 */
@Service
@Slf4j
public class HostedStoreLifecycleService {

    static final int HEALTH_ATTEMPTS = 25;
    static final long HEALTH_INTERVAL_MS = 3000;

    static final String ACTIVE = "ACTIVE";
    static final String SUSPENDED = "SUSPENDED";
    /** Result status for an action that ran but whose store never answered /api/health. */
    public static final String UNHEALTHY = "UNHEALTHY";

    /** {@code status} is the registry status, or {@link #UNHEALTHY} when the health wait failed. */
    public record LifecycleResult(String clientCode, String status, String message) {}

    /** No registry row for the client (404). */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** The row's current status does not allow this action (409). */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private final HostedInstanceRepository repository;
    private final ServerOps ops;
    private final int healthAttempts;
    private final long healthIntervalMs;

    @Autowired
    public HostedStoreLifecycleService(HostedInstanceRepository repository, ServerOps ops) {
        this(repository, ops, HEALTH_ATTEMPTS, HEALTH_INTERVAL_MS);
    }

    /** Public so controller tests in another package can inject a no-clock wait; not for production wiring. */
    public HostedStoreLifecycleService(HostedInstanceRepository repository, ServerOps ops,
                                int healthAttempts, long healthIntervalMs) {
        this.repository = repository;
        this.ops = ops;
        this.healthAttempts = healthAttempts;
        this.healthIntervalMs = healthIntervalMs;
    }

    /** Idempotent: suspending a suspended store reports success and touches nothing. */
    public LifecycleResult suspend(String clientCode) {
        HostedInstance row = find(clientCode);
        if (SUSPENDED.equals(row.getStatus())) {
            return new LifecycleResult(clientCode, SUSPENDED, "Already suspended.");
        }
        if (!ACTIVE.equals(row.getStatus())) {
            throw new ConflictException("Hosted store '" + clientCode + "' is " + row.getStatus()
                    + ", not ACTIVE; only an ACTIVE store can be suspended.");
        }
        ops.stopAndDisable(row.getServiceName());
        row.setStatus(SUSPENDED);
        repository.save(row);
        log.warn("Suspended hosted store '{}' (unit {} disabled and stopped)", clientCode, row.getServiceName());
        return new LifecycleResult(clientCode, SUSPENDED,
                "Suspended: unit stopped and disabled, so a reboot or a release will not revive it.");
    }

    /**
     * Idempotent: resuming a running store reports success. The row is marked ACTIVE only once
     * {@code /api/health} answers; if it never does the row stays SUSPENDED and the unit is left
     * enabled and running so the operator can inspect it or simply retry Resume.
     */
    public LifecycleResult resume(String clientCode) {
        HostedInstance row = find(clientCode);
        if (ACTIVE.equals(row.getStatus())) {
            return new LifecycleResult(clientCode, ACTIVE, "Already running.");
        }
        if (!SUSPENDED.equals(row.getStatus())) {
            throw new ConflictException("Hosted store '" + clientCode + "' is " + row.getStatus()
                    + ", not SUSPENDED; only a suspended store can be resumed.");
        }
        ops.enableAndStart(row.getServiceName());
        if (!waitHealthy(row.getPort())) {
            return new LifecycleResult(clientCode, UNHEALTHY, "Unit enabled and started, but /api/health"
                    + " never answered 200 within " + healthAttempts + " polls. The store is still"
                    + " marked SUSPENDED; retry Resume once it is healthy.");
        }
        row.setStatus(ACTIVE);
        repository.save(row);
        log.info("Resumed hosted store '{}'", clientCode);
        return new LifecycleResult(clientCode, ACTIVE, "Resumed and healthy.");
    }

    /** Restarts one ACTIVE store and waits for health. A suspended store is refused, not revived. */
    public LifecycleResult restartOne(String clientCode) {
        HostedInstance row = find(clientCode);
        if (SUSPENDED.equals(row.getStatus())) {
            throw new ConflictException("Hosted store '" + clientCode + "' is suspended; a restart"
                    + " would not hold. Use Resume to bring it back online.");
        }
        if (!ACTIVE.equals(row.getStatus())) {
            throw new ConflictException("Hosted store '" + clientCode + "' is " + row.getStatus()
                    + ", not ACTIVE; only an ACTIVE store can be restarted.");
        }
        ops.restart(row.getServiceName());
        if (!waitHealthy(row.getPort())) {
            return new LifecycleResult(clientCode, UNHEALTHY, "Restarted, but /api/health never answered"
                    + " 200 within " + healthAttempts + " polls.");
        }
        return new LifecycleResult(clientCode, ACTIVE, "Restarted and healthy.");
    }

    private HostedInstance find(String clientCode) {
        return repository.findByClientCode(clientCode).orElseThrow(() ->
                new NotFoundException("No hosted store registry row for client '" + clientCode + "'"));
    }

    private boolean waitHealthy(int port) {
        for (int attempt = 1; attempt <= healthAttempts; attempt++) {
            if (ops.health(port)) {
                return true;
            }
            if (attempt < healthAttempts && healthIntervalMs > 0) {
                try {
                    Thread.sleep(healthIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
