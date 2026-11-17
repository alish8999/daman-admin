package com.daman.admin.service;

import com.daman.admin.entity.License;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Live" means a licence a shop can actually run on today — not merely a row that was never
 * revoked.
 *
 * <p>Measured on the production fleet 2026-11-17: <b>43 of the 101 ACTIVE licences have an
 * expiry already in the past</b> (fourteen self-serve trials, three hosted stores, and about
 * twenty labelled {@code live} that lapsed in September or October). {@code status} alone says
 * only "not revoked", so anything that reasons about working shops — the feature re-mint, the
 * portal's licence count — has to use this predicate instead.
 */
class LicenceLivenessTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    @Test
    void activeWithNoExpiryIsLive() {
        assertThat(LicenceLiveness.isLive("ACTIVE", null, TODAY)).isTrue();
    }

    /** The boundary: a licence is good through its expiry date, not up to the day before. */
    @Test
    void activeExpiringTodayIsStillLive() {
        assertThat(LicenceLiveness.isLive("ACTIVE", TODAY, TODAY)).isTrue();
    }

    @Test
    void activeThatExpiredYesterdayIsNotLive() {
        assertThat(LicenceLiveness.isLive("ACTIVE", TODAY.minusDays(1), TODAY)).isFalse();
    }

    @Test
    void activeExpiringTomorrowIsLive() {
        assertThat(LicenceLiveness.isLive("ACTIVE", TODAY.plusDays(1), TODAY)).isTrue();
    }

    @Test
    void revokedIsNeverLive() {
        assertThat(LicenceLiveness.isLive("REVOKED", null, TODAY)).isFalse();
        assertThat(LicenceLiveness.isLive("REVOKED", TODAY.plusYears(1), TODAY)).isFalse();
    }

    @Test
    void statusIsComparedLenientlyButNotLoosely() {
        assertThat(LicenceLiveness.isLive(" active ", null, TODAY)).isTrue();
        assertThat(LicenceLiveness.isLive("Active", null, TODAY)).isTrue();
        assertThat(LicenceLiveness.isLive(null, null, TODAY)).isFalse();
        assertThat(LicenceLiveness.isLive("", null, TODAY)).isFalse();
        assertThat(LicenceLiveness.isLive("ACTIVE_PENDING", null, TODAY)).isFalse();
    }

    @Test
    void theEntityOverloadReadsStatusAndExpiryOffTheLicence() {
        License live = new License();
        live.setStatus("ACTIVE");
        live.setExpiresAt(null);
        assertThat(LicenceLiveness.isLive(live, TODAY)).isTrue();

        License lapsed = new License();
        lapsed.setStatus("ACTIVE");
        lapsed.setExpiresAt(TODAY.minusMonths(2));
        assertThat(LicenceLiveness.isLive(lapsed, TODAY)).isFalse();
    }

    @Test
    void aNullLicenceIsNotLive() {
        assertThat(LicenceLiveness.isLive((License) null, TODAY)).isFalse();
    }
}
