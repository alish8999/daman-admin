package com.daman.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ClientConfigDto {
    private Long id;
    private String clientCode;
    private String appName;
    private String tagline;
    private String logoDark;
    private String logoLight;
    private String favicon;
    private String colorPrimary;
    private String colorSecondary;
    private String colorSuccess;
    private String colorDanger;
    private String colorWarning;
    private String colorInfo;
    private String footerDeveloper;
    private String footerUrl;
    private String storeType;
    private String baseCurrency;
    private String dashboardHeaderImage;
    private String adminUsername;
    private String adminPassword;
    private String phone;
    private String email;
    private String pointOfContact;
    private String defaultBuildTarget;
    private FeaturesDto features;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Client status & package
    private String packageTier;
    private String clientStatus;
    private String clientNotes;

    @Data
    @Builder
    public static class FeaturesDto {
        private boolean multiLanguage;
        private boolean barcode;
        private boolean reports;
        private boolean suppliers;
        private boolean seedDemoData;
        private boolean multiCurrency;
        private boolean shifts;
        private boolean clientLedger;
        private boolean supplierLedger;
        private boolean fractionalQuantity;
        /** Per-product pricing-currency override — MTN-style fixed local prices. */
        private boolean multiCurrencyPricing;
        /** Client/Supplier Account Statement report page. */
        private boolean accountStatement;
        /** Per-item Item Ledger report page. */
        private boolean itemLedger;
        /** Batch stocktake / inventory reconciliation page + MONTHLY_RECONCILIATION P&amp;L reason. */
        private boolean batchStocktake;
        /** Bulk category price update (تعديل أسعار قسم بالكامل). */
        private boolean bulkPriceUpdate;
        /** BOM / recipe products for F&B. */
        private boolean productRecipes;
        /** Manufacturing orders (batch / make-to-stock production). */
        private boolean manufacturing;
        /** User & Permissions management in Settings. */
        private boolean userManagement;
        /** Invoice Settings card in Settings. */
        private boolean invoiceSettings;
    }
}
