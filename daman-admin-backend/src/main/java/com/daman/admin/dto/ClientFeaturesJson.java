package com.daman.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted as JSON in {@code client_configs.features_json} and exported under {@code features} in client.config.json.
 */
@Data
@NoArgsConstructor
public class ClientFeaturesJson {
    private boolean multiLanguage;
    private boolean barcode;
    private boolean reports;
    private boolean suppliers;
    private boolean seedDemoData;
    private boolean multiCurrency = true;
    private boolean shifts;
    private boolean clientLedger;
    private boolean supplierLedger;
    private boolean fractionalQuantity;
    /** Per-product pricing-currency override (e.g. fixed 2 500 SYP for MTN cards). Default: off — opt-in per client. */
    private boolean multiCurrencyPricing;
    /** Client/Supplier Account Statement report page (nav link + route). Default: off — opt-in per client. */
    private boolean accountStatement;
    /** Per-item Item Ledger report page (nav link + route). Default: off — opt-in per client. */
    private boolean itemLedger;
    /**
     * Batch stocktake / inventory reconciliation (تسوية الجرد المجمّعة).
     * Adds the bulk "actual qty vs system qty" reconciliation page and unlocks
     * the {@code MONTHLY_RECONCILIATION} stock-adjustment reason in P&amp;L
     * (wastage / inventory gain). Default: off — opt-in per client.
     */
    private boolean batchStocktake;
    /**
     * Bulk category price update (تعديل أسعار قسم بالكامل). Adds the bulk
     * repricing action on the Products page with per-currency adjustment inputs.
     * Default: off — opt-in per client.
     */
    private boolean bulkPriceUpdate;
    /**
     * BOM / recipe products for F&B (cafés, restaurants). Composite finished goods
     * backflush raw-material stock on sale. Default: off — opt-in per client.
     */
    private boolean productRecipes;
    /**
     * Manufacturing orders (batch / make-to-stock). Lets recipe products be
     * pre-produced to stock and sold from finished-good inventory. Default: off.
     */
    private boolean manufacturing;
    /** User & Permissions management in Settings (admin assigns per-user flags). Default: off — ultimate tier. */
    private boolean userManagement;
    /** Invoice Settings card in Settings (phones, address, tax number on invoice). Default: off — pro tier. */
    private boolean invoiceSettings;

    public ClientFeaturesJson(boolean multiLanguage, boolean barcode, boolean reports) {
        this.multiLanguage = multiLanguage;
        this.barcode = barcode;
        this.reports = reports;
    }
}
