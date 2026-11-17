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

    /**
     * How THIS licence's features differ from its client's default set — a sparse JSON object
     * holding only the flags that deviate (see {@link com.daman.admin.service.FeatureOverride}).
     *
     * <p><b>Null means inherit everything</b>, which is the value every licence carries until
     * someone deliberately customises one. That is also why the column is nullable and has no
     * default: {@code ddl-auto=update} has to add it to a {@code licenses} table that already
     * holds ~105 rows, and a NOT NULL column with no default cannot be added to a populated
     * table — Hibernate logs the failed ALTER, carries on, and the column silently never
     * exists. That exact failure shipped once already on {@code hosted_instances.demo}; see
     * {@code LicenseFeatureOverrideColumnTest}, which pins this declaration.
     *
     * <p>Sparse rather than a full copy on purpose: a copy would freeze this licence at the
     * moment it was customised, so a flag later enabled for the whole business would never
     * reach it and nothing would say so.
     */
    @Column(name = "features_override_json", nullable = true, columnDefinition = "TEXT")
    private String featuresOverrideJson;

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
