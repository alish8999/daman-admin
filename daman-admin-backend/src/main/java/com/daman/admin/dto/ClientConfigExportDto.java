package com.daman.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Nested DTO matching the format expected by daman-frontend's ClientConfigService.
 * Written to client.config.json during deployment packaging.
 */
@Data
@Builder
public class ClientConfigExportDto {

    private String clientCode;
    private String appName;
    private String storeType;
    private String baseCurrency;
    private String tagline;
    private LogoDto logo;
    private String favicon;
    private ColorsDto colors;
    private FooterDto footer;
    private DashboardDto dashboard;
    private CredentialsDto credentials;
    private FeaturesDto features;

    @Data
    @Builder
    public static class CredentialsDto {
        private String username;
        private String password;
    }

    @Data
    @Builder
    public static class LogoDto {
        private String dark;
        private String light;
    }

    @Data
    @Builder
    public static class ColorsDto {
        private String primary;
        private String secondary;
        private String success;
        private String danger;
        private String warning;
        private String info;
    }

    @Data
    @Builder
    public static class FooterDto {
        private String developer;
        private String url;
    }

    @Data
    @Builder
    public static class DashboardDto {
        private String headerImage;
    }

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
        /** Quotations / price-quote workflow (عروض الأسعار). */
        private boolean quotation;
        /** Accounting module — double-entry bookkeeping, P&L, Balance Sheet, journal entries. */
        private boolean accounting;
    }
}
