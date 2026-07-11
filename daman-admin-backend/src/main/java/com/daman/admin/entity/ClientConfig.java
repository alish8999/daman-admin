package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "client_configs")
public class ClientConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String clientCode;

    @Column(nullable = false)
    private String appName;

    @Column(nullable = false)
    private String tagline;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String logoDark;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String logoLight;

    @Column(columnDefinition = "TEXT")
    private String favicon;

    @Column(nullable = false)
    private String colorPrimary;

    @Column(nullable = false)
    private String colorSecondary;

    @Column(nullable = false)
    private String colorSuccess;

    @Column(nullable = false)
    private String colorDanger;

    @Column(nullable = false)
    private String colorWarning;

    @Column(nullable = false)
    private String colorInfo;

    private String footerDeveloper;
    private String footerUrl;

    @Column(nullable = true)
    private String adminUsername;

    @Column(nullable = true)
    private String adminPassword;

    /** Store type used to select the demo data seeder (e.g. "mobile", "grocery"). */
    @Column(name = "store_type", length = 50)
    private String storeType;

    /**
     * Base currency for all financial records in this client's deployment.
     * All prices (products, sales, purchases) are stored in this currency.
     * One of: "USD", "SYP", "SYP_OLD". Defaults to "USD".
     */
    @Column(name = "base_currency", length = 10, columnDefinition = "varchar(10) default 'USD'")
    private String baseCurrency = "USD";

    /** Optional background image (data-URL or asset path) for the dashboard header. */
    @Column(name = "dashboard_header_image", columnDefinition = "TEXT")
    private String dashboardHeaderImage;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "point_of_contact")
    private String pointOfContact;

    @Column(name = "default_build_target", length = 10, columnDefinition = "varchar(10) default 'win'")
    private String defaultBuildTarget = "win";

    /** JSON: {@link com.daman.admin.dto.ClientFeaturesJson} */
    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    // ── Client status ──────────────────────────────────────────────────────

    /** Overall client status: ACTIVE, TRIAL, DUMMY */
    @Column(name = "client_status", length = 20)
    private String clientStatus;

    /** Free-text internal notes about this client (visible only in admin). */
    @Column(name = "client_notes", columnDefinition = "TEXT")
    private String clientNotes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
