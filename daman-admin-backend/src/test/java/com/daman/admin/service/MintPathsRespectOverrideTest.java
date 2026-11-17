package com.daman.admin.service;

import com.daman.admin.controller.LicenseController;
import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.HostedInstance;
import com.daman.admin.entity.License;
import com.daman.admin.repository.AppSettingRepository;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.repository.TrialRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every path that mints a licence key must resolve THAT LICENCE's feature override, not just its
 * client's defaults.
 *
 * <p>The one that actually mattered was hosted-store provisioning. Its {@code licence()} reads
 * {@code findByMachineIdAndClientCodeAndStatus(...).orElseGet(License::new)}, so for an existing
 * store the row — and therefore its override — IS in hand; minting from the client default there
 * would quietly re-provision a customised store with a key that ignores its own customisation.
 * The design doc had this one wrong until the code was read.
 */
class MintPathsRespectOverrideTest {

    ClientConfigRepository clientConfigs;
    LicenseRepository licenses;
    LicenseKeyService keys;
    ClientConfigService configService;

    static final Map<String, Boolean> CLIENT_DEFAULT = Map.of("accounting", true, "consignment", true);
    static final Map<String, Boolean> OVERRIDDEN = Map.of("accounting", true, "consignment", false);

    @BeforeEach
    void setUp() {
        clientConfigs = mock(ClientConfigRepository.class);
        licenses = mock(LicenseRepository.class);
        keys = mock(LicenseKeyService.class);
        configService = mock(ClientConfigService.class);

        ClientConfig cfg = new ClientConfig();
        cfg.setClientCode("c-1");
        cfg.setAppName("Shop");
        cfg.setBaseCurrency("SYP");
        when(clientConfigs.findByClientCode("c-1")).thenReturn(Optional.of(cfg));

        // The client default, and what a licence carrying an override resolves to.
        when(configService.licenseEntitlementsFor("c-1")).thenReturn(entitlements(CLIENT_DEFAULT));
        when(configService.licenseEntitlementsFor(any(License.class))).thenAnswer(inv -> {
            License l = inv.getArgument(0);
            return entitlements(l.getFeaturesOverrideJson() == null ? CLIENT_DEFAULT : OVERRIDDEN);
        });
        when(keys.generateLicense(anyString(), anyString(), anyString(), any(), anyString(), any(),
                any(), any(), any(), any(), any())).thenReturn("minted-key");
    }

    private static ClientConfigService.LicenseEntitlements entitlements(Map<String, Boolean> features) {
        return new ClientConfigService.LicenseEntitlements("SYP", features, null, null, null, null,
                new LicenseProfile("Shop", null, null, null, null));
    }

    private License overriddenLicence(long id, String machineId) {
        License l = new License();
        l.setId(id);
        l.setClientCode("c-1");
        l.setMachineId(machineId);
        l.setStatus("ACTIVE");
        l.setLicenseKey("old-key");
        l.setClientName("Shop");
        l.setFeaturesOverrideJson("{\"consignment\":false}");
        return l;
    }

    // ── LicenseController ────────────────────────────────────────────────────

    private LicenseController licenseController() {
        return new LicenseController(keys, licenses, clientConfigs, configService,
                mock(LicenseReissueService.class), mock(LicenseBundleZipper.class),
                mock(TrialRegistrationRepository.class));
    }

    @Test
    void renewKeepsTheLicencesOwnOverride() {
        License l = overriddenLicence(1, "m-1");
        when(licenses.findById(1L)).thenReturn(Optional.of(l));

        licenseController().renewById(1L, Map.of("expiresAt", "2027-01-01"));

        verify(keys).generateLicense(anyString(), anyString(), anyString(), any(), anyString(),
                org.mockito.ArgumentMatchers.eq(OVERRIDDEN), any(), any(), any(), any(), any());
    }

    @Test
    void generateUsesTheClientDefaultForABrandNewLicence() {
        when(licenses.findByMachineIdAndClientCodeAndStatus("m-new", "c-1", "ACTIVE"))
                .thenReturn(Optional.empty());

        licenseController().generate(Map.of("machineId", "m-new", "clientCode", "c-1"));

        verify(keys).generateLicense(anyString(), anyString(), anyString(), any(), anyString(),
                org.mockito.ArgumentMatchers.eq(CLIENT_DEFAULT), any(), any(), any(), any(), any());
    }

    // ── LicenseReissueService ───────────────────────────────────────────────

    @Test
    void reissueAllRespectsEachRowsOverride() {
        License l = overriddenLicence(2, "m-2");
        when(licenses.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));
        when(keys.payloadVersion("old-key")).thenReturn(2);

        new LicenseReissueService(licenses, clientConfigs, configService, keys).reissueAll();

        verify(keys).generateLicense(anyString(), anyString(), anyString(), any(), anyString(),
                org.mockito.ArgumentMatchers.eq(OVERRIDDEN), any(), any(), any(), any(), any());
    }

    // ── DevRunService ───────────────────────────────────────────────────────

    @Test
    void devRunResolvesTheExistingDevRowsOverride() {
        License existing = overriddenLicence(3, "m-dev");
        when(licenses.findByMachineIdAndClientCodeAndStatus("m-dev", "c-1", "ACTIVE"))
                .thenReturn(Optional.of(existing));
        var devRun = new DevRunService(configService, keys, licenses, clientConfigs,
                mock(AppSettingRepository.class));

        License minted = devRun.mintDevLicence("c-1", "m-dev", "Shop");

        assertThat(minted.getLicenseKey()).isEqualTo("minted-key");
        verify(keys).generateLicense(anyString(), anyString(), anyString(), any(), anyString(),
                org.mockito.ArgumentMatchers.eq(OVERRIDDEN), any(), any(), any(), any(), any());
    }

    @Test
    void devRunFallsBackToTheClientDefaultForAFirstRun() {
        when(licenses.findByMachineIdAndClientCodeAndStatus("m-dev", "c-1", "ACTIVE"))
                .thenReturn(Optional.empty());
        var devRun = new DevRunService(configService, keys, licenses, clientConfigs,
                mock(AppSettingRepository.class));

        devRun.mintDevLicence("c-1", "m-dev", "Shop");

        verify(keys).generateLicense(anyString(), anyString(), anyString(), any(), anyString(),
                org.mockito.ArgumentMatchers.eq(CLIENT_DEFAULT), any(), any(), any(), any(), any());
    }

    // ── HostedStoreProvisioningService ──────────────────────────────────────

    private HostedStoreProvisioningService hosted(ServerOps ops) {
        return new HostedStoreProvisioningService(mock(HostedInstanceRepository.class), clientConfigs,
                configService, keys, licenses, mock(CloudflareClient.class), ops);
    }

    private HostedInstance instance() {
        HostedInstance i = new HostedInstance();
        i.setClientCode("c-1");
        i.setPort(9101);
        return i;
    }

    @Test
    void hostedProvisioningResolvesAnExistingStoresOverride() {
        ServerOps ops = mock(ServerOps.class);
        when(ops.probeMachineId(9101)).thenReturn(Optional.of("m-host"));
        when(licenses.findByMachineIdAndClientCodeAndStatus("m-host", "c-1", "ACTIVE"))
                .thenReturn(Optional.of(overriddenLicence(4, "m-host")));

        hosted(ops).licence(instance(), LocalDate.of(2027, 1, 1));

        verify(keys).generateLicense(anyString(), anyString(), anyString(), any(), anyString(),
                org.mockito.ArgumentMatchers.eq(OVERRIDDEN), any(), any(), any(), any(), any());
    }

    @Test
    void hostedProvisioningFallsBackToClientDefaultForAFirstProvision() {
        ServerOps ops = mock(ServerOps.class);
        when(ops.probeMachineId(9101)).thenReturn(Optional.of("m-host"));
        when(licenses.findByMachineIdAndClientCodeAndStatus("m-host", "c-1", "ACTIVE"))
                .thenReturn(Optional.empty());

        hosted(ops).licence(instance(), LocalDate.of(2027, 1, 1));

        verify(keys).generateLicense(anyString(), anyString(), anyString(), any(), anyString(),
                org.mockito.ArgumentMatchers.eq(CLIENT_DEFAULT), any(), any(), any(), any(), any());
    }
}
