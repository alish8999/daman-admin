package com.daman.admin.controller;

import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.repository.TrialRegistrationRepository;
import com.daman.admin.service.ClientConfigService;
import com.daman.admin.service.LicenseBundleZipper;
import com.daman.admin.service.LicenseKeyService;
import com.daman.admin.service.LicenseProfile;
import com.daman.admin.service.LicenseReissueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reading one licence's effective feature set.
 *
 * <p><b>Phase 1 left no way to do this.</b> It returned {@code effectiveFeatures} /
 * {@code featureOverride} only as the RESPONSE TO A WRITE
 * ({@code PUT /api/licenses/{id}/features}), so the portal's licence drawer had nothing to open
 * with — and discovering an override by clearing it is obviously not an option.
 *
 * <p>So the per-client listing carries them. The whole-fleet listing deliberately does not: that
 * is the endpoint whose per-row resolution would be a client lookup per licence on a page that
 * renders all 105 of them, which is why the maps were made optional in the first place. One
 * client has a handful.
 */
class LicenseControllerFeaturesByClientTest {

    LicenseKeyService keys;
    LicenseRepository licenses;
    ClientConfigRepository clientConfigs;
    ClientConfigService configService;
    LicenseController controller;

    @BeforeEach
    void setUp() {
        keys = mock(LicenseKeyService.class);
        licenses = mock(LicenseRepository.class);
        clientConfigs = mock(ClientConfigRepository.class);
        configService = mock(ClientConfigService.class);
        controller = new LicenseController(keys, licenses, clientConfigs, configService,
                mock(LicenseReissueService.class), mock(LicenseBundleZipper.class),
                mock(TrialRegistrationRepository.class));
        when(keys.payloadVersion(any())).thenReturn(2);
    }

    private License licence(long id, String code, String overrideJson) {
        License l = new License();
        l.setId(id);
        l.setClientCode(code);
        l.setMachineId("m-" + id);
        l.setStatus("ACTIVE");
        l.setLicenseKey("key-" + id);
        l.setFeaturesOverrideJson(overrideJson);
        return l;
    }

    private void resolves(License l, Map<String, Boolean> effective) {
        when(configService.licenseEntitlementsFor(l)).thenReturn(
                new ClientConfigService.LicenseEntitlements("SYP", effective, null, null, null, null,
                        new LicenseProfile("Shop", null, null, null, null)));
    }

    @Test
    void getByClientPopulatesEffectiveFeaturesAndOverride() {
        License l = licence(1, "c-1", null);
        resolves(l, Map.of("accounting", true, "shifts", false));
        when(licenses.findByClientCodeOrderByActivatedAtDesc("c-1")).thenReturn(List.of(l));

        var dto = controller.getByClient("c-1").get(0);

        assertThat(dto.effectiveFeatures()).containsEntry("accounting", true).containsEntry("shifts", false);
        assertThat(dto.featureOverride()).isEmpty();
    }

    @Test
    void getByClientReportsAnOverriddenFlagInBothMaps() {
        License l = licence(2, "c-1", "{\"accounting\":false}");
        resolves(l, Map.of("accounting", false, "shifts", false));
        when(licenses.findByClientCodeOrderByActivatedAtDesc("c-1")).thenReturn(List.of(l));

        var dto = controller.getByClient("c-1").get(0);

        assertThat(dto.effectiveFeatures()).containsEntry("accounting", false);
        assertThat(dto.featureOverride()).containsEntry("accounting", false);
    }

    /** The cost guard: the fleet listing must not resolve entitlements once per row. */
    @Test
    void getAllStillReturnsNullMapsForTheWholeFleet() {
        License l = licence(3, "c-1", "{\"accounting\":false}");
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));

        var dto = controller.getAll().get(0);

        assertThat(dto.effectiveFeatures()).isNull();
        assertThat(dto.featureOverride()).isNull();
    }

    /** Six orphan licences exist in production; one must not fail the whole listing. */
    @Test
    void anOrphanLicenceStillListsWithNullMapsRatherThanThrowing() {
        License orphan = licence(4, "gone", null);
        License ok = licence(5, "gone", null);
        when(configService.licenseEntitlementsFor(orphan))
                .thenThrow(new IllegalArgumentException("Client not found: gone"));
        resolves(ok, Map.of("accounting", true));
        when(licenses.findByClientCodeOrderByActivatedAtDesc("gone")).thenReturn(List.of(orphan, ok));

        var dtos = controller.getByClient("gone");

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).effectiveFeatures()).isNull();
        assertThat(dtos.get(1).effectiveFeatures()).containsEntry("accounting", true);
    }
}
