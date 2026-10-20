package com.daman.admin.controller;

import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.service.LicenseKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/licenses")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseKeyService licenseKeyService;
    private final LicenseRepository licenseRepository;
    private final ClientConfigRepository clientConfigRepository;
    private final com.daman.admin.service.ClientConfigService clientConfigService;
    private final com.daman.admin.service.LicenseReissueService licenseReissueService;
    private final com.daman.admin.service.LicenseBundleZipper licenseBundleZipper;
    private final com.daman.admin.repository.TrialRegistrationRepository trialRegistrationRepository;

    // ── Generate ──────────────────────────────────────────────────────────────

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        String machineId  = body.get("machineId");
        String clientCode = body.get("clientCode");
        String expiresAt  = body.get("expiresAt");
        String label      = body.getOrDefault("label", "");

        if (machineId == null || machineId.isBlank() || clientCode == null || clientCode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "machineId and clientCode are required"));
        }

        var clientConfig = clientConfigRepository.findByClientCode(clientCode).orElse(null);
        if (clientConfig == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Client not found: " + clientCode));
        }

        // Block duplicate: same client + same machine already has an ACTIVE license
        var duplicate = licenseRepository.findByMachineIdAndClientCodeAndStatus(machineId, clientCode, "ACTIVE");
        if (duplicate.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "An active license already exists for this machine and client. Revoke or renew it instead."));
        }

        String clientName = clientConfig.getAppName();
        var ent = clientConfigService.licenseEntitlementsFor(clientCode);
        String licenseKey = licenseKeyService.generateLicense(
                machineId, clientName, clientCode, expiresAt, ent.baseCurrency(), ent.features(),
                ent.colorPrimary(), ent.colorSecondary(), ent.adminUsername(), ent.adminPassword(), ent.profile());

        License license = new License();
        license.setClientCode(clientCode);
        license.setMachineId(machineId);
        license.setLicenseKey(licenseKey);
        license.setClientName(clientName);
        license.setStatus("ACTIVE");
        license.setLabel(label.isBlank() ? null : label);
        license.setExpiresAt(expiresAt != null && !expiresAt.isBlank() ? LocalDate.parse(expiresAt) : null);
        licenseRepository.save(license);
        markTrialConverted(clientCode);

        return ResponseEntity.ok(Map.of(
            "licenseKey", licenseKey,
            "clientName", clientName,
            "machineId",  machineId,
            "label",      label
        ));
    }

    /**
     * A normal paid licence minted for a self-serve trial's clientCode is what "converted" means.
     * Every existing client passes through generate(), so this must never throw or alter anything
     * for a non-trial client: a missing registration is a silent no-op, and any failure here is
     * logged and swallowed rather than failing the licence that was just saved.
     */
    private void markTrialConverted(String clientCode) {
        try {
            for (var trial : trialRegistrationRepository.findAllByClientCode(clientCode)) {
                if (trial.getConvertedAt() == null) {
                    trial.setConvertedAt(LocalDateTime.now());
                    trialRegistrationRepository.save(trial);
                }
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(LicenseController.class)
                    .warn("Could not stamp trial conversion for {}: {}", clientCode, e.toString());
        }
    }

    // ── Activate ─────────────────────────────────────────────────────────────
    //
    // REMOVED 2026-10-19. An UNAUTHENTICATED `POST /api/licenses/activate`
    // used to live here, exempted in AuthFilter.PUBLIC_PATHS and labelled
    // "called by the desktop app". No desktop build ever called it: licences
    // ship as `.dat` files and are verified locally against the bundled public
    // key (daman-backend LicenseService.verifyLicenseKey), and the desktop
    // makes no outbound HTTP calls at all. Confirmed with
    // `git log --all -S "licenses/activate"` over daman-backend and
    // daman-frontend — zero hits in all history, so nothing in the field
    // depended on it.
    //
    // What it did, reachable from the open internet with no credentials: took
    // machineId and clientCode from the request BODY and minted a signed
    // licence with expiresAt = null (never expires), bound to the caller's own
    // machineId, carrying that client's full paid entitlements AND their seeded
    // admin username/password. The payload half of a licence key is base64, not
    // encrypted, so the caller could decode the response and read those
    // credentials. A wrong code answered 400 "Client not found" and a right one
    // 200, which made client codes enumerable — and they are low-entropy
    // (daman-1, viora, ...). Verified at the time of removal that
    // admin-api.damansoft.com answers GET /api/clients with 401 from the public
    // internet, i.e. AuthFilter was the ONLY gate; there is no Cloudflare Access
    // layer in front of it.
    //
    // Licences are minted through the session-protected POST /api/licenses
    // above, driven by the admin UI. If a phone-home activation flow is ever
    // wanted, it must authenticate with a per-client short-lived activation
    // token issued when the client is created — never clientCode alone.

    // ── List ─────────────────────────────────────────────────────────────────

    @GetMapping
    public java.util.List<com.daman.admin.dto.LicenseDto> getAll() {
        return licenseRepository.findAllByOrderByActivatedAtDesc().stream()
                .map(l -> com.daman.admin.dto.LicenseDto.of(l, licenseKeyService.payloadVersion(l.getLicenseKey())))
                .toList();
    }

    @GetMapping("/client/{clientCode}")
    public java.util.List<com.daman.admin.dto.LicenseDto> getByClient(@PathVariable String clientCode) {
        return licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode).stream()
                .map(l -> com.daman.admin.dto.LicenseDto.of(l, licenseKeyService.payloadVersion(l.getLicenseKey())))
                .toList();
    }

    // ── Revoke ───────────────────────────────────────────────────────────────

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Map<String, String>> revokeById(@PathVariable Long id) {
        License license = licenseRepository.findById(id).orElse(null);
        if (license == null) return ResponseEntity.notFound().build();
        license.setStatus("REVOKED");
        license.setRevokedAt(LocalDateTime.now());
        licenseRepository.save(license);
        return ResponseEntity.ok(Map.of("message", "License revoked"));
    }

    @PostMapping("/client/{clientCode}/revoke")
    public ResponseEntity<Map<String, String>> revokeByClient(@PathVariable String clientCode) {
        var licenses = licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode);
        if (licenses.isEmpty()) return ResponseEntity.notFound().build();
        // Revoke the most recent active one
        License license = licenses.stream()
                .filter(l -> "ACTIVE".equals(l.getStatus()))
                .findFirst()
                .orElse(licenses.get(0));
        license.setStatus("REVOKED");
        license.setRevokedAt(LocalDateTime.now());
        licenseRepository.save(license);
        return ResponseEntity.ok(Map.of("message", "License revoked"));
    }

    // ── Renew ────────────────────────────────────────────────────────────────

    /** Renew a specific license by its ID — preferred endpoint. */
    @PutMapping("/{id}/renew")
    public ResponseEntity<Map<String, Object>> renewById(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        License license = licenseRepository.findById(id).orElse(null);
        if (license == null) return ResponseEntity.notFound().build();

        return doRenew(license, body.get("expiresAt"));
    }

    /** Legacy: renew by clientCode — renews the most recent active license. */
    @PutMapping("/client/{clientCode}/renew")
    public ResponseEntity<Map<String, Object>> renewByClient(
            @PathVariable String clientCode,
            @RequestBody Map<String, String> body) {

        var licenses = licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode);
        if (licenses.isEmpty()) return ResponseEntity.notFound().build();

        License license = licenses.stream()
                .filter(l -> "ACTIVE".equals(l.getStatus()))
                .findFirst()
                .orElse(licenses.get(0));

        return doRenew(license, body.get("expiresAt"));
    }

    private ResponseEntity<Map<String, Object>> doRenew(License license, String expiresAt) {
        if (clientConfigRepository.findByClientCode(license.getClientCode()).isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Client not found: " + license.getClientCode()));
        }

        String newExpiresAt = (expiresAt != null && !expiresAt.isBlank()) ? expiresAt : null;
        var ent = clientConfigService.licenseEntitlementsFor(license.getClientCode());
        String newKey = licenseKeyService.generateLicense(
                license.getMachineId(), license.getClientName(), license.getClientCode(),
                newExpiresAt, ent.baseCurrency(), ent.features(),
                ent.colorPrimary(), ent.colorSecondary(), ent.adminUsername(), ent.adminPassword(), ent.profile());

        license.setLicenseKey(newKey);
        license.setStatus("ACTIVE");
        license.setExpiresAt(newExpiresAt != null ? LocalDate.parse(newExpiresAt) : null);
        license.setRevokedAt(null);
        license.setRenewedAt(LocalDateTime.now());
        licenseRepository.save(license);

        return ResponseEntity.ok(Map.of(
            "message",    "License renewed",
            "licenseKey", newKey,
            "clientCode", license.getClientCode()
        ));
    }

    // -- Stage-1 batch re-issue (license-driven distribution P2, spec 6.2 / 7) --------

    @GetMapping("/reissue-v2/preview")
    public com.daman.admin.service.LicenseReissueService.ReissuePreview reissueV2Preview() {
        return licenseReissueService.preview();
    }

    @PostMapping("/reissue-v2")
    public ResponseEntity<byte[]> reissueV2() {
        var result = licenseReissueService.reissueAll();
        byte[] zip = licenseBundleZipper.zip(result);
        String filename = "daman-v2-licences-" + LocalDate.now() + ".zip";
        return ResponseEntity.ok()
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("X-Reissued-Count", String.valueOf(result.reissued().size()))
                .header("X-Skipped-Count", String.valueOf(result.skipped().size()))
                .body(zip);
    }

    /**
     * Undo the last in-place v2 re-issue for one licence (from {@code reissue-v2}):
     * restore its {@code previousLicenseKey} as the active key. One-shot — a second
     * call returns 400 (nothing left to revert). Admin-token guarded via {@link
     * com.daman.admin.config.AuthFilter}, same as the sibling reissue endpoints.
     */
    @PostMapping("/{id}/revert-reissue")
    public ResponseEntity<?> revertReissue(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(licenseReissueService.revertReissue(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        if (!licenseRepository.existsById(id)) return ResponseEntity.notFound().build();
        licenseRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Public key ───────────────────────────────────────────────────────────

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        return ResponseEntity.ok(Map.of("publicKey", licenseKeyService.getPublicKeyPem()));
    }
}
