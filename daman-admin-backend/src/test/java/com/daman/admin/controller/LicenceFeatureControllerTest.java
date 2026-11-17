package com.daman.admin.controller;

import com.daman.admin.entity.License;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.service.ClientConfigService;
import com.daman.admin.service.LicenceDriftScanner;
import com.daman.admin.service.LicenceRemintService;
import com.daman.admin.service.LicenseProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-licence override endpoint and the drift preview / reconcile pair.
 *
 * <p>The override is a MERGE PATCH: a key with {@code true}/{@code false} starts or changes an
 * override, a key with {@code null} stops overriding it, and an empty body clears the whole
 * thing. That matters because the column's null state is what "inherit everything" means, and it
 * has to be reachable from the UI rather than only from SQL.
 */
class LicenceFeatureControllerTest {

    LicenseRepository licenses;
    ClientConfigService configs;
    LicenceDriftScanner scanner;
    LicenceRemintService remint;
    LicenceFeatureController controller;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        licenses = mock(LicenseRepository.class);
        configs = mock(ClientConfigService.class);
        scanner = mock(LicenceDriftScanner.class);
        remint = mock(LicenceRemintService.class);
        controller = new LicenceFeatureController(licenses, configs, scanner, remint, mapper);

        when(configs.knownFeatureKeys()).thenReturn(Set.of("accounting", "shifts", "consignment", "deviceRepair"));
    }

    private License licence(long id, String overrideJson) {
        License l = new License();
        l.setId(id);
        l.setClientCode("c-1");
        l.setMachineId("m-" + id);
        l.setStatus("ACTIVE");
        l.setFeaturesOverrideJson(overrideJson);
        when(licenses.findById(id)).thenReturn(Optional.of(l));
        when(configs.licenseEntitlementsFor(l)).thenReturn(new ClientConfigService.LicenseEntitlements(
                "SYP", Map.of("accounting", true, "shifts", false), null, null, null, null,
                new LicenseProfile("Shop", null, null, null, null)));
        return l;
    }

    private void remintSucceeds(long id) {
        // The controller passes the patch's keys as the removable set, so that only the flags this
        // save named can be switched off in the key.
        when(remint.remintLicence(anyLong(), org.mockito.ArgumentMatchers.<java.util.Set<String>>any()))
                .thenReturn(new LicenceRemintService.RemintOutcome(id, "c-1", "m-" + id, true, null));
    }

    @Test
    void putFeaturesOnAnUnknownIdIs404() {
        when(licenses.findById(99L)).thenReturn(Optional.empty());
        assertThat(controller.setFeatures(99L, Map.of("accounting", true)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTrueValueIsStoredAsAnOverrideAndTheLicenceIsReminted() {
        License l = licence(1, null);
        remintSucceeds(1);

        var response = controller.setFeatures(1L, Map.of("consignment", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(l.getFeaturesOverrideJson()).isEqualTo("{\"consignment\":true}");
        verify(licenses).save(l);
        verify(remint).remintLicence(1L, java.util.Set.of("consignment"));
    }

    @Test
    void aFalseValueIsAlsoAnOverride() {
        License l = licence(2, null);
        remintSucceeds(2);
        controller.setFeatures(2L, Map.of("accounting", false));
        assertThat(l.getFeaturesOverrideJson()).isEqualTo("{\"accounting\":false}");
    }

    @Test
    void aNullValueRemovesThatKeyFromTheOverride() {
        License l = licence(3, "{\"accounting\":false,\"shifts\":true}");
        remintSucceeds(3);

        Map<String, Boolean> body = new HashMap<>();
        body.put("accounting", null);
        controller.setFeatures(3L, body);

        assertThat(l.getFeaturesOverrideJson()).isEqualTo("{\"shifts\":true}");
    }

    /** Removing the last overridden key must clear the column, not leave "{}" behind. */
    @Test
    void removingTheLastKeyClearsTheColumnToNull() {
        License l = licence(4, "{\"accounting\":false}");
        remintSucceeds(4);

        Map<String, Boolean> body = new HashMap<>();
        body.put("accounting", null);
        controller.setFeatures(4L, body);

        assertThat(l.getFeaturesOverrideJson()).isNull();
    }

    @Test
    void anEmptyBodyClearsTheOverrideColumnToNull() {
        License l = licence(5, "{\"accounting\":false,\"shifts\":true}");
        remintSucceeds(5);

        controller.setFeatures(5L, Map.of());

        assertThat(l.getFeaturesOverrideJson()).isNull();
        verify(remint).remintLicence(5L, java.util.Set.of());
    }

    @Test
    void theResponseCarriesTheEffectiveSetSoTheDrawerNeedNotRecompute() {
        licence(6, null);
        remintSucceeds(6);

        var body = controller.setFeatures(6L, Map.of("consignment", true)).getBody();

        assertThat(body).isInstanceOf(LicenceFeatureController.FeatureUpdateResponse.class);
        var response = (LicenceFeatureController.FeatureUpdateResponse) body;
        assertThat(response.effectiveFeatures()).containsEntry("accounting", true);
        assertThat(response.featureOverride()).containsEntry("consignment", true);
        assertThat(response.reminted()).isTrue();
    }

    /** An unknown key is a client bug or a stale UI; storing it silently would be worse. */
    @Test
    void anUnknownFlagKeyInTheBodyIsRejectedWith400() {
        licence(7, null);

        var response = controller.setFeatures(7L, Map.of("renamedFlag", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(licenses, never()).save(any());
        verify(remint, never()).remintLicence(anyLong(), org.mockito.ArgumentMatchers.<java.util.Set<String>>any());
    }

    @Test
    void driftPreviewDelegatesToTheScannerAndMintsNothing() {
        var row = new LicenceDriftScanner.DriftRow(1L, "viora", "live", "m-1",
                List.of(new LicenceDriftScanner.FlagChange("multiCurrencyPricing", true, false)));
        when(scanner.scanAll()).thenReturn(List.of(row));

        assertThat(controller.featuresDrift()).containsExactly(row);
        verify(remint, never()).reconcileDrift();
    }

    @Test
    void reconcileDelegatesToRemintServiceReconcileDrift() {
        var outcome = new LicenceRemintService.RemintOutcome(1L, "viora", "m-1", true, null);
        when(remint.reconcileDrift()).thenReturn(List.of(outcome));

        assertThat(controller.reconcileFeaturesDrift()).containsExactly(outcome);
    }

    /**
     * The response must carry the licence's CURRENT key, because the re-mint replaced it.
     *
     * <p>Without it the drawer keeps the key it was opened with: it merges only the two feature
     * maps out of this response, so `licence.licenseKey` stays at the pre-toggle value. The
     * owner then turns a feature on for one machine and presses "Download .dat" to email the
     * shop — which is the normal flow for a till that is not online — and the file is the
     * SUPERSEDED key. It verifies and installs, so nothing looks wrong, but it does not grant
     * the feature just paid for, and the admin database now holds a different key than the shop.
     */
    @Test
    void theResponseCarriesTheRemintedKeySoADownloadIsNotStale() {
        License l = licence(9, null);
        l.setLicenseKey("OLD-KEY");
        when(remint.remintLicence(anyLong(), org.mockito.ArgumentMatchers.<java.util.Set<String>>any()))
                .thenAnswer(inv -> {
                    l.setLicenseKey("NEW-KEY");
                    return new LicenceRemintService.RemintOutcome(9L, "c-1", "m-9", true, null);
                });

        var response = (LicenceFeatureController.FeatureUpdateResponse)
                controller.setFeatures(9L, Map.of("consignment", true)).getBody();

        assertThat(response.licenseKey()).isEqualTo("NEW-KEY");
    }

    /** A skipped re-mint leaves the key alone, and the response says so rather than inventing one. */
    @Test
    void aSkippedRemintReturnsTheUnchangedKey() {
        License l = licence(10, null);
        l.setLicenseKey("OLD-KEY");
        when(remint.remintLicence(anyLong(), org.mockito.ArgumentMatchers.<java.util.Set<String>>any()))
                .thenReturn(new LicenceRemintService.RemintOutcome(10L, "c-1", "m-10", false, "expired"));

        var response = (LicenceFeatureController.FeatureUpdateResponse)
                controller.setFeatures(10L, Map.of("consignment", true)).getBody();

        assertThat(response.reminted()).isFalse();
        assertThat(response.licenseKey()).isEqualTo("OLD-KEY");
    }
}
