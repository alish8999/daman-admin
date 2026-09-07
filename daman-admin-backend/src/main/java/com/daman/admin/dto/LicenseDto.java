package com.daman.admin.dto;

import com.daman.admin.entity.License;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** The licence shape the admin frontend consumes: the entity fields + the parsed payload version. */
public record LicenseDto(
        Long id, String clientCode, String machineId, String licenseKey, String status,
        String clientName, String label, LocalDate expiresAt, String deviceInfo,
        LocalDateTime activatedAt, LocalDateTime revokedAt, LocalDateTime renewedAt,
        int payloadVersion, boolean reissued) {

    public static LicenseDto of(License l, int payloadVersion) {
        return new LicenseDto(
                l.getId(), l.getClientCode(), l.getMachineId(), l.getLicenseKey(), l.getStatus(),
                l.getClientName(), l.getLabel(), l.getExpiresAt(), l.getDeviceInfo(),
                l.getActivatedAt(), l.getRevokedAt(), l.getRenewedAt(), payloadVersion,
                l.getPreviousLicenseKey() != null);
    }
}
