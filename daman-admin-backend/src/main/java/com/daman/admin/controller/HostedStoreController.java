package com.daman.admin.controller;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.DemoResetService;
import com.daman.admin.service.HostedStoreLifecycleService;
import com.daman.admin.service.HostedStoreProvisionLauncher;
import com.daman.admin.service.HostedStoreProvisioningService;
import com.daman.admin.service.HostedStoreProvisioningService.ProvisionRequest;
import com.daman.admin.service.HostedStoreStatusService;
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

import java.time.Instant;
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

    private final HostedStoreProvisionLauncher launcher;
    private final HostedInstanceRepository repository;
    private final ServerOps ops;
    private final HostedStoreStatusService statusService;
    private final HostedStoreLifecycleService lifecycle;
    private final DemoResetService demoReset;
    private final int healthAttempts;
    private final long healthIntervalMs;

    @Autowired
    public HostedStoreController(HostedStoreProvisionLauncher launcher,
                                 HostedInstanceRepository repository, ServerOps ops,
                                 HostedStoreStatusService statusService,
                                 HostedStoreLifecycleService lifecycle, DemoResetService demoReset) {
        this(launcher, repository, ops, statusService, lifecycle, demoReset,
                HEALTH_ATTEMPTS, HEALTH_INTERVAL_MS);
    }

    HostedStoreController(HostedStoreProvisionLauncher launcher,
                          HostedInstanceRepository repository, ServerOps ops,
                          HostedStoreStatusService statusService, HostedStoreLifecycleService lifecycle,
                          DemoResetService demoReset, int healthAttempts, long healthIntervalMs) {
        this.launcher = launcher;
        this.repository = repository;
        this.ops = ops;
        this.statusService = statusService;
        this.lifecycle = lifecycle;
        this.demoReset = demoReset;
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

    /** {@code demoFlavour} ("mart" or "mobile") marks the new slot as a demo. Absent = a real store. */
    public record ProvisionBody(String clientCode, String subdomain, String expiresAt, String demoFlavour) {
        /** A body without a flavour provisions an ordinary customer store, which is the default. */
        public ProvisionBody(String clientCode, String subdomain, String expiresAt) {
            this(clientCode, subdomain, expiresAt, null);
        }
    }

    /**
     * Response shape for a registry row. Deliberately has no field for the store's JWT signing
     * key: that key is the tenancy boundary, so the type cannot carry it.
     */
    public record HostedStoreDto(
            Long id, String clientCode, String subdomain, int port, String serviceName,
            String apiHostname, String frontendHostname, String status, String failedStage,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            String liveStatus, String staleness, Instant startedAt,
            String backupAt, Long backupSizeBytes, Long memoryBytes,
            // Carried so the dashboard can offer Reset on demo rows only. The page deciding what to
            // SHOW is a convenience; DemoResetService refuses a non-demo row regardless.
            boolean demo, String demoFlavour) {

        /** Registry facts only (provision/forget answers): the live fields are not probed here. */
        static HostedStoreDto of(HostedInstance i) {
            return of(i, null);
        }

        static HostedStoreDto of(HostedInstance i, HostedStoreStatusService.LiveState live) {
            return new HostedStoreDto(i.getId(), i.getClientCode(), i.getSubdomain(), i.getPort(),
                    i.getServiceName(), i.getApiHostname(), i.getFrontendHostname(), i.getStatus(),
                    i.getFailedStage(), i.getCreatedAt(), i.getUpdatedAt(),
                    live == null ? null : live.liveStatus(),
                    live == null ? null : live.staleness(),
                    live == null ? null : live.startedAt(),
                    live == null ? null : live.backupAt(),
                    live == null ? null : live.backupSizeBytes(),
                    live == null ? null : live.memoryBytes(),
                    i.isDemo(), i.getDemoFlavour());
        }
    }

    /** The list response: the stores plus the fleet-level numbers a dashboard header needs. */
    public record FleetDto(List<HostedStoreDto> stores, Instant jarModifiedAt, long totalMemoryMb,
                           int committedStores, int heapMb, int maxStores,
                           ServerOps.BoxStats boxStats) {}

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
                    new ProvisionRequest(clientCode, body.subdomain().trim(), body.expiresAt(),
                            body.demoFlavour()));
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
     * <p><b>Registry-only for data and infrastructure, with one exception.</b> The DNS records, the
     * Pages custom domain, the systemd unit and the data directory all survive and are the
     * operator's to clean up (DEPLOYMENT.md §10.5 lists them). The tunnel ingress rule for the
     * row's own {@code <sub>-api} hostname is dropped (absent is fine): deleting the row frees its
     * port, so a surviving rule would point the departed hostname at the next store to get that
     * port, a cross-tenant leak. The "registry-only" promise exists so Forget cannot destroy data;
     * dropping a rule for a store that does not exist destroys none. Deleting the row
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
        if ("SUSPENDED".equals(row.getStatus())) {
            return error("Hosted store '" + clientCode + "' is SUSPENDED — refusing to delete its row:"
                    + " that would leave its unit and the customer's data on disk with no backup and no"
                    + " row left to remove it by. Use POST /api/hosted-stores/" + clientCode
                    + "/remove, which takes a backup first.", HttpStatus.CONFLICT);
        }
        if (launcher.isRunning(clientCode)) {
            return error("A provision for client '" + clientCode + "' is running right now;"
                    + " deleting its row underneath it would have the run re-create it. Wait for"
                    + " the row to read FAILED, then retry.", HttpStatus.CONFLICT);
        }
        // Closes a tenancy leak: a row that failed at API_DNS or later already has a live ingress
        // rule, and deleting the row frees its port for the next store. An absent rule is fine.
        boolean ingressDropped = false;
        if (HostedStoreLifecycleService.hasOwnApiHostname(row)) {
            ingressDropped = lifecycle.dropIngressRule(row);
        } else {
            log.warn("Not touching the ingress for '{}': API hostname {} is not its own", clientCode,
                    row.getApiHostname());
        }
        repository.delete(row);
        log.warn("Deleted the hosted-store registry row for '{}' (was {} at stage {}). Ingress rule"
                        + " dropped: {}. Its unit, data directory, DNS records and Pages domain were NOT touched.",
                clientCode, row.getStatus(), row.getFailedStage(), ingressDropped);
        return ResponseEntity.ok(Map.of(
                "deleted", clientCode,
                "warning", "Registry row only. The systemd unit " + row.getServiceName()
                        + ", the data directory " + row.getDataDir() + ", the DNS records for "
                        + row.getApiHostname() + " and " + row.getFrontendHostname()
                        + ", and the Pages custom domain all still exist — remove them by hand if"
                        + " this store is being abandoned (DEPLOYMENT.md §10.5)."));
    }

    @GetMapping
    public FleetDto list() {
        List<HostedInstance> rows = repository.findAll();
        HostedStoreStatusService.FleetState fleet = statusService.describe(rows);
        List<HostedStoreDto> stores = new java.util.ArrayList<>();
        for (int n = 0; n < rows.size(); n++) {
            stores.add(HostedStoreDto.of(rows.get(n), fleet.states().get(n)));
        }
        return new FleetDto(stores, fleet.jarModifiedAt(), fleet.totalMemoryMb(),
                fleet.committedStores(), fleet.heapMb(), fleet.maxStores(), fleet.boxStats());
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

    /**
     * Suspend: a billing lever. Disables and stops the unit and marks the row SUSPENDED, so neither
     * a reboot, a release walk nor re-running Create brings the store back. Idempotent.
     */
    @PostMapping("/{clientCode}/suspend")
    public ResponseEntity<?> suspend(@PathVariable String clientCode) {
        return lifecycleCall(() -> lifecycle.suspend(clientCode));
    }

    /** Resume a suspended store; 502 when it was started but never became healthy. Idempotent. */
    @PostMapping("/{clientCode}/resume")
    public ResponseEntity<?> resume(@PathVariable String clientCode) {
        return lifecycleCall(() -> lifecycle.resume(clientCode));
    }

    /** Restart one ACTIVE store; a suspended one is refused with 409. 502 when it stays unhealthy. */
    @PostMapping("/{clientCode}/restart")
    public ResponseEntity<?> restartStore(@PathVariable String clientCode) {
        return lifecycleCall(() -> lifecycle.restartOne(clientCode));
    }

    /**
     * Wipes a <b>demo slot</b> back to its seed and rolls its dates to today, so the next prospect
     * gets a clean shop that looks like it traded this morning.
     *
     * <p><b>Destroys that store's database.</b> It is refused for any row not explicitly marked as
     * a demo — the guard lives in {@link DemoResetService} and is checked before anything is
     * stopped, so no request can aim this at a paying customer. 400 on a refusal; the body says why.
     */
    @PostMapping("/{clientCode}/reset-demo")
    public ResponseEntity<?> resetDemo(@PathVariable String clientCode) {
        try {
            return ResponseEntity.ok(demoReset.reset(clientCode));
        } catch (DemoResetService.RefusedException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (RuntimeException e) {
            log.error("Demo reset for '{}' failed", clientCode, e);
            return error(e.getMessage(), HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * Re-renders one ACTIVE store's systemd unit from its registry row and restarts it, so a store
     * created before a unit-template change picks that change up. A unit is written once, at
     * provisioning, and a jar redeploy never rewrites it — this is the only way to roll a template
     * change out to stores that already exist.
     *
     * <p>No-op (200, "already up to date") when the rendered unit matches what is on disk, so it is
     * safe to run against the whole fleet. 409 when it would change {@code DAMAN_JWT_SECRET},
     * which signs out everyone on that store, unless {@code allowSecretChange=true} says so
     * deliberately.
     */
    @PostMapping("/{clientCode}/sync-unit")
    public ResponseEntity<?> syncUnit(@PathVariable String clientCode,
                                      @RequestParam(defaultValue = "false") boolean allowSecretChange) {
        return lifecycleCall(() -> lifecycle.syncUnit(clientCode, allowSecretChange));
    }

    /**
     * Irreversible teardown of a SUSPENDED store, keeping one backup zip. 409 for a store that is
     * not suspended or whose paths fail the safety checks, 404 for an unknown client.
     */
    @PostMapping("/{clientCode}/remove")
    public ResponseEntity<?> remove(@PathVariable String clientCode) {
        if (launcher.isRunning(clientCode)) {
            return error("A provision for client '" + clientCode + "' is running right now;"
                    + " removing the store underneath it would have the run re-create its unit and"
                    + " ingress rule. Wait for it to finish, then retry.", HttpStatus.CONFLICT);
        }
        try {
            return ResponseEntity.ok(lifecycle.remove(clientCode));
        } catch (HostedStoreLifecycleService.NotFoundException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (HostedStoreLifecycleService.ConflictException e) {
            return error(e.getMessage(), HttpStatus.CONFLICT);
        }
    }

    /** 404 unknown client, 409 wrong status, 502 ran but never healthy (health wait is 75 s max). */
    private ResponseEntity<?> lifecycleCall(java.util.function.Supplier<HostedStoreLifecycleService.LifecycleResult> action) {
        try {
            HostedStoreLifecycleService.LifecycleResult r = action.get();
            HttpStatus status = HostedStoreLifecycleService.UNHEALTHY.equals(r.status())
                    ? HttpStatus.BAD_GATEWAY : HttpStatus.OK;
            return ResponseEntity.status(status).body(r);
        } catch (HostedStoreLifecycleService.NotFoundException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (HostedStoreLifecycleService.ConflictException e) {
            return error(e.getMessage(), HttpStatus.CONFLICT);
        }
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
