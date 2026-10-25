package com.daman.admin.controller;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedStoreProvisioningService;
import com.daman.admin.service.HostedStoreProvisioningService.ProvisionRequest;
import com.daman.admin.service.SubdomainRules;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class HostedStoreController {

    private final HostedStoreProvisioningService provisioningService;
    private final HostedInstanceRepository repository;

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

    @GetMapping("/suggest-subdomain")
    public Map<String, String> suggestSubdomain(@RequestParam String clientCode) {
        return Map.of("subdomain", SubdomainRules.sanitise(clientCode));
    }

    private static ResponseEntity<Map<String, String>> error(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message == null ? "Rejected" : message));
    }
}
