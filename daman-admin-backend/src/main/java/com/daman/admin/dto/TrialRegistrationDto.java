package com.daman.admin.dto;

import com.daman.admin.entity.TrialRegistration;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Row for the admin Trials tab. {@code dayNumber}/{@code totalDays} are derived ONLY from the
 * registration row's own {@code registeredAt}/{@code expiresAt} - never from a desktop-reported
 * figure (a desktop can report Integer.MAX_VALUE as a tamper sentinel, which is not a day count).
 */
public record TrialRegistrationDto(
        Long id, String clientCode, String shopName, String phone, String address,
        String storeType, Boolean hasTables, String baseCurrency,
        LocalDateTime registeredAt, LocalDateTime expiresAt, LocalDateTime convertedAt,
        int dayNumber, int totalDays, String state) {

    public static TrialRegistrationDto of(TrialRegistration t, LocalDateTime now) {
        int total = (int) Math.max(1, Math.ceil(Duration.between(t.getRegisteredAt(), t.getExpiresAt()).toHours() / 24.0));
        long elapsedDays = Math.max(0, Duration.between(t.getRegisteredAt(), now).toDays());
        int day = (int) Math.min(total, elapsedDays + 1);
        String state = t.getConvertedAt() != null ? "CONVERTED"
                : !now.toLocalDate().isAfter(t.getExpiresAt().toLocalDate()) ? "ACTIVE" : "EXPIRED";
        return new TrialRegistrationDto(t.getId(), t.getClientCode(), t.getShopName(), t.getPhone(),
                t.getAddress(), t.getStoreType(), t.getHasTables(), t.getBaseCurrency(),
                t.getRegisteredAt(), t.getExpiresAt(), t.getConvertedAt(), day, total, state);
    }
}
