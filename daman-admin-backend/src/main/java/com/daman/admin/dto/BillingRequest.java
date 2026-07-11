package com.daman.admin.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BillingRequest {
    private Long licenseId;
    private BigDecimal amount;
    private String paymentMethod;
    private String paymentStatus;
    private String invoiceRef;
    private String paymentDate;
    private String supportStartDate;
    private String supportEndDate;
    private String notes;
}
