package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "licenses")
public class License {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String clientCode;

    @Column(nullable = false)
    private String machineId;

    /** Optional label to identify the purpose of this license (e.g. "Main Store", "Testing", "Branch 2"). */
    @Column(name = "label", length = 100)
    private String label;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String licenseKey;

    /**
     * The key this row held immediately before the last in-place v2 re-issue
     * ({@link com.daman.admin.service.LicenseReissueService#reissueAll()}).
     * Null until a re-issue overwrites the key. Lets a bad batch re-issue be
     * undone from the DB alone, without the downloaded rollback bundle.
     */
    @Column(name = "previous_license_key", columnDefinition = "TEXT")
    private String previousLicenseKey;

    @Column(nullable = false)
    private String status; // ACTIVE, REVOKED

    private String clientName;

    private LocalDate expiresAt;

    private String deviceInfo;

    @CreationTimestamp
    @Column(name = "activated_at", updatable = false)
    private LocalDateTime activatedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "renewed_at")
    private LocalDateTime renewedAt;
}
