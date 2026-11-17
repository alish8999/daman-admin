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

    /**
     * {@code previousPayloadVersion} is the {@code "v"} of {@code previousLicenseKey}, or null
     * when there is none.
     *
     * <p><b>It decides {@code reissued}, and that is not pedantry.</b> The flag drives the
     * portal's "Revert re-issue" button, which swaps the stored previous key back in. That is
     * only a meaningful offer when the previous key is a <b>v1</b> one — a genuine v1→v2
     * re-issue, which is the only case {@code LicenseReissueService} records. A feature re-mint
     * also leaves a previous key behind, and offering to "revert" to it would restore a v2 key
     * with stale features, leaving the licence instantly drifting again. So a bare
     * {@code previousLicenseKey != null} is the wrong test.
     */
    public static LicenseDto of(License l, int payloadVersion, Integer previousPayloadVersion) {
        return of(l, payloadVersion, previousPayloadVersion, null, null);
    }

    public static LicenseDto of(License l, int payloadVersion, Integer previousPayloadVersion,
                                java.util.Map<String, Boolean> effectiveFeatures,
                                java.util.Map<String, Boolean> featureOverride) {
        return new LicenseDto(
                l.getId(), l.getClientCode(), l.getMachineId(), l.getLicenseKey(), l.getStatus(),
                l.getClientName(), l.getLabel(), l.getExpiresAt(), l.getDeviceInfo(),
                l.getActivatedAt(), l.getRevokedAt(), l.getRenewedAt(), payloadVersion,
                previousPayloadVersion != null && previousPayloadVersion < 2,
                com.daman.admin.service.LicenceLiveness.isLive(l, LocalDate.now()),
                effectiveFeatures, featureOverride);
    }
}
