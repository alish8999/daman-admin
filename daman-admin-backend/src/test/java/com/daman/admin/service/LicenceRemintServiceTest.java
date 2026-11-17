package com.daman.admin.service;

import com.daman.admin.entity.License;
import com.daman.admin.event.LicenceRemintedEvent;
import com.daman.admin.repository.LicenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single owner of re-minting a licence key when its effective feature set changes.
 *
 * <p>Every rule here is load-bearing. A re-mint must not resurrect a revoked licence (hosted-store
 * re-provisioning leaves REVOKED rows behind routinely), must not mint for a locked shop (43 of
 * 101 ACTIVE licences are expired), must not die on an orphan (six exist), and must leave
 * {@code expiresAt} and {@code renewedAt} exactly as they were — a re-mint is not a renewal.
 */
class LicenceRemintServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    LicenseRepository licenses;
    LicenseKeyService keys;
    ClientConfigService configs;
    LicenceDriftScanner scanner;
    ApplicationEventPublisher events;
    LicenceRemintService service;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        licenses = mock(LicenseRepository.class);
        keys = mock(LicenseKeyService.class);
        configs = mock(ClientConfigService.class);
        scanner = mock(LicenceDriftScanner.class);
        events = mock(ApplicationEventPublisher.class);
        service = new LicenceRemintService(licenses, keys, configs, scanner, events);
        // Default every key to v2. A v1 key is refused outright (it would produce a half-open
        // install), and an unstubbed mock would answer 0 — so without this default every test
        // below would be exercising the v1 refusal instead of what it is about. The v1 test
        // overrides this for its own key.
        when(keys.payloadVersion(org.mockito.ArgumentMatchers.anyString())).thenReturn(2);
    }

    private License licence(long id, String code, String status, LocalDate expiry) {
        License l = new License();
        l.setId(id);
        l.setClientCode(code);
        l.setMachineId("m-" + id);
        l.setStatus(status);
        l.setExpiresAt(expiry);
        l.setLicenseKey("key-" + id);
        return l;
    }

    private void keySays(License l, Map<String, Boolean> inKey) {
        StringBuilder json = new StringBuilder("{\"features\":{");
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

    private void clientHas(String code, License... rows) {
        when(licenses.findByClientCodeOrderByActivatedAtDesc(code)).thenReturn(List.of(rows));
    }

    @Test
    void aLicenceWhoseResolvedSetMatchesItsKeyIsNotSavedOrPublished() {
        License l = licence(1, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", true));
        resolvedSays(l, Map.of("accounting", true));
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, null))
                .singleElement().satisfies(o -> assertThat(o.minted()).isFalse());
        verify(licenses, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aChangedSetMintsSavesAndPublishesOnce() {
        License l = licence(2, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", false));
        resolvedSays(l, Map.of("accounting", true));
        when(keys.resignWithFeatures("key-2", Map.of("accounting", true))).thenReturn("new-key-2");
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, null))
                .singleElement().satisfies(o -> assertThat(o.minted()).isTrue());

        assertThat(l.getLicenseKey()).isEqualTo("new-key-2");
        verify(licenses, times(1)).save(l);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new LicenceRemintedEvent(2L, "c-1", "m-2", "new-key-2"));
    }

    /** A flag the key never carried (added to the catalogue later) is a reason to mint. */
    @Test
    void aFlagMissingEntirelyFromTheKeyIsMinted() {
        License l = licence(3, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", true));
        resolvedSays(l, Map.of("accounting", true, "deviceRepair", true));
        when(keys.resignWithFeatures("key-3", Map.of("accounting", true, "deviceRepair", true)))
                .thenReturn("new-key-3");
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, null))
                .singleElement().satisfies(o -> assertThat(o.minted()).isTrue());
    }

    @Test
    void theOldKeyIsStoredAsPreviousLicenseKey() {
        License l = licence(4, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", false));
        resolvedSays(l, Map.of("accounting", true));
        when(keys.resignWithFeatures("key-4", Map.of("accounting", true))).thenReturn("new-key-4");
        clientHas("c-1", l);

        service.remintForClient("c-1", TODAY, null);
        assertThat(l.getPreviousLicenseKey()).isEqualTo("key-4");
    }

    /** A re-mint is not a renewal. */
    @Test
    void renewedAtAndExpiresAtAreUntouchedByARemint() {
        License l = licence(5, "c-1", "ACTIVE", LocalDate.of(2027, 5, 1));
        LocalDateTime renewed = LocalDateTime.of(2026, 1, 1, 10, 0);
        l.setRenewedAt(renewed);
        keySays(l, Map.of("accounting", false));
        resolvedSays(l, Map.of("accounting", true));
        when(keys.resignWithFeatures("key-5", Map.of("accounting", true))).thenReturn("new-key-5");
        clientHas("c-1", l);

        service.remintForClient("c-1", TODAY, null);
        assertThat(l.getRenewedAt()).isEqualTo(renewed);
        assertThat(l.getExpiresAt()).isEqualTo(LocalDate.of(2027, 5, 1));
        assertThat(l.getMachineId()).isEqualTo("m-5");
    }

    @Test
    void aRevokedLicenceIsSkippedWithReasonRevoked() {
        License l = licence(6, "c-1", "REVOKED", null);
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, null)).singleElement().satisfies(o -> {
            assertThat(o.minted()).isFalse();
            assertThat(o.skippedReason()).isEqualTo("revoked");
        });
        verify(licenses, never()).save(any());
    }

    @Test
    void anExpiredLicenceIsSkippedWithReasonExpired() {
        License l = licence(7, "c-1", "ACTIVE", TODAY.minusDays(1));
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, null)).singleElement().satisfies(o -> {
            assertThat(o.minted()).isFalse();
            assertThat(o.skippedReason()).isEqualTo("expired");
        });
        verify(licenses, never()).save(any());
    }

    @Test
    void anOrphanLicenceIsSkippedWithReasonNoClient() {
        License l = licence(8, "gone", "ACTIVE", null);
        keySays(l, Map.of("accounting", true));
        when(configs.licenseEntitlementsFor(l)).thenThrow(new IllegalArgumentException("Client not found: gone"));
        clientHas("gone", l);

        assertThat(service.remintForClient("gone", TODAY, null)).singleElement().satisfies(o -> {
            assertThat(o.minted()).isFalse();
            assertThat(o.skippedReason()).isEqualTo("no client");
        });
        verify(licenses, never()).save(any());
    }

    @Test
    void aKeyThatDoesNotVerifyIsSkippedAndItsStoredKeyIsLeftAlone() {
        License l = licence(9, "c-1", "ACTIVE", null);
        when(keys.verifiedPayload("key-9")).thenReturn(null);
        resolvedSays(l, Map.of("accounting", true));
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, null)).singleElement().satisfies(o -> {
            assertThat(o.minted()).isFalse();
            assertThat(o.skippedReason()).isEqualTo("unverifiable key");
        });
        assertThat(l.getLicenseKey()).isEqualTo("key-9");
        verify(licenses, never()).save(any());
    }

    @Test
    void aFailedResignIsSkippedAndChangesNothing() {
        License l = licence(10, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", false));
        resolvedSays(l, Map.of("accounting", true));
        when(keys.resignWithFeatures("key-10", Map.of("accounting", true))).thenReturn(null);
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, null)).singleElement().satisfies(o -> {
            assertThat(o.minted()).isFalse();
            assertThat(o.skippedReason()).isEqualTo("resign failed");
        });
        assertThat(l.getLicenseKey()).isEqualTo("key-10");
        verify(licenses, never()).save(any());
    }

    /**
     * A re-issue can leave two live rows for one machine+client, which LicenseRepository's own
     * comment warns about. Both must be re-minted, and the throwing Optional finder must not be
     * the way they are found.
     */
    @Test
    void bothLiveLicencesForTheSameMachineAndClientAreReminted() {
        License a = licence(11, "c-14", "ACTIVE", null);
        License b = licence(12, "c-14", "ACTIVE", null);
        b.setMachineId("m-11");                         // same machine as a
        keySays(a, Map.of("consignment", false));
        keySays(b, Map.of("consignment", false));
        resolvedSays(a, Map.of("consignment", true));
        resolvedSays(b, Map.of("consignment", true));
        when(keys.resignWithFeatures("key-11", Map.of("consignment", true))).thenReturn("new-11");
        when(keys.resignWithFeatures("key-12", Map.of("consignment", true))).thenReturn("new-12");
        clientHas("c-14", a, b);

        assertThat(service.remintForClient("c-14", TODAY, null))
                .hasSize(2).allSatisfy(o -> assertThat(o.minted()).isTrue());
        verify(licenses, times(2)).save(any());
        verify(events, times(2)).publishEvent(any(Object.class));
    }

    @Test
    void remintLicenceByIdMintsThatOneRow() {
        License l = licence(13, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", false));
        resolvedSays(l, Map.of("accounting", true));
        when(keys.resignWithFeatures("key-13", Map.of("accounting", true))).thenReturn("new-13");
        when(licenses.findById(13L)).thenReturn(Optional.of(l));

        assertThat(service.remintLicence(13L, TODAY, null).minted()).isTrue();
    }

    @Test
    void remintLicenceOnAnUnknownIdReportsNotFound() {
        when(licenses.findById(99L)).thenReturn(Optional.empty());
        assertThat(service.remintLicence(99L, TODAY, null).skippedReason()).isEqualTo("not found");
    }

    /** The owner-run reconcile mints exactly the rows the scanner reported, and no others. */
    // ── An ordinary save must not strip a drifted flag ──────────────────────
    //
    // Spec §7.1(a) and §13.1: a feature save never silently takes a flag away from a live
    // shop; that happens only through the owner-run reconcile. viora runs
    // multiCurrencyPricing and c-14 runs consignment while their client records say false,
    // so without this guard the first unrelated toggle on those clients would remove a
    // working feature with no preview and no confirmation.

    @Test
    void savingAnUnrelatedFlagDoesNotRemoveADriftedOne() {
        License l = licence(20, "viora", "ACTIVE", null);
        keySays(l, Map.of("multiCurrencyPricing", true, "accounting", false));
        resolvedSays(l, Map.of("multiCurrencyPricing", false, "accounting", true));
        // The save touched `accounting` only.
        when(keys.resignWithFeatures("key-20", Map.of("multiCurrencyPricing", true, "accounting", true)))
                .thenReturn("new-20");
        when(keys.payloadVersion("key-20")).thenReturn(2);
        clientHas("viora", l);

        assertThat(service.remintForClient("viora", TODAY, Set.of("accounting")))
                .singleElement().satisfies(o -> assertThat(o.minted()).isTrue());

        // accounting adopted, multiCurrencyPricing PRESERVED despite the resolved set denying it.
        verify(keys).resignWithFeatures("key-20",
                Map.of("multiCurrencyPricing", true, "accounting", true));
    }

    @Test
    void savingTheDriftedFlagItselfDoesRemoveIt() {
        License l = licence(21, "viora", "ACTIVE", null);
        keySays(l, Map.of("multiCurrencyPricing", true));
        resolvedSays(l, Map.of("multiCurrencyPricing", false));
        when(keys.resignWithFeatures("key-21", Map.of("multiCurrencyPricing", false))).thenReturn("new-21");
        when(keys.payloadVersion("key-21")).thenReturn(2);
        clientHas("viora", l);

        assertThat(service.remintForClient("viora", TODAY, Set.of("multiCurrencyPricing")))
                .singleElement().satisfies(o -> assertThat(o.minted()).isTrue());
        verify(keys).resignWithFeatures("key-21", Map.of("multiCurrencyPricing", false));
    }

    /** Nothing but a protected removal to do: say so rather than reporting "unchanged". */
    @Test
    void aLicenceWithOnlyAProtectedRemovalIsReportedAsDrifting() {
        License l = licence(22, "c-14", "ACTIVE", null);
        keySays(l, Map.of("consignment", true));
        resolvedSays(l, Map.of("consignment", false));
        when(keys.payloadVersion("key-22")).thenReturn(2);
        clientHas("c-14", l);

        assertThat(service.remintForClient("c-14", TODAY, Set.of("accounting")))
                .singleElement().satisfies(o -> {
                    assertThat(o.minted()).isFalse();
                    assertThat(o.skippedReason()).isEqualTo("drifting - reconcile first");
                });
        verify(licenses, never()).save(any());
    }

    /** A flag the key never carried is adopted whether or not the save named it. */
    @Test
    void aNewlyAddedFlagIsAdoptedWithoutBeingNamedBySave() {
        License l = licence(23, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", true));
        resolvedSays(l, Map.of("accounting", true, "deviceRepair", true));
        when(keys.resignWithFeatures("key-23", Map.of("accounting", true, "deviceRepair", true)))
                .thenReturn("new-23");
        when(keys.payloadVersion("key-23")).thenReturn(2);
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, Set.of()))
                .singleElement().satisfies(o -> assertThat(o.minted()).isTrue());
    }

    /** The reconcile is the explicit remover, so nothing is protected from it. */
    @Test
    void reconcileDriftMayRemoveAFlagNoSaveNamed() {
        License l = licence(24, "viora", "ACTIVE", null);
        keySays(l, Map.of("multiCurrencyPricing", true));
        resolvedSays(l, Map.of("multiCurrencyPricing", false));
        when(keys.resignWithFeatures("key-24", Map.of("multiCurrencyPricing", false))).thenReturn("new-24");
        when(keys.payloadVersion("key-24")).thenReturn(2);
        when(licenses.findById(24L)).thenReturn(Optional.of(l));
        when(scanner.scanAll(TODAY)).thenReturn(List.of(new LicenceDriftScanner.DriftRow(
                24L, "viora", "live", "m-24",
                List.of(new LicenceDriftScanner.FlagChange("multiCurrencyPricing", true, false)))));

        assertThat(service.reconcileDrift(TODAY))
                .singleElement().satisfies(o -> assertThat(o.minted()).isTrue());
        verify(keys).resignWithFeatures("key-24", Map.of("multiCurrencyPricing", false));
    }

    // ── A v1 key must be re-issued, not feature-re-minted ───────────────────

    /**
     * resignWithFeatures would happily add a features object to a v1 payload, but the desktop's
     * applyLicenseEntitlements() returns early below v2 while effectiveClientConfig() has no
     * version gate — so the POS UI would light up over backend gates that stay shut: a button
     * that 403s.
     */
    @Test
    void aV1KeyIsSkippedAndPointedAtTheReissuePath() {
        License l = licence(25, "c-1", "ACTIVE", null);
        keySays(l, Map.of("accounting", false));
        resolvedSays(l, Map.of("accounting", true));
        when(keys.payloadVersion("key-25")).thenReturn(1);
        clientHas("c-1", l);

        assertThat(service.remintForClient("c-1", TODAY, null)).singleElement().satisfies(o -> {
            assertThat(o.minted()).isFalse();
            assertThat(o.skippedReason()).isEqualTo("v1 key - re-issue first");
        });
        assertThat(l.getLicenseKey()).isEqualTo("key-25");
        verify(licenses, never()).save(any());
    }

    // ── The v1 rollback key must survive a feature re-mint ──────────────────

    /**
     * LicenseReissueService stores previousLicenseKey only on a genuine v1→v2 transition, so a
     * later revert restores a v1 key rather than another v2 one. A feature re-mint must not
     * clobber that, or "reversible from the DB alone" stops being true.
     */
    @Test
    void aStoredV1PreviousKeyIsNotOverwrittenByAFeatureRemint() {
        License l = licence(26, "c-1", "ACTIVE", null);
        l.setPreviousLicenseKey("v1-key");
        keySays(l, Map.of("accounting", false));
        resolvedSays(l, Map.of("accounting", true));
        when(keys.payloadVersion("key-26")).thenReturn(2);
        when(keys.payloadVersion("v1-key")).thenReturn(1);
        when(keys.resignWithFeatures("key-26", Map.of("accounting", true))).thenReturn("new-26");
        clientHas("c-1", l);

        service.remintForClient("c-1", TODAY, null);

        assertThat(l.getPreviousLicenseKey()).isEqualTo("v1-key");
        assertThat(l.getLicenseKey()).isEqualTo("new-26");
    }

    @Test
    void aStoredV2PreviousKeyIsReplacedAsUsual() {
        License l = licence(27, "c-1", "ACTIVE", null);
        l.setPreviousLicenseKey("older-v2-key");
        keySays(l, Map.of("accounting", false));
        resolvedSays(l, Map.of("accounting", true));
        when(keys.payloadVersion("key-27")).thenReturn(2);
        when(keys.payloadVersion("older-v2-key")).thenReturn(2);
        when(keys.resignWithFeatures("key-27", Map.of("accounting", true))).thenReturn("new-27");
        clientHas("c-1", l);

        service.remintForClient("c-1", TODAY, null);

        assertThat(l.getPreviousLicenseKey()).isEqualTo("key-27");
    }

    // ── The push listener must not run before the row is committed ──────────

    @Test
    void theRemintedEventIsPublishedAfterCommit() throws Exception {
        var annotation = HostedLicencePushListener.class
                .getMethod("onReminted", com.daman.admin.event.LicenceRemintedEvent.class)
                .getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class);
        assertThat(annotation).as("a blocking HTTP push must not run inside the mint transaction")
                .isNotNull();
        assertThat(annotation.phase())
                .isEqualTo(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void reconcileDriftMintsExactlyTheRowsTheScannerReported() {
        License drifting = licence(14, "viora", "ACTIVE", null);
        keySays(drifting, Map.of("multiCurrencyPricing", true));
        resolvedSays(drifting, Map.of("multiCurrencyPricing", false));
        when(keys.resignWithFeatures("key-14", Map.of("multiCurrencyPricing", false))).thenReturn("new-14");
        when(licenses.findById(14L)).thenReturn(Optional.of(drifting));
        when(scanner.scanAll(TODAY)).thenReturn(List.of(new LicenceDriftScanner.DriftRow(
                14L, "viora", "live", "m-14",
                List.of(new LicenceDriftScanner.FlagChange("multiCurrencyPricing", true, false)))));

        assertThat(service.reconcileDrift(TODAY))
                .singleElement().satisfies(o -> assertThat(o.minted()).isTrue());
        verify(licenses, times(1)).save(drifting);
    }
}
