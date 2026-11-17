package com.daman.admin.dto;

import com.daman.admin.entity.License;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The licence shape the admin frontend consumes: the entity fields, the parsed payload version,
 * and whether the licence is actually usable today.
 *
 * <p>{@code live} is not the same as {@code status == "ACTIVE"} — 43 of the production fleet's 101
 * ACTIVE licences have an expiry already in the past. The portal counts live ones.
 *
 * <p>{@code effectiveFeatures} and {@code featureOverride} are null on the list endpoints, which
 * would otherwise resolve entitlements once per row. They are populated only where a caller asked
 * about one licence's features.
 */
public record LicenseDto(
        Long id, String clientCode, String machineId, String licenseKey, String status,
        String clientName, String label, LocalDate expiresAt, String deviceInfo,
        LocalDateTime activatedAt, LocalDateTime revokedAt, LocalDateTime renewedAt,
        int payloadVersion, boolean reissued, boolean live,
        java.util.Map<String, Boolean> effectiveFeatures,
        java.util.Map<String, Boolean> featureOverride) {

    public static LicenseDto of(License l, int payloadVersion) {
        return of(l, payloadVersion, null, null);
    }

    public static LicenseDto of(License l, int payloadVersion,
                                java.util.Map<String, Boolean> effectiveFeatures,
                                java.util.Map<String, Boolean> featureOverride) {
        return new LicenseDto(
                l.getId(), l.getClientCode(), l.getMachineId(), l.getLicenseKey(), l.getStatus(),
                l.getClientName(), l.getLabel(), l.getExpiresAt(), l.getDeviceInfo(),
                l.getActivatedAt(), l.getRevokedAt(), l.getRenewedAt(), payloadVersion,
                l.getPreviousLicenseKey() != null,
                com.daman.admin.service.LicenceLiveness.isLive(l, LocalDate.now()),
                effectiveFeatures, featureOverride);
    }
}
