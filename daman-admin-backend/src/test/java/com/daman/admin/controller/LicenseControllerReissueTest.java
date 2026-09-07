package com.daman.admin.controller;

import com.daman.admin.dto.LicenseDto;
import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.service.ClientConfigService;
import com.daman.admin.service.LicenseBundleZipper;
import com.daman.admin.service.LicenseKeyService;
import com.daman.admin.service.LicenseReissueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LicenseControllerReissueTest {

    LicenseKeyService licenseKeyService;
    LicenseRepository licenseRepository;
    ClientConfigRepository clientConfigRepository;
    ClientConfigService clientConfigService;
    LicenseReissueService licenseReissueService;
    LicenseBundleZipper licenseBundleZipper;
    LicenseController controller;

    @BeforeEach
    void setUp() {
        licenseKeyService = mock(LicenseKeyService.class);
        licenseRepository = mock(LicenseRepository.class);
        clientConfigRepository = mock(ClientConfigRepository.class);
        clientConfigService = mock(ClientConfigService.class);
        licenseReissueService = mock(LicenseReissueService.class);
        licenseBundleZipper = mock(LicenseBundleZipper.class);
        controller = new LicenseController(licenseKeyService, licenseRepository,
                clientConfigRepository, clientConfigService, licenseReissueService, licenseBundleZipper);
    }

    @Test
    void reissueV2_returnsZipWithAttachmentHeadersAndCounts() {
        var result = new LicenseReissueService.ReissueResult(
                List.of(new LicenseReissueService.ReissuedLicence(
                        "acme", "Acme", "M", 1, "acme.dat", "NEW", "OLD")),
                List.of());
        when(licenseReissueService.reissueAll()).thenReturn(result);
        when(licenseBundleZipper.zip(any())).thenReturn(new byte[]{1, 2, 3});

        ResponseEntity<byte[]> resp = controller.reissueV2();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(resp.getHeaders().getFirst("Content-Type")).isEqualTo("application/zip");
        assertThat(resp.getHeaders().getFirst("Content-Disposition"))
                .startsWith("attachment; filename=\"daman-v2-licences-").endsWith(".zip\"");
        assertThat(resp.getHeaders().getFirst("X-Reissued-Count")).isEqualTo("1");
        assertThat(resp.getHeaders().getFirst("X-Skipped-Count")).isEqualTo("0");
    }

    @Test
    void reissueV2Preview_passesThroughServiceResult() {
        var preview = new LicenseReissueService.ReissuePreview(List.of(), List.of());
        when(licenseReissueService.preview()).thenReturn(preview);

        assertThat(controller.reissueV2Preview()).isSameAs(preview);
    }

    @Test
    void revertReissue_returnsRestoredKey_andIsIdempotentOnSecondCall() {
        when(licenseReissueService.revertReissue(7L))
                .thenReturn(new LicenseReissueService.RevertResult("acme", "M-AAAA", "original.v1.key"))
                .thenThrow(new IllegalArgumentException("Licence 7 has no previous key to revert to"));

        ResponseEntity<?> first = controller.revertReissue(7L);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(((LicenseReissueService.RevertResult) first.getBody()).restoredKey())
                .isEqualTo("original.v1.key");

        ResponseEntity<?> second = controller.revertReissue(7L);
        assertThat(second.getStatusCode().value()).isEqualTo(400);
        assertThat(((java.util.Map<?, ?>) second.getBody()).get("error")).isNotNull();
    }

    @Test
    void revertReissue_unknownId_returns400() {
        when(licenseReissueService.revertReissue(99L))
                .thenThrow(new IllegalArgumentException("No licence with id 99"));

        ResponseEntity<?> resp = controller.revertReissue(99L);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(((java.util.Map<?, ?>) resp.getBody()).get("error")).isNotNull();
    }

    @Test
    void getAll_mapsPayloadVersionOntoDto() {
        License l = new License();
        l.setId(7L);
        l.setClientCode("acme");
        l.setMachineId("M");
        l.setLicenseKey("some.key");
        l.setStatus("ACTIVE");
        when(licenseRepository.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));
        when(licenseKeyService.payloadVersion("some.key")).thenReturn(2);

        List<LicenseDto> dtos = controller.getAll();

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).id()).isEqualTo(7L);
        assertThat(dtos.get(0).payloadVersion()).isEqualTo(2);
        assertThat(dtos.get(0).reissued()).isFalse(); // no previousLicenseKey set
    }

    @Test
    void getAll_reissuedIsTrue_whenPreviousLicenseKeyPresent() {
        License l = new License();
        l.setId(8L);
        l.setClientCode("acme");
        l.setMachineId("M");
        l.setLicenseKey("v2.key");
        l.setPreviousLicenseKey("v1.key");
        l.setStatus("ACTIVE");
        when(licenseRepository.findAllByOrderByActivatedAtDesc()).thenReturn(List.of(l));
        when(licenseKeyService.payloadVersion("v2.key")).thenReturn(2);

        List<LicenseDto> dtos = controller.getAll();

        assertThat(dtos.get(0).reissued()).isTrue();
    }
}
