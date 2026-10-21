package com.daman.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of the anonymous {@code POST /api/trial/register}. Deliberately has no client code,
 * no expiry and no day count: the server decides all three. Unknown properties are ignored.
 *
 * <p>{@code restoreOnly} asks only "does this machine already have a trial?" - it returns the
 * existing key, or 404 {@code no_trial_for_machine}, and NEVER mints. Every other field is
 * ignored for such a request, because the machine-id lookup already runs before validation.
 * It exists so the desktop can skip a form whose values would be discarded.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrialRegisterRequest(String machineId, String deviceInfo, String shopName, String phone,
                                   String address, String storeType, Boolean hasTables, String baseCurrency,
                                   Boolean restoreOnly) {}
