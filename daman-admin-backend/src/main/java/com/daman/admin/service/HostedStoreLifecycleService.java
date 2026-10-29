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
import java.util.Map;

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
     * Re-renders one ACTIVE store's systemd unit from the registry row and restarts it, so a store
     * provisioned before a unit-template change picks that change up.
     *
     * <p>Why this exists: {@code writeUnit} runs only in the {@code UNIT} provisioning stage, so a
     * store's unit is frozen at the moment it was created. Deploying a new jar and restarting does
     * not touch it. When viora needed the Google Drive settings its unit had been written weeks
     * earlier and had neither {@code DAMAN_GOOGLE_REDIRECT_URI} nor the credentials
     * {@code EnvironmentFile}, so the feature reported itself unconfigured with nothing in the UI
     * to fix it — the unit had to be hand-edited over SSH. This is that hand-edit, made repeatable.
     *
     * <p>Skips the restart entirely when the rendered unit already matches what is on disk, so it
     * is safe to run across the fleet: an up-to-date store is left running untouched.
     *
     * <p><b>Refuses to rotate the JWT signing secret.</b> The unit carries
     * {@code DAMAN_JWT_SECRET}, and if the registry row has drifted from the running unit a blind
     * rewrite would silently sign out every user of that store mid-shift. That is a decision for a
     * human, so it is a 409 unless {@code allowSecretChange} is explicitly set.
     *
     * @param allowSecretChange permit a rewrite that changes {@code DAMAN_JWT_SECRET} (signs
     *                          everyone out of that store)
     */
    public LifecycleResult syncUnit(String clientCode, boolean allowSecretChange) {
        HostedInstance row = find(clientCode);
        if (SUSPENDED.equals(row.getStatus())) {
            throw new ConflictException("Hosted store '" + clientCode + "' is suspended; resume it"
                    + " first, then sync its config.");
        }
        if (!ACTIVE.equals(row.getStatus())) {
            throw new ConflictException("Hosted store '" + clientCode + "' is " + row.getStatus()
                    + ", not ACTIVE; only an ACTIVE store's config can be synced.");
        }

        String rendered = StoreUnitRenderer.render(row);
        String current = ops.readUnit(row.getServiceName()).orElse(null);
        if (rendered.equals(current)) {
            return new LifecycleResult(clientCode, ACTIVE, "Config already up to date; nothing changed.");
        }

        List<String> changed = changedKeys(current, rendered);
        // Only a REPLACED secret signs anyone out. A unit that is missing entirely, or that has no
        // DAMAN_JWT_SECRET line at all, has no live sessions to invalidate — and blocking those
        // would refuse exactly the broken-unit case this exists to repair.
        String currentSecret = settings(current).get(JWT_SECRET_KEY);
        boolean rotatesSecret = currentSecret != null && changed.contains(JWT_SECRET_KEY);
        if (rotatesSecret && !allowSecretChange) {
            throw new ConflictException("Syncing '" + clientCode + "' would change its"
                    + " DAMAN_JWT_SECRET, which signs out everyone currently using that store."
                    + " Re-run with allowSecretChange=true if that is intended.");
        }

        ops.writeUnit(row.getServiceName(), rendered);
        ops.daemonReloadAndStart(row.getServiceName());

        // A restart is only earned by a changed SETTING. The texts can differ while every setting
        // matches — a reordered line, a reworded comment, or a unit someone hand-edited to the same
        // effect — and bouncing a live till to converge a comment would be downtime for nothing.
        // The file is still rewritten above, so the drift is gone either way.
        if (changed.isEmpty()) {
            log.info("Synced unit text for hosted store '{}'; no setting changed, so no restart", clientCode);
            return new LifecycleResult(clientCode, ACTIVE,
                    "Service file rewritten; no setting changed, so the store was not restarted.");
        }

        ops.restart(row.getServiceName());
        String what = String.join(", ", changed);
        if (!waitHealthy(row.getPort())) {
            return new LifecycleResult(clientCode, UNHEALTHY, "Config synced (" + what + "), but"
                    + " /api/health never answered 200 within " + healthAttempts + " polls.");
        }
        log.info("Synced unit for hosted store '{}' ({})", clientCode, what);
        return new LifecycleResult(clientCode, ACTIVE, "Config synced (" + what + ") and healthy.");
    }

    static final String JWT_SECRET_KEY = "DAMAN_JWT_SECRET";

    /**
     * The setting NAMES that differ between two unit texts — never their values. The unit holds the
     * JWT signing secret and this string reaches an HTTP response and the log, so reporting values
     * would leak it. A key present in one text and absent in the other counts as changed.
     */
    private static List<String> changedKeys(String current, String rendered) {
        Map<String, String> before = settings(current);
        Map<String, String> after = settings(rendered);
        java.util.Set<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<String> changed = new ArrayList<>();
        for (String k : keys) {
            if (!java.util.Objects.equals(before.get(k), after.get(k))) changed.add(k);
        }
        return changed;
    }

    /** {@code Environment=K=V} -> {@code K -> V}, plus {@code EnvironmentFile} and {@code ExecStart}. */
    private static Map<String, String> settings(String unitText) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (unitText == null) return out;
        for (String line : unitText.split("\n")) {
            String s = line.strip();
            if (s.startsWith("Environment=")) {
                String kv = s.substring("Environment=".length());
                int eq = kv.indexOf('=');
                if (eq > 0) out.put(kv.substring(0, eq), kv.substring(eq + 1));
            } else if (s.startsWith("EnvironmentFile=") || s.startsWith("ExecStart=")) {
                int eq = s.indexOf('=');
                out.put(s.substring(0, eq), s.substring(eq + 1));
            }
        }
        return out;
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

        // Always try to stop, so by the time the zip runs the process is down and the WAL is
        // checkpointed. This single pre-backup step swallows failure: `systemctl disable --now`
        // exits 1 for a unit file that no longer exists (the resumed-removal case), and any other
        // failure is also fine to continue past, because remove is SUSPENDED-only and deleteUnit +
        // daemon-reload follow regardless. A unitActive gate is NOT used: it reads "inactive" for a
        // unit that is activating or deactivating, which would copy a live database.
        try {
            ops.stopAndDisable(row.getServiceName());
            steps.add("Stopped and disabled " + row.getServiceName() + ".");
        } catch (RuntimeException e) {
            // steps is lost if a later step throws, and on a live unit this means the zip below may be
            // a hot copy with an uncheckpointed WAL, so the failure must be in the log.
            log.warn("Pre-backup stop of '{}' (unit {}) failed; continuing: {}", code, row.getServiceName(), e.toString());
            steps.add("unit already stopped or gone (" + e.getMessage() + ")");
        }

        String keptBackup = null;
        if (dbPresent) {
            keptBackup = StorePaths.removedBackupZip(code, LocalDate.now());
            ops.zipFileInto(dbFile, keptBackup, "daman_db.sqlite");
            // Logged immediately so the trail survives any later failure (steps is lost on a throw).
            log.warn("Kept backup for '{}' at {}", code, keptBackup);
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
        // removeRule normalises CRLF/CR to LF; compare against the same normalisation or a CRLF file
        // would look "changed" and restart cloudflared (an outage for every hostname) for nothing.
        String normalised = ingress.replace("\r\n", "\n").replace('\r', '\n');
        String edited = IngressEditor.removeRule(ingress, row.getApiHostname());
        if (edited.equals(normalised)) {
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
