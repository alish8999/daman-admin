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

    /** The base currency baked into this client's v2 licence payload, where it PRE-SELECTS the
     *  currency on the desktop's first-run setup screen. Sent by the admin UI again as of
     *  2026-11-18 (it had been dropped when the device started choosing its own).
     *
     *  <p>It cannot override a shop that has already locked one: {@code BaseCurrencyGuard}
     *  trusts the stored {@code app_settings.base_currency} unconditionally and
     *  {@code InstallationSetupServiceImpl.complete()} rejects a contradicting choice. A
     *  blank/absent value leaves the stored value untouched (see {@code applyRequest}).
     *
     *  <p><b>Changing it does not re-mint.</b> Only a feature change does, so an edit here
     *  reaches a shop at its next renewal or feature save, not immediately. */
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

    /** Business address. Travels in the v2 licence profile, where it pre-fills the shop's
     *  mandatory setup screen on a fresh install, so it is collected here and nowhere else. */
    private String address;
    private String defaultBuildTarget;

    private FeaturesRequest features;

    // Client status
    private String clientStatus;
    private String clientNotes;
}
