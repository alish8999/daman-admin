package com.daman.admin.controller;

import com.daman.admin.config.TrialRateLimiter;
import com.daman.admin.entity.License;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.service.LicenseKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * This endpoint is ANONYMOUS and world-callable, so the negative paths matter more than the
 * happy one. The property every test here defends: machineId and clientCode come from the
 * SIGNED payload, never from the request body.
 */
class LicenseRenewalControllerTest {

    private LicenseKeyService keys;
    private LicenseRepository licenses;
    private TrialRateLimiter limiter;
    private LicenseRenewalController controller;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        keys = mock(LicenseKeyService.class);
        licenses = mock(LicenseRepository.class);
        limiter = mock(TrialRateLimiter.class);
        when(limiter.allowRestore(anyString(), any())).thenReturn(true);
        controller = new LicenseRenewalController(keys, licenses, limiter, mapper);
    }

    private MockHttpServletRequest post(String body) {
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/license/renewal");
        http.setContent(body.getBytes(StandardCharsets.UTF_8));
        http.setRemoteAddr("203.0.113.9");
        return http;
    }

    private JsonNode payload(String machineId, String clientCode) {
        return mapper.readTree("{\"machineId\":\"" + machineId + "\",\"clientCode\":\"" + clientCode + "\"}");
    }

    private License active(String key) {
        License l = new License();
        l.setLicenseKey(key);
        l.setStatus("ACTIVE");
        return l;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> bodyOf(ResponseEntity<?> res) {
        return (Map<String, String>) res.getBody();
    }

    @Test
    void returnsTheCurrentKeyForTheMachineNamedInTheSignedPayload() {
        when(keys.verifiedPayload("PRESENTED")).thenReturn(payload("M1", "c-7"));
        when(licenses.findByMachineIdAndClientCodeAndStatus("M1", "c-7", "ACTIVE"))
                .thenReturn(Optional.of(active("RENEWED-KEY")));

        ResponseEntity<?> res = controller.renewal(post("{\"licenseKey\":\"PRESENTED\"}"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(bodyOf(res)).containsEntry("licenseKey", "RENEWED-KEY");
    }

    /**
     * THE test for this endpoint. A body-supplied machineId would turn it into a read-oracle
     * for another machine key, which is exactly what got /api/licenses/activate deleted.
     */
    @Test
    void aMachineIdInTheBodyIsIgnoredEntirely() {
        when(keys.verifiedPayload("PRESENTED")).thenReturn(payload("MINE", "c-7"));
        when(licenses.findByMachineIdAndClientCodeAndStatus("MINE", "c-7", "ACTIVE"))
                .thenReturn(Optional.of(active("MY-KEY")));

        ResponseEntity<?> res = controller.renewal(post(
                "{\"licenseKey\":\"PRESENTED\",\"machineId\":\"VICTIM\",\"clientCode\":\"daman-1\"}"));

        assertThat(bodyOf(res)).containsEntry("licenseKey", "MY-KEY");
        verify(licenses).findByMachineIdAndClientCodeAndStatus("MINE", "c-7", "ACTIVE");
        verify(licenses, never()).findByMachineIdAndClientCodeAndStatus("VICTIM", "daman-1", "ACTIVE");
    }

    @Test
    void anUnsignedOrForgedKeyIsRefusedAndNothingIsLookedUp() {
        when(keys.verifiedPayload(anyString())).thenReturn(null);

        ResponseEntity<?> res = controller.renewal(post("{\"licenseKey\":\"forged\"}"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(bodyOf(res)).containsEntry("error", "invalid_license");
        verifyNoInteractions(licenses);
    }

    @Test
    void aRevokedOrMissingLicenceIs404AndTellsTheCallerNothingMore() {
        when(keys.verifiedPayload("PRESENTED")).thenReturn(payload("M1", "c-7"));
        when(licenses.findByMachineIdAndClientCodeAndStatus("M1", "c-7", "ACTIVE"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.renewal(post("{\"licenseKey\":\"PRESENTED\"}"));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(bodyOf(res)).containsEntry("error", "no_license_for_machine");
    }

    @Test
    void aSignedPayloadMissingTheMachineOrClientIsRefused() {
        when(keys.verifiedPayload("P")).thenReturn(mapper.readTree("{\"clientCode\":\"c-7\"}"));
        assertThat(bodyOf(controller.renewal(post("{\"licenseKey\":\"P\"}"))))
                .containsEntry("error", "invalid_license");

        when(keys.verifiedPayload("P")).thenReturn(mapper.readTree("{\"machineId\":\"M1\"}"));
        assertThat(bodyOf(controller.renewal(post("{\"licenseKey\":\"P\"}"))))
                .containsEntry("error", "invalid_license");

        verifyNoInteractions(licenses);
    }

    @Test
    void aMissingOrUnparseableBodyIsRefused() {
        for (String body : new String[]{"", "{}", "{\"licenseKey\":\"\"}", "not json"}) {
            ResponseEntity<?> res = controller.renewal(post(body));
            assertThat(res.getStatusCode().value()).as("body %s", body).isEqualTo(400);
            assertThat(bodyOf(res)).containsEntry("error", "invalid_request");
        }
        verifyNoInteractions(licenses);
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsRead() {
        // A real oversized body, not a spoofed Content-Length: readCapped() is the guard that
        // actually holds when a caller lies about the length, so that is what must be exercised.
        ResponseEntity<?> res = controller.renewal(post(
                "{\"licenseKey\":\"" + "x".repeat(TrialRateLimiter.MAX_BODY_BYTES) + "\"}"));

        assertThat(res.getStatusCode().value()).isEqualTo(413);
        assertThat(bodyOf(res)).containsEntry("error", "payload_too_large");
        verifyNoInteractions(licenses);
        verifyNoInteractions(keys);
    }

    @Test
    void theRateLimitIsAppliedOnTheMachineFromTheSignedPayload() {
        when(keys.verifiedPayload("PRESENTED")).thenReturn(payload("M1", "c-7"));
        when(limiter.allowRestore("203.0.113.9", "M1")).thenReturn(false);

        ResponseEntity<?> res = controller.renewal(post("{\"licenseKey\":\"PRESENTED\"}"));

        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(bodyOf(res)).containsEntry("error", "rate_limited");
        verifyNoInteractions(licenses);
    }

    /** An unverifiable key has no machine id to limit on, so it must be limited per IP. */
    @Test
    void aForgedKeyFloodIsRateLimitedPerIp() {
        when(keys.verifiedPayload(anyString())).thenReturn(null);
        when(limiter.allowRestore("203.0.113.9", "")).thenReturn(false);

        ResponseEntity<?> res = controller.renewal(post("{\"licenseKey\":\"forged\"}"));

        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(bodyOf(res)).containsEntry("error", "rate_limited");
    }

    @Test
    void theCfConnectingIpHeaderIsTrustedOnlyFromLoopback() {
        when(keys.verifiedPayload("P")).thenReturn(payload("M1", "c-7"));
        when(licenses.findByMachineIdAndClientCodeAndStatus("M1", "c-7", "ACTIVE"))
                .thenReturn(Optional.of(active("K")));

        MockHttpServletRequest spoofed = post("{\"licenseKey\":\"P\"}");
        spoofed.setRemoteAddr("198.51.100.4");
        spoofed.addHeader("CF-Connecting-IP", "1.2.3.4");
        controller.renewal(spoofed);
        verify(limiter).allowRestore("198.51.100.4", "M1");

        MockHttpServletRequest tunnelled = post("{\"licenseKey\":\"P\"}");
        tunnelled.setRemoteAddr("127.0.0.1");
        tunnelled.addHeader("CF-Connecting-IP", "1.2.3.4");
        controller.renewal(tunnelled);
        verify(limiter).allowRestore("1.2.3.4", "M1");
    }
}
