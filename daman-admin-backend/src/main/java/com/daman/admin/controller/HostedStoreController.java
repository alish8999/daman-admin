package com.daman.admin.controller;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedStoreProvisioningService;
import com.daman.admin.service.HostedStoreProvisioningService.ProvisionRequest;
import com.daman.admin.service.ServerOps;
import com.daman.admin.service.SubdomainRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 * <p>Status mapping follows the service's own split: a <em>refusal</em> throws and persists
 * nothing (400); a <em>failure</em> mid-sequence returns a {@code FAILED} row, which is still
 * 201 because the caller needs the row to see how far it got. A client that already has an
 * {@code ACTIVE} store is 409 with that row, so a resume of a {@code FAILED} one still works.
 */
@RestController
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

    private final HostedStoreProvisioningService provisioningService;
    private final HostedInstanceRepository repository;
    private final ServerOps ops;
    private final int healthAttempts;
    private final long healthIntervalMs;

    @Autowired
    public HostedStoreController(HostedStoreProvisioningService provisioningService,
                                 HostedInstanceRepository repository, ServerOps ops) {
        this(provisioningService, repository, ops, HEALTH_ATTEMPTS, HEALTH_INTERVAL_MS);
    }

    HostedStoreController(HostedStoreProvisioningService provisioningService,
                          HostedInstanceRepository repository, ServerOps ops,
                          int healthAttempts, long healthIntervalMs) {
        this.provisioningService = provisioningService;
        this.repository = repository;
        this.ops = ops;
        this.healthAttempts = healthAttempts;
        this.healthIntervalMs = healthIntervalMs;
    }

    /** One walked store. A store never reached (after a halt) has no entry at all. */
    public record RestartResult(String clientCode, boolean restarted, boolean healthy) {}

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
        // Looked up BEFORE provisioning: a freshly provisioned store also ends ACTIVE, so only the
        // prior state tells "already live" (409) apart from "just made live" (201).
        boolean alreadyActive = repository.findByClientCode(clientCode)
                .map(i -> "ACTIVE".equals(i.getStatus())).orElse(false);
        try {
            HostedInstance instance = provisioningService.provision(
                    new ProvisionRequest(clientCode, body.subdomain().trim(), body.expiresAt()));
            return ResponseEntity.status(alreadyActive ? HttpStatus.CONFLICT : HttpStatus.CREATED)
                    .body(HostedStoreDto.of(instance));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping
    public List<HostedStoreDto> list() {
        return repository.findAll().stream().map(HostedStoreDto::of).toList();
    }

    /**
     * Shared-jar release: restarts every ACTIVE store one at a time, polling its health before moving
     * to the next, and HALTS at the first store that fails to restart or come up - so a bad jar takes
     * one till down, not all of them. 200 when every store is healthy, 502 on a halt; the body is the
     * list of stores actually walked (the unhealthy one last). Stores after the halt are untouched
     * and absent from the list.
     */
    @PostMapping("/restart-all")
    public ResponseEntity<List<RestartResult>> restartAll() {
        List<HostedInstance> targets = repository.findAll().stream()
                .filter(i -> "ACTIVE".equals(i.getStatus()))
                .filter(i -> !VIORA_SERVICE.equals(i.getServiceName()))
                .sorted(java.util.Comparator.comparing(HostedInstance::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        List<RestartResult> results = new java.util.ArrayList<>();
        for (HostedInstance i : targets) {
            RestartResult r = restartOne(i);
            results.add(r);
            if (!r.healthy()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(results);
            }
        }
        return ResponseEntity.ok(results);
    }

    private RestartResult restartOne(HostedInstance i) {
        try {
            ops.restart(i.getServiceName());
        } catch (RuntimeException e) {
            return new RestartResult(i.getClientCode(), false, false);
        }
        for (int attempt = 1; attempt <= healthAttempts; attempt++) {
            if (ops.health(i.getPort())) {
                return new RestartResult(i.getClientCode(), true, true);
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
        return new RestartResult(i.getClientCode(), true, false);
    }

    @GetMapping("/suggest-subdomain")
    public Map<String, String> suggestSubdomain(@RequestParam String clientCode) {
        return Map.of("subdomain", SubdomainRules.sanitise(clientCode));
    }

    private static ResponseEntity<Map<String, String>> error(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message == null ? "Rejected" : message));
    }
}
