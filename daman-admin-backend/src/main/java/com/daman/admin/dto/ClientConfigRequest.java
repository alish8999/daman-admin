package com.daman.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClientConfigRequest {

    /** Ignored on create (the server auto-generates an immutable code from the
     *  app name) and on update (the path param is authoritative). Kept only so
     *  older callers that still send it don't fail validation. */
    private String clientCode;

    @NotBlank
    private String appName;

    /** Deprecated — no longer sent by the admin UI. The server sets this to the
     *  app name in {@code applyRequest()}; kept as a column for per-client builds. */
    private String tagline;

    @NotBlank
    private String logoDark;

    /** Deprecated — the admin UI collects one logo; the server mirrors logoDark
     *  here when this is blank (see {@code applyRequest()}). */
    private String logoLight;

    /** Deprecated — always the default; no longer shown in the admin UI. */
    private String favicon;

    @NotBlank
    private String colorPrimary;

    @NotBlank
    private String colorSecondary;

    /** success/danger/warning/info are no longer edited in the admin UI; they
     *  keep their framework defaults (sent by the form, or the entity default). */
    private String colorSuccess;
    private String colorDanger;
    private String colorWarning;
    private String colorInfo;

    private String footerDeveloper;
    private String footerUrl;

    /** Store type — selects the demo data seeder. One of: mobile, grocery, clothing, pharmacy, hardware, bookstore, cafe, general */
    private String storeType;

    /** Deprecated — no longer sent by the admin UI. The device decides its own
     *  base currency at first run (or already has it locked). Retained so a
     *  request that still carries it is honoured; a blank/absent value leaves
     *  the stored value untouched (see {@code applyRequest}). */
    private String baseCurrency;

    /** Optional data-URL or asset path for the dashboard header background image. */
    private String dashboardHeaderImage;

    @NotBlank
    private String adminUsername;

    @NotBlank
    private String adminPassword;

    private String phone;
    private String email;
    private String pointOfContact;
    private String defaultBuildTarget;

    private FeaturesRequest features;

    // Client status
    private String clientStatus;
    private String clientNotes;
}
