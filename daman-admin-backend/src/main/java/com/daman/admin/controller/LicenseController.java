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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/licenses")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseKeyService licenseKeyService;
    private final LicenseRepository licenseRepository;
    private final ClientConfigRepository clientConfigRepository;

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
        String licenseKey = licenseKeyService.generateLicense(machineId, clientName, clientCode, expiresAt);

        License license = new License();
        license.setClientCode(clientCode);
        license.setMachineId(machineId);
        license.setLicenseKey(licenseKey);
        license.setClientName(clientName);
        license.setStatus("ACTIVE");
        license.setLabel(label.isBlank() ? null : label);
        license.setExpiresAt(expiresAt != null && !expiresAt.isBlank() ? LocalDate.parse(expiresAt) : null);
        licenseRepository.save(license);

        return ResponseEntity.ok(Map.of(
            "licenseKey", licenseKey,
            "clientName", clientName,
            "machineId",  machineId,
            "label",      label
        ));
    }

    // ── Activate (called by the desktop app) ─────────────────────────────────

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, String> body) {
        String machineId  = body.get("machineId");
        String clientCode = body.get("clientCode");

        if (machineId == null || clientCode == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "machineId and clientCode are required"));
        }

        // Return existing active license for this machine if present
        var existing = licenseRepository.findByMachineIdAndClientCodeAndStatus(machineId, clientCode, "ACTIVE");
        if (existing.isPresent()) {
            return ResponseEntity.ok(Map.of(
                "licenseKey", existing.get().getLicenseKey(),
                "message",    "Already activated"
            ));
        }

        var clientConfig = clientConfigRepository.findByClientCode(clientCode).orElse(null);
        if (clientConfig == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Client not found"));
        }

        String clientName = clientConfig.getAppName();
        String licenseKey = licenseKeyService.generateLicense(machineId, clientName, clientCode, null);

        License license = new License();
        license.setClientCode(clientCode);
        license.setMachineId(machineId);
        license.setLicenseKey(licenseKey);
        license.setClientName(clientName);
        license.setStatus("ACTIVE");
        license.setDeviceInfo(body.getOrDefault("deviceInfo", ""));
        licenseRepository.save(license);

        return ResponseEntity.ok(Map.of("licenseKey", licenseKey, "clientName", clientName));
    }

    // ── List ─────────────────────────────────────────────────────────────────

    @GetMapping
    public List<License> getAll() {
        return licenseRepository.findAllByOrderByActivatedAtDesc();
    }

    @GetMapping("/client/{clientCode}")
    public List<License> getByClient(@PathVariable String clientCode) {
        return licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode);
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
        String newExpiresAt = (expiresAt != null && !expiresAt.isBlank()) ? expiresAt : null;
        String newKey = licenseKeyService.generateLicense(
                license.getMachineId(), license.getClientName(), license.getClientCode(), newExpiresAt);

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
