package com.daman.admin.service;

import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LicenseReissueServiceTest {

    LicenseRepository licenseRepository;
    ClientConfigRepository clientConfigRepository;
    ClientConfigService clientConfigService;
    LicenseKeyService licenseKeyService;
    LicenseReissueService service;

    @BeforeEach
    void setUp() {
        licenseRepository = mock(LicenseRepository.class);
        clientConfigRepository = mock(ClientConfigRepository.class);
        clientConfigService = mock(ClientConfigService.class);
        // Real key service — signs/decodes for real; init() reads/creates ~/.daman/keys.
        licenseKeyService = new LicenseKeyService();
        licenseKeyService.init();
        service = new LicenseReissueService(
                licenseRepository, clientConfigRepository, clientConfigService, licenseKeyService);

        // Default: every clientCode resolves to some entitlements unless a test overrides.
        when(clientConfigService.licenseEntitlementsFor(anyString()))
                .thenReturn(new ClientConfigService.LicenseEntitlements("USD", Map.of("barcode", true)));
    }

    private License lic(long id, String code, String machine, String status, String key) {
        License l = new License();
        l.setId(id);
        l.setClientCode(code);
        l.setClientName(code + " Store");
        l.setMachineId(machine);
        l.setStatus(status);
        l.setLicenseKey(key);
        l.setExpiresAt(LocalDate.parse("2027-01-01"));
        return l;
    }

    private ClientConfig cfg(String code) {
        ClientConfig c = new ClientConfig();
        c.setClientCode(code);
        c.setAppName(code + " POS");
        c.setBaseCurrency("SYP");
        return c;
    }

    @Test
    void reissueAll_regeneratesV2InPlace_onlyForActiveWithConfig_bumpsRenewedAt() {
        License active = lic(1, "acme", "M-AAAA", "ACTIVE", "old.key");
        License revoked = lic(2, "beta", "M-BBBB", "REVOKED", "old.key");
        when(licenseRepository.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(active, revoked));
        when(clientConfigRepository.findByClientCode("acme")).thenReturn(Optional.of(cfg("acme")));
        when(clientConfigRepository.findByClientCode("beta")).thenReturn(Optional.of(cfg("beta")));

        LicenseReissueService.ReissueResult r = service.reissueAll();

        assertThat(r.reissued()).hasSize(1);
        assertThat(r.reissued().get(0).clientCode()).isEqualTo("acme");
        assertThat(r.reissued().get(0).datFileName()).isEqualTo("acme.dat");
        assertThat(r.reissued().get(0).previousKey()).isEqualTo("old.key");
        // The new key is a real v2 key
        assertThat(licenseKeyService.payloadVersion(r.reissued().get(0).newKey())).isEqualTo(2);
        // Written back onto the SAME entity, renewedAt set
        assertThat(active.getLicenseKey()).isEqualTo(r.reissued().get(0).newKey());
        assertThat(active.getRenewedAt()).isNotNull();
        verify(licenseRepository, times(1)).save(active);
        verify(licenseRepository, never()).save(revoked);
    }

    @Test
    void reissueAll_missingClientConfig_isSkippedNotThrown() {
        License orphan = lic(1, "ghost", "M-GHOST", "ACTIVE", "old.key");
        when(licenseRepository.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(orphan));
        when(clientConfigRepository.findByClientCode("ghost")).thenReturn(Optional.empty());

        LicenseReissueService.ReissueResult r = service.reissueAll();

        assertThat(r.reissued()).isEmpty();
        assertThat(r.skipped()).extracting(LicenseReissueService.ReissuePreviewRow::clientCode)
                .containsExactly("ghost");
        verify(licenseRepository, never()).save(any());
    }

    @Test
    void reissueAll_multiMachineClient_disambiguatesDatFilenames() {
        License a = lic(1, "acme", "MACHINE-ONE-1234", "ACTIVE", "old.a");
        License b = lic(2, "acme", "MACHINE-TWO-5678", "ACTIVE", "old.b");
        when(licenseRepository.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(a, b));
        when(clientConfigRepository.findByClientCode("acme")).thenReturn(Optional.of(cfg("acme")));

        LicenseReissueService.ReissueResult r = service.reissueAll();

        assertThat(r.reissued()).extracting(LicenseReissueService.ReissuedLicence::datFileName)
                .containsExactlyInAnyOrder("acme__MACHINEON.dat", "acme__MACHINETW.dat");
    }

    @Test
    void reissueAll_isIdempotent_secondRunAddsNoRowsAndStaysV2() {
        License active = lic(1, "acme", "M-AAAA", "ACTIVE", "old.key");
        when(licenseRepository.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(active));
        when(clientConfigRepository.findByClientCode("acme")).thenReturn(Optional.of(cfg("acme")));

        LicenseReissueService.ReissueResult first = service.reissueAll();
        LicenseReissueService.ReissueResult second = service.reissueAll();

        assertThat(first.reissued()).hasSize(1);
        assertThat(second.reissued()).hasSize(1);
        assertThat(licenseKeyService.payloadVersion(second.reissued().get(0).newKey())).isEqualTo(2);
        assertThat(second.reissued().get(0).fromVersion()).isEqualTo(2); // first run already made it v2
        // save() only ever called with the existing entity, never a brand-new row
        verify(licenseRepository, times(2)).save(active);
    }

    @Test
    void preview_splitsEligibleFromOrphans_andReadsCurrentVersion() {
        String v2Key = licenseKeyService.generateLicense(
                "M-AAAA", "Acme", "acme", "2027-01-01", "USD", Map.of("barcode", true));
        License migrated = lic(1, "acme", "M-AAAA", "ACTIVE", v2Key);
        License notYet = lic(2, "beta", "M-BBBB", "ACTIVE", "legacy.v1.key");
        License orphan = lic(3, "ghost", "M-GHOST", "ACTIVE", "legacy.v1.key");
        License revoked = lic(4, "old", "M-OLD", "REVOKED", "legacy.v1.key");
        when(licenseRepository.findAllByOrderByActivatedAtDesc())
                .thenReturn(List.of(migrated, notYet, orphan, revoked));
        when(clientConfigRepository.findByClientCode("acme")).thenReturn(Optional.of(cfg("acme")));
        when(clientConfigRepository.findByClientCode("beta")).thenReturn(Optional.of(cfg("beta")));
        when(clientConfigRepository.findByClientCode("ghost")).thenReturn(Optional.empty());

        LicenseReissueService.ReissuePreview p = service.preview();

        assertThat(p.eligible()).extracting(LicenseReissueService.ReissuePreviewRow::clientCode)
                .containsExactlyInAnyOrder("acme", "beta");
        assertThat(p.skipped()).extracting(LicenseReissueService.ReissuePreviewRow::clientCode)
                .containsExactly("ghost");
        assertThat(p.eligible()).filteredOn(r -> r.clientCode().equals("acme"))
                .allMatch(r -> r.currentVersion() == 2);
        assertThat(p.eligible()).filteredOn(r -> r.clientCode().equals("beta"))
                .allMatch(r -> r.currentVersion() == 1);
    }
}
