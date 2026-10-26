package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.entity.License;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.repository.LicenseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    private final LicenseRepository licenseRepository;
    private final int healthAttempts;
    private final long healthIntervalMs;

    @Autowired
    public HostedStoreLifecycleService(HostedInstanceRepository repository, ServerOps ops,
                                       LicenseRepository licenseRepository) {
        this(repository, ops, licenseRepository, HEALTH_ATTEMPTS, HEALTH_INTERVAL_MS);
    }

    /** Public so controller tests in another package can inject a no-clock wait; not for production wiring. */
    public HostedStoreLifecycleService(HostedInstanceRepository repository, ServerOps ops,
                                LicenseRepository licenseRepository,
                                int healthAttempts, long healthIntervalMs) {
        this.repository = repository;
        this.ops = ops;
        this.licenseRepository = licenseRepository;
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
     * Idempotent: resuming a running store reports success. The row is marked ACTIVE as soon as
     * the unit is enabled and started, before the health wait: the registry status records intent
     * (this store is meant to run) and the live axis reports reality. Leaving it SUSPENDED while
     * the unit runs would show a lie in the fleet list and exclude a serving store from release
     * walks. If health never confirms, the unit is NOT rolled back (a cold boot can outlast the
     * 75 s bound); the result is UNHEALTHY (502) and the store list shows the live state.
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
        row.setStatus(ACTIVE);
        repository.save(row);
        if (!waitHealthy(row.getPort())) {
            log.warn("Resumed hosted store '{}' but /api/health did not confirm within {} polls",
                    clientCode, healthAttempts);
            return new LifecycleResult(clientCode, UNHEALTHY, "The store was started and is marked"
                    + " ACTIVE, but it did not confirm healthy within " + healthAttempts
                    + " polls. The store list shows its live state (starting, or stopped if it"
                    + " never comes up).");
        }
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

    /**
     * @param keptBackup    the zip taken before anything was deleted, or null when none was taken
     * @param backupSkipped true when no database was left to copy (an earlier run already removed it)
     * @param steps         what each step did, including the ones that found their target already gone
     */
    public record RemoveResult(String clientCode, String keptBackup, boolean backupSkipped,
                               boolean licenceRevoked, List<String> steps) {}

    /**
     * Minimum-safe teardown of a departed customer's store (spec 9.3). The only irreversible action
     * in the fleet feature, so every refusal happens in the preflight, before anything is touched.
     *
     * <p>Only a {@code SUSPENDED} store qualifies: the API mirrors the UI's deliberate two-step and
     * guarantees the copy is taken from a cleanly stopped database. A missing database is not
     * uniformly harmless. For a suspended (real) customer it means the path was derived wrongly, so
     * the removal is refused rather than deleting the store with no copy. The one exception is when
     * the data directory itself is already gone: a previous run got that far and died before
     * deleting the row, and refusing would strand a half-removed store forever.
     *
     * <p>Every step after the backup tolerates its target already being absent. The data directory
     * and the client code passed to {@code deleteTree} both come from the registry row, never from
     * the caller.
     */
    public RemoveResult remove(String clientCode) {
        HostedInstance row = find(clientCode);
        if (!SUSPENDED.equals(row.getStatus())) {
            throw new ConflictException("Hosted store '" + clientCode + "' is " + row.getStatus()
                    + ", not SUSPENDED. Suspend it first: removal is only offered for a store that is"
                    + " already stopped, so the backup is taken from a cleanly stopped database.");
        }
        String code = row.getClientCode();
        String dataDir = row.getDataDir();
        if (!StorePaths.isAllowedDataDir(dataDir, code)) {
            throw new ConflictException("Refusing to remove '" + code + "': its registered data"
                    + " directory " + dataDir + " is not one of the two places a store may live.");
        }
        if (!hasOwnApiHostname(row)) {
            throw new ConflictException("Refusing to remove '" + code + "': its API hostname "
                    + row.getApiHostname() + " is not '<subdomain>-api' for this store, so dropping"
                    + " its ingress rule could break another tenant.");
        }
        String dbFile = StorePaths.databaseFile(dataDir, code);
        boolean dbPresent = ops.fileModifiedAt(dbFile).isPresent();
        boolean dirPresent = ops.fileModifiedAt(dataDir).isPresent();
        if (!dbPresent && dirPresent) {
            throw new ConflictException("Refusing to remove '" + code + "': its data directory exists"
                    + " but no database was found at " + dbFile + ". That means the path is wrong, not"
                    + " that the customer has no data, and removing now would leave no copy.");
        }

        List<String> steps = new ArrayList<>();

        ops.stopAndDisable(row.getServiceName());
        steps.add("Stopped and disabled " + row.getServiceName() + " (already stopped if it was suspended).");

        String keptBackup = null;
        if (dbPresent) {
            keptBackup = StorePaths.removedBackupZip(code, LocalDate.now());
            ops.zipFileInto(dbFile, keptBackup, "daman_db.sqlite");
            steps.add("Backed up " + dbFile + " to " + keptBackup + ".");
        } else {
            steps.add("No backup taken: the data directory " + dataDir + " is already gone, so an"
                    + " earlier removal run deleted it.");
        }

        ops.deleteUnit(row.getServiceName());
        steps.add("Deleted unit " + row.getServiceName() + " (no error if it was already gone).");

        steps.add(dropIngressRule(row)
                ? "Removed the ingress rule for " + row.getApiHostname() + " and reloaded cloudflared."
                : "Ingress rule for " + row.getApiHostname() + " was already absent.");

        ops.deleteTree(dataDir, code);
        steps.add(dirPresent ? "Deleted data directory " + dataDir + "."
                : "Data directory " + dataDir + " was already absent.");

        boolean licenceRevoked = revokeLicence(code);
        steps.add(licenceRevoked ? "Revoked the store's licence." : "No licence row to revoke.");

        repository.delete(row);
        steps.add("Deleted the registry row.");
        log.warn("Removed hosted store '{}'. Backup: {}. Steps: {}", code, keptBackup, steps);
        return new RemoveResult(code, keptBackup, keptBackup == null, licenceRevoked, steps);
    }

    /** True when the row's API hostname is exactly {@code <subdomain>-api.<zone>} and not a reserved one. */
    public static boolean hasOwnApiHostname(HostedInstance row) {
        if (row.getSubdomain() == null || row.getApiHostname() == null) {
            return false;
        }
        String label = row.getApiHostname().split("\\.", 2)[0];
        return label.equals(row.getSubdomain() + "-api") && !SubdomainRules.RESERVED.contains(label);
    }

    /**
     * Drops the row's {@code <sub>-api} ingress rule, writing and reloading cloudflared only when
     * the rule was actually there. Returns whether it was. {@code nextFreePort()} reissues a freed
     * port, so a surviving rule would route the departed hostname to the next tenant's store.
     * Callers must have checked {@link #hasOwnApiHostname} first.
     */
    public boolean dropIngressRule(HostedInstance row) {
        String ingress = ops.readIngress();
        String edited = IngressEditor.removeRule(ingress, row.getApiHostname());
        if (edited.equals(ingress)) {
            return false;
        }
        ops.writeIngress(edited);
        ops.reloadCloudflared();
        return true;
    }

    /** Same selection as {@code LicenseController.revokeByClient}: newest ACTIVE row, else newest row. */
    private boolean revokeLicence(String clientCode) {
        List<License> all = licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode);
        if (all.isEmpty()) {
            return false;
        }
        License license = all.stream()
                .filter(l -> "ACTIVE".equals(l.getStatus()))
                .findFirst()
                .orElse(all.get(0));
        license.setStatus("REVOKED");
        license.setRevokedAt(LocalDateTime.now());
        licenseRepository.save(license);
        return true;
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
