package com.daman.admin.service;

import com.daman.admin.entity.License;
import com.daman.admin.repository.LicenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What each stored licence key actually grants, against what it should grant.
 *
 * <p>This is the measurement behind spec §13.1. Live on 2026-11-17, 8 of 95 comparable ACTIVE
 * licences disagreed with their client's record — five in the "owner enabled a feature and no key
 * was re-minted" direction, and three the other way, where the running key grants something the
 * record denies ({@code viora}'s {@code multiCurrencyPricing}, {@code c-14}'s
 * {@code consignment}). The second kind is why a reconcile is previewed and owner-run rather than
 * a side effect of an unrelated save.
 *
 * <p>Only keys present in BOTH sides are compared: a flag added to the catalogue after a key was
 * minted is not drift, and neither is a retired one still sitting in an old payload.
 */
class LicenceDriftScannerTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    LicenseRepository licenses;
    LicenseKeyService keys;
    ClientConfigService configs;
    LicenceDriftScanner scanner;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        licenses = mock(LicenseRepository.class);
        keys = mock(LicenseKeyService.class);
        configs = mock(ClientConfigService.class);
        scanner = new LicenceDriftScanner(licenses, keys, configs);
    }

    private License licence(long id, String code, String status, LocalDate expiry) {
        License l = new License();
        l.setId(id);
        l.setClientCode(code);
        l.setMachineId("m-" + id);
        l.setLabel("live");
        l.setStatus(status);
        l.setExpiresAt(expiry);
        l.setLicenseKey("key-" + id);
        return l;
    }

    /** Stubs what the stored key's payload says, and what the resolved set says. */
    private void keySays(License l, Map<String, Boolean> inKey) {
        StringBuilder json = new StringBuilder("{\"machineId\":\"" + l.getMachineId() + "\",\"features\":{");
        boolean first = true;
        for (var e : inKey.entrySet()) {
            if (!first) json.append(',');
            json.append('"').append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        json.append("}}");
        when(keys.verifiedPayload(l.getLicenseKey())).thenReturn(mapper.readTree(json.toString()));
    }

    private void resolvedSays(License l, Map<String, Boolean> resolved) {
        when(configs.licenseEntitlementsFor(l)).thenReturn(new ClientConfigService.LicenseEntitlements(
                "SYP", resolved, null, null, null, null,
                new LicenseProfile("Shop", null, null, null, null)));
    }

    @Test
    void aLicenceWhoseKeyMatchesItsResolvedSetIsNotReported() {
        License l = licence(1, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", true, "shifts", false));
        resolvedSays(l, Map.of("accounting", true, "shifts", false));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));

        assertThat(scanner.scanAll(TODAY)).isEmpty();
    }

    /** viora / c-14: the key grants more than the record. */
    @Test
    void aFlagTheKeyGrantsButTheResolvedSetDeniesIsReported() {
        License l = licence(2, "viora", "ACTIVE", null);
        keySays(l, Map.of("multiCurrencyPricing", true));
        resolvedSays(l, Map.of("multiCurrencyPricing", false));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));

        assertThat(scanner.scanAll(TODAY)).singleElement().satisfies(row -> {
            assertThat(row.licenceId()).isEqualTo(2L);
            assertThat(row.clientCode()).isEqualTo("viora");
            assertThat(row.changes()).containsExactly(
                    new LicenceDriftScanner.FlagChange("multiCurrencyPricing", true, false));
        });
    }

    /** The common direction: a sold add-on that never reached the shop. */
    @Test
    void aFlagTheResolvedSetGrantsButTheKeyLacksIsReported() {
        License l = licence(3, "alkaram", "ACTIVE", null);
        keySays(l, Map.of("currencyExchange", false));
        resolvedSays(l, Map.of("currencyExchange", true));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));

        assertThat(scanner.scanAll(TODAY)).singleElement().satisfies(row ->
                assertThat(row.changes()).containsExactly(
                        new LicenceDriftScanner.FlagChange("currencyExchange", false, true)));
    }

    @Test
    void aFlagPresentOnlyInTheKeyIsNotReported() {
        License l = licence(4, "c-1", "ACTIVE", null);
        keySays(l, Map.of("retiredFlag", true, "accounting", true));
        resolvedSays(l, Map.of("accounting", true));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));

        assertThat(scanner.scanAll(TODAY)).isEmpty();
    }

    @Test
    void aFlagPresentOnlyInTheResolvedSetIsNotReported() {
        License l = licence(5, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", true));
        resolvedSays(l, Map.of("accounting", true, "brandNewFlag", true));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));

        assertThat(scanner.scanAll(TODAY)).isEmpty();
    }

    @Test
    void revokedAndExpiredLicencesAreSkipped() {
        License revoked = licence(6, "c-1", "REVOKED", null);
        License expired = licence(7, "c-2", "ACTIVE", TODAY.minusDays(1));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(revoked, expired));

        assertThat(scanner.scanAll(TODAY)).isEmpty();
    }

    /** Six orphan rows exist in production; one must not abort the scan. */
    @Test
    void anOrphanLicenceIsSkippedNotThrown() {
        License orphan = licence(8, "gone", "ACTIVE", null);
        License ok = licence(9, "c-1", "ACTIVE", null);
        keySays(orphan, Map.of("accounting", true));
        keySays(ok, Map.of("accounting", false));
        resolvedSays(ok, Map.of("accounting", true));
        when(configs.licenseEntitlementsFor(orphan))
                .thenThrow(new IllegalArgumentException("Client not found: gone"));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(orphan, ok));

        assertThat(scanner.scanAll(TODAY)).singleElement()
                .satisfies(row -> assertThat(row.licenceId()).isEqualTo(9L));
    }

    @Test
    void aKeyThatDoesNotVerifyIsSkipped() {
        License l = licence(10, "c-1", "ACTIVE", null);
        when(keys.verifiedPayload(l.getLicenseKey())).thenReturn(null);
        resolvedSays(l, Map.of("accounting", true));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));

        assertThat(scanner.scanAll(TODAY)).isEmpty();
    }

    @Test
    void aPayloadWithNoFeaturesObjectIsSkipped() {
        License l = licence(11, "c-1", "ACTIVE", null);
        when(keys.verifiedPayload(l.getLicenseKey())).thenReturn(mapper.readTree("{\"machineId\":\"m-11\"}"));
        resolvedSays(l, Map.of("accounting", true));
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));

        assertThat(scanner.scanAll(TODAY)).isEmpty();
    }

    @Test
    void scanClientReturnsOnlyThatClientsRows() {
        License l = licence(12, "viora", "ACTIVE", null);
        keySays(l, Map.of("consignment", true));
        resolvedSays(l, Map.of("consignment", false));
        when(licenses.findByClientCodeOrderByActivatedAtDesc("viora")).thenReturn(List.of(l));

        assertThat(scanner.scanClient("viora", TODAY)).singleElement()
                .satisfies(row -> assertThat(row.clientCode()).isEqualTo("viora"));
    }

    /** The comparison rule on its own, so the remint path can share it without a repository. */
    @Test
    void changesComparesOnlyCommonlyPresentKeys() {
        var changes = LicenceDriftScanner.changes(
                Map.of("a", true, "onlyInKey", true),
                Map.of("a", false, "onlyInResolved", true));
        assertThat(changes).containsExactly(new LicenceDriftScanner.FlagChange("a", true, false));
    }

    @Test
    void changesIsEmptyWhenEverythingAgrees() {
        assertThat(LicenceDriftScanner.changes(Map.of("a", true), Map.of("a", true))).isEmpty();
    }
}
