package com.daman.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClientConfigRequest {

    @NotBlank
    private String clientCode;

    @NotBlank
    private String appName;

    @NotBlank
    private String tagline;

    @NotBlank
    private String logoDark;

    @NotBlank
    private String logoLight;

    private String favicon;

    @NotBlank
    private String colorPrimary;

    @NotBlank
    private String colorSecondary;

    @NotBlank
    private String colorSuccess;

    @NotBlank
    private String colorDanger;

    @NotBlank
    private String colorWarning;

    @NotBlank
    private String colorInfo;

    private String footerDeveloper;
    private String footerUrl;

    /** Store type — selects the demo data seeder. One of: mobile, grocery, clothing, pharmacy, hardware, bookstore, cafe, general */
    private String storeType;

    /** Base currency for all financial records. One of: USD, SYP, SYP_OLD. Defaults to USD. */
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
