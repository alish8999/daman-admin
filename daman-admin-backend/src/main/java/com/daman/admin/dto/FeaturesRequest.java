package com.daman.admin.dto;

import lombok.Data;

@Data
public class FeaturesRequest {
    private Boolean multiLanguage;
    private Boolean barcode;
    private Boolean reports;
    private Boolean suppliers;
    private Boolean seedDemoData;
    private Boolean multiCurrency;
    private Boolean shifts;
    private Boolean clientLedger;
    private Boolean supplierLedger;
    private Boolean fractionalQuantity;
    /** Per-product pricing-currency override (e.g. fixed 2 500 SYP for MTN cards). */
    private Boolean multiCurrencyPricing;
    /** Client/Supplier Account Statement report page (nav link + route). */
    private Boolean accountStatement;
    /** Per-item Item Ledger report page (nav link + route). */
    private Boolean itemLedger;
}
