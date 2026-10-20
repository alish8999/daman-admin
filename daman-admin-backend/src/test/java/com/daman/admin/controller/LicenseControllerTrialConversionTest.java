package com.daman.admin.controller;

import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.TrialRegistration;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.repository.TrialRegistrationRepository;
import com.daman.admin.service.ClientConfigService;
import com.daman.admin.service.LicenseBundleZipper;
import com.daman.admin.service.LicenseKeyService;
import com.daman.admin.service.LicenseReissueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Minting a normal paid licence for a trial's clientCode is what "converted" means. EVERY existing
 * client goes through this same endpoint, so the non-trial and failure cases matter most.
 */
class LicenseControllerTrialConversionTest {

    LicenseKeyService keys = mock(LicenseKeyService.class);
    LicenseRepository licenses = mock(LicenseRepository.class);
    ClientConfigRepository clients = mock(ClientConfigRepository.class);
    ClientConfigService clientConfigService = mock(ClientConfigService.class);
    TrialRegistrationRepository trials = mock(TrialRegistrationRepository.class);
    LicenseController controller;

    @BeforeEach
    void setUp() {
        ClientConfig cc = new ClientConfig();
        cc.setAppName("Shop");
        when(clients.findByClientCode(anyString())).thenReturn(Optional.of(cc));
        when(clientConfigService.licenseEntitlementsFor(anyString())).thenReturn(
                new ClientConfigService.LicenseEntitlements("USD", Map.of(), null, null, null, null, null));
        when(keys.generateLicense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("KEY");
        when(licenseRepository().findByMachineIdAndClientCodeAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        controller = new LicenseController(keys, licenses, clients, clientConfigService,
                mock(LicenseReissueService.class), mock(LicenseBundleZipper.class), trials);
    }

    private LicenseRepository licenseRepository() { return licenses; }

    private static TrialRegistration trial(LocalDateTime converted) {
        TrialRegistration t = new TrialRegistration();
        t.setClientCode("trial-1");
        t.setConvertedAt(converted);
        return t;
    }

    private static Map<String, String> body(String code) {
        return Map.of("machineId", "M1", "clientCode", code);
    }

    @Test
    void issuingAPaidLicenceStampsConvertedAt() {
        TrialRegistration t = trial(null);
        when(trials.findAllByClientCode("trial-1")).thenReturn(List.of(t));

        controller.generate(body("trial-1"));

        assertThat(t.getConvertedAt()).isNotNull();
        verify(trials).save(t);
    }

    @Test
    void anAlreadyStampedConversionIsNotOverwritten() {
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 0, 0);
        TrialRegistration t = trial(first);
        when(trials.findAllByClientCode("trial-1")).thenReturn(List.of(t));

        controller.generate(body("trial-1"));

        assertThat(t.getConvertedAt()).isEqualTo(first);
        verify(trials, never()).save(any());
    }

    @Test
    void issuingALicenceForANonTrialClientChangesNothing() {
        when(trials.findAllByClientCode("daman-1")).thenReturn(List.of());

        var resp = controller.generate(body("daman-1"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(trials, never()).save(any());
        verify(licenses).save(any());
    }

    @Test
    void aFailureInTheConversionStampNeverBreaksLicenceCreation() {
        when(trials.findAllByClientCode(anyString())).thenThrow(new RuntimeException("db down"));

        var resp = controller.generate(body("daman-1"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(licenses).save(any());
    }

    private TrialRegistration sevenDayTrial() {
        TrialRegistration t = trial(null);
        t.setRegisteredAt(LocalDateTime.of(2026, 10, 10, 12, 0));
        t.setExpiresAt(LocalDateTime.of(2026, 10, 17, 0, 0));
        return t;
    }

    private void renew(TrialRegistration t, String expiresAt) {
        com.daman.admin.entity.License l = new com.daman.admin.entity.License();
        l.setClientCode("trial-1");
        l.setMachineId("M1");
        l.setClientName("Shop");
        l.setStatus("ACTIVE");
        when(licenses.findById(1L)).thenReturn(Optional.of(l));
        when(trials.findAllByClientCode("trial-1")).thenReturn(List.of(t));
        var resp = controller.renewById(1L, expiresAt == null ? new java.util.HashMap<>() : Map.of("expiresAt", expiresAt));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aMidTrialExtensionDoesNotCountAsConversion() {
        TrialRegistration t = sevenDayTrial();
        renew(t, "2026-10-22"); // +5 days: goodwill
        assertThat(t.getConvertedAt()).isNull();
        verify(trials, never()).save(any());
    }

    @Test
    void renewingFarBeyondTheTrialWindowIsAConversion() {
        TrialRegistration t = sevenDayTrial();
        renew(t, "2027-10-17");
        assertThat(t.getConvertedAt()).isNotNull();
    }

    @Test
    void renewingToPerpetualIsAConversion() {
        TrialRegistration t = sevenDayTrial();
        renew(t, null);
        assertThat(t.getConvertedAt()).isNotNull();
    }

    @Test
    void renewForANonTrialClientStillSucceedsAndStampsNothing() {
        com.daman.admin.entity.License l = new com.daman.admin.entity.License();
        l.setClientCode("daman-1");
        l.setMachineId("M1");
        l.setClientName("X");
        when(licenses.findById(2L)).thenReturn(Optional.of(l));
        when(trials.findAllByClientCode("daman-1")).thenReturn(List.of());
        assertThat(controller.renewById(2L, Map.of("expiresAt", "2027-01-01")).getStatusCode().value()).isEqualTo(200);
        verify(trials, never()).save(any());
    }

    @Test
    void aFailureStampingOnRenewNeverBreaksTheRenewal() {
        when(trials.findAllByClientCode(anyString())).thenThrow(new RuntimeException("db down"));
        com.daman.admin.entity.License l = new com.daman.admin.entity.License();
        l.setClientCode("daman-1");
        l.setMachineId("M1");
        l.setClientName("X");
        when(licenses.findById(3L)).thenReturn(Optional.of(l));
        assertThat(controller.renewById(3L, Map.of("expiresAt", "2027-01-01")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void bothConversionPathsPassTheEntitlementsProfileToTheMint() {
        var profile = new com.daman.admin.service.LicenseProfile("Shop", "0999", "Main St", "cafe", true);
        when(clientConfigService.licenseEntitlementsFor(anyString())).thenReturn(
                new ClientConfigService.LicenseEntitlements("USD", Map.of(), null, null, null, null, profile));
        when(trials.findAllByClientCode(anyString())).thenReturn(List.of());

        controller.generate(body("trial-1"));

        com.daman.admin.entity.License l = new com.daman.admin.entity.License();
        l.setClientCode("trial-1");
        l.setMachineId("M1");
        l.setClientName("Shop");
        when(licenses.findById(9L)).thenReturn(Optional.of(l));
        controller.renewById(9L, Map.of("expiresAt", "2027-01-01"));

        org.mockito.ArgumentCaptor<com.daman.admin.service.LicenseProfile> cap =
                org.mockito.ArgumentCaptor.forClass(com.daman.admin.service.LicenseProfile.class);
        verify(keys, times(2)).generateLicense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                cap.capture());
        assertThat(cap.getAllValues()).containsExactly(profile, profile);
    }
}
