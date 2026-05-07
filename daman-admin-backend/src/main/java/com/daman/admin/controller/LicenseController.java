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

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        String machineId = body.get("machineId");
        String clientCode = body.get("clientCode");
        String expiresAt = body.get("expiresAt");

        if (machineId == null || machineId.isBlank() || clientCode == null || clientCode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "machineId and clientCode are required"));
        }

        var clientConfig = clientConfigRepository.findByClientCode(clientCode).orElse(null);
        if (clientConfig == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Client not found: " + clientCode));
        }

        // Enforce 1:1 — one license per client
        if (licenseRepository.findByClientCode(clientCode).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A license already exists for this client. Use Renew to update it."));
        }

        String clientName = clientConfig.getAppName();
        String licenseKey = licenseKeyService.generateLicense(machineId, clientName, clientCode, expiresAt);

        License license = new License();
        license.setClientCode(clientCode);
        license.setMachineId(machineId);
        license.setLicenseKey(licenseKey);
        license.setClientName(clientName);
        license.setStatus("ACTIVE");
        license.setExpiresAt(expiresAt != null && !expiresAt.isBlank() ? LocalDate.parse(expiresAt) : null);
        licenseRepository.save(license);

        return ResponseEntity.ok(Map.of(
            "licenseKey", licenseKey,
            "clientName", clientName,
            "machineId", machineId
        ));
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, String> body) {
        String machineId = body.get("machineId");
        String clientCode = body.get("clientCode");

        if (machineId == null || clientCode == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "machineId and clientCode are required"));
        }

        // If the client's single license is active and machine matches, return it
        var existing = licenseRepository.findByMachineIdAndClientCodeAndStatus(machineId, clientCode, "ACTIVE");
        if (existing.isPresent()) {
            return ResponseEntity.ok(Map.of(
                "licenseKey", existing.get().getLicenseKey(),
                "message", "Already activated"
            ));
        }

        var clientConfig = clientConfigRepository.findByClientCode(clientCode).orElse(null);
        if (clientConfig == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Client not found"));
        }

        // Only create if no license exists yet (1:1 guard)
        if (licenseRepository.findByClientCode(clientCode).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "License exists for this client but machine ID does not match."));
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

    @GetMapping
    public List<License> getAll() {
        return licenseRepository.findAllByOrderByActivatedAtDesc();
    }

    @GetMapping("/client/{clientCode}")
    public List<License> getByClient(@PathVariable String clientCode) {
        return licenseRepository.findByClientCodeOrderByActivatedAtDesc(clientCode);
    }

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
        License license = licenseRepository.findByClientCode(clientCode).orElse(null);
        if (license == null) return ResponseEntity.notFound().build();
        license.setStatus("REVOKED");
        license.setRevokedAt(LocalDateTime.now());
        licenseRepository.save(license);
        return ResponseEntity.ok(Map.of("message", "License revoked"));
    }

    @PutMapping("/client/{clientCode}/renew")
    public ResponseEntity<Map<String, Object>> renewByClient(
            @PathVariable String clientCode,
            @RequestBody Map<String, String> body) {

        License license = licenseRepository.findByClientCode(clientCode).orElse(null);
        if (license == null) {
            return ResponseEntity.notFound().build();
        }

        String expiresAt = body.get("expiresAt");
        String newExpiresAt = (expiresAt != null && !expiresAt.isBlank()) ? expiresAt : null;

        // Re-generate license key with updated expiry
        String newKey = licenseKeyService.generateLicense(
                license.getMachineId(), license.getClientName(), clientCode, newExpiresAt);

        license.setLicenseKey(newKey);
        license.setStatus("ACTIVE");
        license.setExpiresAt(newExpiresAt != null ? LocalDate.parse(newExpiresAt) : null);
        license.setRevokedAt(null);
        license.setRenewedAt(LocalDateTime.now());
        licenseRepository.save(license);

        return ResponseEntity.ok(Map.of(
            "message", "License renewed",
            "licenseKey", newKey,
            "clientCode", clientCode
        ));
    }

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        return ResponseEntity.ok(Map.of("publicKey", licenseKeyService.getPublicKeyPem()));
    }
}
