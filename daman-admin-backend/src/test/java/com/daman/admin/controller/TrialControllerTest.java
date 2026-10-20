package com.daman.admin.controller;

import com.daman.admin.dto.TrialRegisterRequest;
import com.daman.admin.dto.TrialRegisterResponse;
import com.daman.admin.service.TrialAlreadyUsedException;
import com.daman.admin.service.TrialRegistrationService;
import com.daman.admin.service.TrialsDisabledException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrialControllerTest {

    private TrialRegistrationService service;
    private TrialController controller;
    private final TrialRegisterRequest req =
            new TrialRegisterRequest("M1", "Win11", "Shop", null, null, "general", false, "USD");

    @BeforeEach
    void setUp() {
        service = mock(TrialRegistrationService.class);
        controller = new TrialController(service);
    }

    @Test
    void successBodyIsExactlyTheLicenceKey() {
        when(service.register(any(), anyString(), anyString())).thenReturn(new TrialRegisterResponse("KEY"));

        ResponseEntity<?> res = controller.register(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(new TrialRegisterResponse("KEY"));
        verify(service).register(req, "M1", "Win11");
    }

    @Test
    void disabledIs403TrialsDisabled() {
        when(service.register(any(), any(), any())).thenThrow(new TrialsDisabledException());

        ResponseEntity<?> res = controller.register(req);

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "trials_disabled"));
    }

    @Test
    void usedIs409TrialAlreadyUsed() {
        when(service.register(any(), any(), any())).thenThrow(new TrialAlreadyUsedException());

        ResponseEntity<?> res = controller.register(req);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "trial_already_used"));
    }

    @Test
    void invalidInputIs400WithAGenericBodyThatEchoesNothing() {
        when(service.register(any(), any(), any())).thenThrow(new IllegalArgumentException("storeType 'x' unknown"));

        ResponseEntity<?> res = controller.register(req);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "invalid_request"));
    }

    @Test
    void aMissingBodyIs400NotANullPointer() {
        ResponseEntity<?> res = controller.register(null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(service, org.mockito.Mockito.never()).register(any(), any(), any());
    }
}
