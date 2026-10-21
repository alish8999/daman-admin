package com.daman.admin.controller;

import com.daman.admin.config.TrialRateLimiter;
import com.daman.admin.dto.TrialRegisterRequest;
import com.daman.admin.dto.TrialRegisterResponse;
import com.daman.admin.service.TrialAlreadyUsedException;
import com.daman.admin.service.TrialRegistrationService;
import com.daman.admin.service.TrialsDisabledException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrialControllerTest {

    private TrialRegistrationService service;
    private TrialRateLimiter limiter;
    private TrialController controller;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final TrialRegisterRequest req =
            new TrialRegisterRequest("M1", "Win11", "Shop", null, null, "general", false, "USD", null);

    @BeforeEach
    void setUp() {
        service = mock(TrialRegistrationService.class);
        limiter = mock(TrialRateLimiter.class);
        when(limiter.allow(anyString(), any())).thenReturn(true);
        controller = new TrialController(service, limiter, mapper);
    }

    private MockHttpServletRequest postRaw(byte[] body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/trial/register");
        r.setRemoteAddr("203.0.113.9");
        r.setContent(body);
        return r;
    }

    private MockHttpServletRequest post(Object body) {
        return postRaw(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void successBodyIsExactlyTheLicenceKey() {
        when(service.register(any(), anyString(), anyString())).thenReturn(new TrialRegisterResponse("KEY"));

        ResponseEntity<?> res = controller.register(post(req));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(new TrialRegisterResponse("KEY"));
        verify(service).register(req, "M1", "Win11");
    }

    @Test
    void disabledIs403TrialsDisabled() {
        when(service.register(any(), any(), any())).thenThrow(new TrialsDisabledException());

        ResponseEntity<?> res = controller.register(post(req));

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "trials_disabled"));
    }

    @Test
    void usedIs409TrialAlreadyUsed() {
        when(service.register(any(), any(), any())).thenThrow(new TrialAlreadyUsedException());

        ResponseEntity<?> res = controller.register(post(req));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "trial_already_used"));
    }

    @Test
    void invalidInputIs400WithAGenericBodyThatEchoesNothing() {
        when(service.register(any(), any(), any())).thenThrow(new IllegalArgumentException("storeType 'x' unknown"));

        ResponseEntity<?> res = controller.register(post(req));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "invalid_request"));
    }

    @Test
    void aMissingBodyIs400NotANullPointer() {
        ResponseEntity<?> res = controller.register(postRaw(new byte[0]));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(service, never()).register(any(), any(), any());
    }

    @Test
    void aBlockedRequestIs429AndTouchesNothing() {
        when(limiter.allow(anyString(), any())).thenReturn(false);

        ResponseEntity<?> res = controller.register(post(req));

        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "rate_limited"));
        verifyNoInteractions(service);
    }

    @Test
    void theLimiterSeesTheClientIpAndMachineId() {
        controller.register(post(req));

        verify(limiter).allow("203.0.113.9", "M1");
    }

    @Test
    void behindTheLoopbackTunnelTheRealClientIpComesFromCfConnectingIp() {
        MockHttpServletRequest r = post(req);
        r.setRemoteAddr("127.0.0.1");
        r.addHeader("CF-Connecting-IP", "198.51.100.7");

        controller.register(r);

        verify(limiter).allow("198.51.100.7", "M1");
    }

    @Test
    void aForwardedHeaderFromANonLoopbackPeerIsIgnored() {
        MockHttpServletRequest r = post(req);
        r.setRemoteAddr("203.0.113.9");
        r.addHeader("CF-Connecting-IP", "198.51.100.7");

        controller.register(r);

        verify(limiter).allow("203.0.113.9", "M1");
    }

    @Test
    void anOversizedBodyIs413BeforeItIsParsedOrCounted() {
        byte[] huge = ("{\"shopName\":\"" + "x".repeat(TrialRateLimiter.MAX_BODY_BYTES) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<?> res = controller.register(postRaw(huge));

        assertThat(res.getStatusCode().value()).isEqualTo(413);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "payload_too_large"));
        verifyNoInteractions(service);
        verify(limiter, never()).allow(any(), any());
    }

    @Test
    void aBodyJustUnderTheCapIsAccepted() {
        String pad = "x".repeat(TrialRateLimiter.MAX_BODY_BYTES - 200);
        byte[] ok = ("{\"machineId\":\"M1\",\"deviceInfo\":\"d\",\"pad\":\"" + pad + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        when(service.register(any(), anyString(), anyString())).thenReturn(new TrialRegisterResponse("KEY"));

        assertThat(controller.register(postRaw(ok)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void malformedJsonIs400AndTouchesNothing() {
        ResponseEntity<?> res = controller.register(postRaw("{not json".getBytes(StandardCharsets.UTF_8)));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "invalid_request"));
        verifyNoInteractions(service);
    }
}
