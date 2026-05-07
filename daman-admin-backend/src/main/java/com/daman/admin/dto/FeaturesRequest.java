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
}
