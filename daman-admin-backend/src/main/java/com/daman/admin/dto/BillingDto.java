package com.daman.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class BillingDto {
    private Long id;
    private String clientCode;
    private Long licenseId;
    private BigDecimal amount;
    private String paymentMethod;
    private String paymentStatus;
    private String invoiceRef;
    private LocalDate paymentDate;
    private LocalDate supportStartDate;
    private LocalDate supportEndDate;
    private String notes;
    private LocalDateTime createdAt;
}
