package com.daman.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of the anonymous {@code POST /api/trial/register}. Deliberately has no client code,
 * no expiry and no day count: the server decides all three. Unknown properties are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrialRegisterRequest(String machineId, String deviceInfo, String shopName, String phone,
                                   String address, String storeType, Boolean hasTables, String baseCurrency) {}
