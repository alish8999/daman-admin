package com.daman.admin.service;

import com.daman.admin.entity.License;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Whether a licence is one a shop can actually run on today.
 *
 * <p><b>{@code status = 'ACTIVE'} is not that question.</b> It means only "not revoked". On the
 * production fleet, measured 2026-11-17, <b>43 of the 101 ACTIVE licences had an expiry already
 * in the past</b>: fourteen self-serve trials, three hosted stores, and about twenty labelled
 * {@code live} that lapsed in September or October. Those installs are locked.
 *
 * <p>So everything that reasons about working shops uses this one predicate: the feature re-mint
 * (minting a key for a locked shop achieves nothing and would have the portal claim a delivery
 * that cannot land) and the portal's "active licences" count. Expired rows stay visible and
 * marked — a lapsed customer is still a customer record — they simply are not live.
 *
 * <p>A licence is good <b>through</b> its expiry date, not up to the day before.
 */
public final class LicenceLiveness {

    private static final String ACTIVE = "ACTIVE";

    private LicenceLiveness() { }

    /** True when the licence is ACTIVE and either perpetual or not yet past its expiry. */
    public static boolean isLive(License licence, LocalDate today) {
        if (licence == null) return false;
        return isLive(licence.getStatus(), licence.getExpiresAt(), today);
    }

    /**
     * The same rule for callers holding loose values (a projection, a DTO, a test).
     *
     * <p>Status is trimmed and compared case-insensitively, because it is a hand-written string
     * column rather than an enum — but only {@code ACTIVE} itself counts, never a value that
     * merely starts with it.
     */
    public static boolean isLive(String status, LocalDate expiresAt, LocalDate today) {
        if (status == null || !ACTIVE.equals(status.trim().toUpperCase(Locale.ROOT))) return false;
        return expiresAt == null || !expiresAt.isBefore(today);
    }
}
