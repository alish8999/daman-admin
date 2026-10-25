package com.daman.admin.controller;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedStoreProvisionLauncher;
import com.daman.admin.service.HostedStoreProvisioningService;
import com.daman.admin.service.HostedStoreProvisioningService.ProvisionRequest;
import com.daman.admin.service.ServerOps;
import com.daman.admin.service.SubdomainRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Drives hosted-store provisioning from the local deploy dashboard. Not listed in
 * {@code AuthFilter.PUBLIC_PATHS}, so every route needs the admin Bearer token.
 *
 * <p>Status mapping, all of it decided before any stage runs:
 * <ul>
 *   <li><b>202</b> with the {@code PROVISIONING} row — accepted and handed to
 *       {@link HostedStoreProvisionLauncher}. The stages take 125-150 s, far past the 100 s after
 *       which Cloudflare's edge answers 524, so the caller polls {@code GET /api/hosted-stores}
 *       until the row reads {@code ACTIVE} or {@code FAILED} instead of waiting on this response.</li>
 *   <li><b>400</b> — a refusal: the input is wrong and nothing was persisted.</li>
 *   <li><b>503</b> — this server cannot provision right now (no RAM headroom, Cloudflare not
 *       configured). The request was fine; retrying it unchanged later is correct.</li>
 *   <li><b>409</b> — the client already has an {@code ACTIVE} store, or a provision for it is
 *       already in flight. A {@code FAILED} row is neither: re-running Create resumes it.</li>
 * </ul>
 *
 * <p>Anything else is left to propagate as a 500 with no body of ours: a wide
 * {@code catch (IllegalStateException)} here used to echo incidental Spring and JPA messages
 * straight back to the caller.
 */
@RestController
@Slf4j
@RequestMapping("/api/hosted-stores")
public class HostedStoreController {

    /** A fresh store boots Spring in up to ~a minute; poll this long before calling it unhealthy. */
    private static final int HEALTH_ATTEMPTS = 40;
    private static final long HEALTH_INTERVAL_MS = 3000;

    /**
     * Viora is frozen on its OWN jar (/root/daman-store-viora.jar), not the shared one, so a
     * shared-jar release must not restart it. Rule chosen: exclude by service name. Deliberate, not
     * incidental - the unit text is not stored in the registry, so "jar path is not the shared one"
     * cannot be evaluated here; every other provisioned unit is rendered by StoreUnitRenderer with
     * the shared jar by construction.
     */
    static final String VIORA_SERVICE = "daman-store-viora";

    private final HostedStoreProvisionLauncher launcher;
    private final HostedInstanceRepository repository;
    private final ServerOps ops;
    private final int healthAttempts;
    private final long healthIntervalMs;

    @Autowired
    public HostedStoreController(HostedStoreProvisionLauncher launcher,
                                 HostedInstanceRepository repository, ServerOps ops) {
        this(launcher, repository, ops, HEALTH_ATTEMPTS, HEALTH_INTERVAL_MS);
    }

    HostedStoreController(HostedStoreProvisionLauncher launcher,
                          HostedInstanceRepository repository, ServerOps ops,
                          int healthAttempts, long healthIntervalMs) {
        this.launcher = launcher;
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

    public record ProvisionBody(String clientCode, String subdomain, String expiresAt) {}

    /**
     * Response shape for a registry row. Deliberately has no field for the store's JWT signing
     * key: that key is the tenancy boundary, so the type cannot carry it.
     */
    public record HostedStoreDto(
            Long id, String clientCode, String subdomain, int port, String serviceName,
            String apiHostname, String frontendHostname, String status, String failedStage,
            LocalDateTime createdAt, LocalDateTime updatedAt) {

        static HostedStoreDto of(HostedInstance i) {
            return new HostedStoreDto(i.getId(), i.getClientCode(), i.getSubdomain(), i.getPort(),
                    i.getServiceName(), i.getApiHostname(), i.getFrontendHostname(), i.getStatus(),
                    i.getFailedStage(), i.getCreatedAt(), i.getUpdatedAt());
        }
    }

    @PostMapping
    public ResponseEntity<?> provision(@RequestBody(required = false) ProvisionBody body) {
        if (body == null || body.clientCode() == null || body.clientCode().isBlank()) {
            return error("clientCode is required");
        }
        if (body.subdomain() == null || body.subdomain().isBlank()) {
            return error("A subdomain is required. Type one by hand (lowercase ASCII letters, digits "
                    + "and hyphens): a client code with no ASCII letters or digits, such as an "
                    + "Arabic-only name, has no automatic suggestion.");
        }
        String clientCode = body.clientCode().trim();
        try {
            // Returns as soon as the row exists: the stage loop runs on the launcher's own thread,
            // because holding this response for the full 125-150 s crosses Cloudflare's 100 s
            // origin timeout and reports a 524 failure for a provision that in fact succeeded.
            HostedStoreProvisionLauncher.Started started = launcher.start(
                    new ProvisionRequest(clientCode, body.subdomain().trim(), body.expiresAt()));
            return ResponseEntity
                    .status(started.alreadyActive() ? HttpStatus.CONFLICT : HttpStatus.ACCEPTED)
                    .body(HostedStoreDto.of(started.instance()));
        } catch (HostedStoreProvisioningService.RefusedException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (HostedStoreProvisioningService.UnavailableException e) {
            return error(e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        } catch (HostedStoreProvisionLauncher.AlreadyRunningException e) {
            return error(e.getMessage(), HttpStatus.CONFLICT);
        }
    }

    /**
     * Forgets a half-created store's registry row. Admin-token gated like everything here, and it
     * <b>refuses any {@code ACTIVE} row</b>: spec §5.4's "no suspend, stop or delete" decision is
     * about live stores and still stands.
     *
     * <p>What this is for is the case the spec did not consider: a <em>mistyped</em> client. Type
     * {@code acem} for {@code acme}, let it half-complete, and there was no supported way out — the
     * resume refuses to rename the row, {@code existsBySubdomain} then blocks reusing {@code acme}
     * on the correctly-named client, and the H2 console is disabled, so the only escape was an H2
     * shell over SSH.
     *
     * <p><b>Registry-only.</b> Nothing is torn down, exactly as a failed provision tears nothing
     * down: the DNS records, the Pages custom domain, the systemd unit and the data directory all
     * survive and are the operator's to clean up (DEPLOYMENT.md §10.5 lists them). Deleting the row
     * only stops the registry claiming that store exists — it also frees its subdomain and its
     * port for reuse, which is the point.
     */
    @DeleteMapping("/{clientCode}")
    public ResponseEntity<Map<String, String>> forget(@PathVariable String clientCode) {
        HostedInstance row = repository.findByClientCode(clientCode).orElse(null);
        if (row == null) {
            return error("No hosted store registry row for client '" + clientCode + "'",
                    HttpStatus.NOT_FOUND);
        }
        if ("ACTIVE".equals(row.getStatus())) {
            return error("Hosted store '" + clientCode + "' is ACTIVE on " + row.getFrontendHostname()
                    + " — refusing to delete the row of a live store. This route exists only to"
                    + " abandon a half-created one.", HttpStatus.CONFLICT);
        }
        if (launcher.isRunning(clientCode)) {
            return error("A provision for client '" + clientCode + "' is running right now;"
                    + " deleting its row underneath it would have the run re-create it. Wait for"
                    + " the row to read FAILED, then retry.", HttpStatus.CONFLICT);
        }
        repository.delete(row);
        log.warn("Deleted the hosted-store registry row for '{}' (was {} at stage {}). Its unit,"
                        + " data directory, DNS records and Pages domain were NOT touched.",
                clientCode, row.getStatus(), row.getFailedStage());
        return ResponseEntity.ok(Map.of(
                "deleted", clientCode,
                "warning", "Registry row only. The systemd unit " + row.getServiceName()
                        + ", the data directory " + row.getDataDir() + ", the DNS records for "
                        + row.getApiHostname() + " and " + row.getFrontendHostname()
                        + ", and the Pages custom domain all still exist — remove them by hand if"
                        + " this store is being abandoned (DEPLOYMENT.md §10.5)."));
    }

    @GetMapping
    public List<HostedStoreDto> list() {
        return repository.findAll().stream().map(HostedStoreDto::of).toList();
    }

    /**
     * Shared-jar release: restarts every ACTIVE store one at a time, polling its health before moving
     * to the next, and HALTS at the first store that fails to restart or come up. That delays and
     * sequences the exposure to a bad jar; it does NOT contain it: the new jar is already on disk and
     * every unit has {@code Restart=always}, so a store not yet reached loads it on any restart, and
     * the health check only catches "does not boot". Stores are walked in id order (oldest first, so
     * the oldest store is the canary). 200 when every store is healthy, 502 on a halt.
     *
     * <p>The body is a {@link RestartReport}: {@code walked} is the stores actually restarted (the
     * unhealthy one last; stores after a halt are untouched and absent), and {@code skipped} is
     * every registry row deliberately passed over, with the reason. Both halves matter to the
     * caller — an empty {@code walked} list means the release restarted <b>nothing</b>, which is a
     * silent no-op the script has to shout about rather than call a success.
     */
    @PostMapping("/restart-all")
    public ResponseEntity<RestartReport> restartAll() {
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
            RestartResult r = restartOne(i);
            walked.add(r);
            if (!r.healthy()) {
                // Halt: every row after this one is neither walked nor "skipped by rule" — it was
                // simply never reached, which the halt itself says.
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(new RestartReport(walked, skipped));
            }
        }
        if (walked.isEmpty()) {
            log.warn("restart-all restarted NOTHING: {} registry row(s), all skipped {}",
                    rows.size(), skipped);
        }
        return ResponseEntity.ok(new RestartReport(walked, skipped));
    }

    /** Why this row is not part of a shared-jar release, or null when it is. */
    private static String skipReason(HostedInstance i) {
        if (VIORA_SERVICE.equals(i.getServiceName())) {
            return "runs its own jar (/root/daman-store-viora.jar), not the shared one";
        }
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

    @GetMapping("/suggest-subdomain")
    public Map<String, String> suggestSubdomain(@RequestParam String clientCode) {
        return Map.of("subdomain", SubdomainRules.sanitise(clientCode));
    }

    private static ResponseEntity<Map<String, String>> error(String message) {
        return error(message, HttpStatus.BAD_REQUEST);
    }

    private static ResponseEntity<Map<String, String>> error(String message, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "Rejected" : message));
    }
}
