package com.daman.admin.controller;

import com.daman.admin.service.BuildService;
import com.daman.admin.service.ClientConfigService;
import com.daman.admin.service.DevRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@code /api/clients} dev "run as this client" endpoints.
 * Plain JUnit 5 + Mockito, no Spring context — matches {@code LicenseControllerReissueTest}.
 */
class ClientControllerDevTest {

    ClientConfigService clientConfigService;
    BuildService buildService;
    DevRunService devRunService;
    ClientController controller;

    @BeforeEach
    void setUp() {
        clientConfigService = mock(ClientConfigService.class);
        buildService = mock(BuildService.class);
        devRunService = mock(DevRunService.class);
        controller = new ClientController(clientConfigService, buildService, devRunService);
    }

    @Test
    void devRun_ok_returnsResult() {
        var result = new DevRunService.DevRunResult("acme", "generic", "MID", "/x/acme/daman_db.sqlite",
                true, "/x/license.dat", java.util.List.of("restart"));
        when(devRunService.devRun(eq("acme"), any())).thenReturn(result);
        ResponseEntity<?> resp = controller.devRun("acme", new DevRunService.DevRunRequest("generic", "/db.sqlite"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(((DevRunService.DevRunResult) resp.getBody()).machineId()).isEqualTo("MID");
    }

    @Test
    void devRun_serviceThrowsIllegalArg_returns400WithError() {
        when(devRunService.devRun(any(), any())).thenThrow(new IllegalArgumentException("dbFile not found: /x"));
        ResponseEntity<?> resp = controller.devRun("acme", new DevRunService.DevRunRequest("generic", "/x"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(((java.util.Map<?, ?>) resp.getBody()).get("error")).isEqualTo("dbFile not found: /x");
    }

    @Test
    void devRun_machineIdUnknown_returns400() {
        when(devRunService.devRun(any(), any()))
                .thenThrow(new IllegalStateException("Dev machine ID unknown — ..."));
        ResponseEntity<?> resp = controller.devRun("acme", new DevRunService.DevRunRequest("generic", null));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(((java.util.Map<?, ?>) resp.getBody()).get("error").toString()).contains("machine ID unknown");
    }

    @Test
    void devCurrent_returnsServiceValue() {
        when(devRunService.devCurrent()).thenReturn(new DevRunService.DevCurrent("acme", "per-client", "dev"));
        assertThat(controller.devCurrent().clientCode()).isEqualTo("acme");
    }

    @Test
    void devReset_returns204_andCallsService() {
        ResponseEntity<Void> resp = controller.devReset();
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(devRunService).devReset();
    }

    @Test
    void devMachineId_badFormat_returns400() {
        doThrow(new IllegalArgumentException("machineId must be 16–128 …"))
                .when(devRunService).setDevMachineId("nope");
        ResponseEntity<?> resp = controller.devMachineId(java.util.Map.of("machineId", "nope"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }
}
