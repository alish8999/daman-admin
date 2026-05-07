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

    public ClientFeaturesJson(boolean multiLanguage, boolean barcode, boolean reports) {
        this.multiLanguage = multiLanguage;
        this.barcode = barcode;
        this.reports = reports;
    }
}
