package com.daman.admin.controller;

import com.daman.admin.entity.ClientHeartbeat;
import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientHeartbeatRepository;
import com.daman.admin.repository.LicenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the portal is allowed to believe.
 *
 * <p>Every one of these returns 200 — that is the contract, so "was it accepted" is asserted by
 * whether a row was saved, never by the status code.
 */
@ExtendWith(MockitoExtension.class)
class ClientHeartbeatControllerTest {

    private static final String CODE = "shop-7";
    private static final String MACHINE = "machine-abc";
    private static final String KEY = "licence-key-current";

    @Mock private ClientHeartbeatRepository heartbeatRepository;
    @Mock private LicenseRepository licenseRepository;
    @InjectMocks private ClientHeartbeatController controller;

    private License active;

    @BeforeEach
    void setUp() {
        active = new License();
        active.setClientCode(CODE);
        active.setMachineId(MACHINE);
        active.setLicenseKey(KEY);
        active.setStatus("ACTIVE");
        lenient().when(heartbeatRepository.existsByClientCodeAndMachineIdAndReceivedAtAfter(
                anyString(), anyString(), any())).thenReturn(false);
    }

    private ClientHeartbeatController.HeartbeatRequest request(String key) {
        ClientHeartbeatController.HeartbeatRequest r = new ClientHeartbeatController.HeartbeatRequest();
        r.setClientCode(CODE);
        r.setMachineId(MACHINE);
        r.setLicenseKey(key);
        r.setAppVersion("1.3.5");
        r.setLastSaleOn(LocalDate.of(2026, 11, 5));
        r.setTotalSales(412L);
        r.setActiveUserCount(3);
        return r;
    }

    private void licenceLookupReturns(List<License> licences) {
        when(licenseRepository.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc(
                MACHINE, CODE, "ACTIVE")).thenReturn(licences);
    }

    @Test
    @DisplayName("a valid heartbeat is recorded, with the server's own clock")
    void validHeartbeatIsRecorded() {
        licenceLookupReturns(List.of(active));
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        controller.receive(request(KEY));

        ArgumentCaptor<ClientHeartbeat> captor = ArgumentCaptor.forClass(ClientHeartbeat.class);
        verify(heartbeatRepository).save(captor.capture());
        ClientHeartbeat saved = captor.getValue();
        assertThat(saved.getClientCode()).isEqualTo(CODE);
        assertThat(saved.getTotalSales()).isEqualTo(412L);
        assertThat(saved.getLastSaleOn()).isEqualTo(LocalDate.of(2026, 11, 5));
        // Server time, not anything the client sent — a till with a wrong clock must not be able
        // to place a heartbeat in the future or bury one in the past.
        assertThat(saved.getReceivedAt()).isAfter(before);
    }

    @Test
    @DisplayName("a licence key rotated by renewal is still accepted via previousLicenseKey")
    void rotatedKeyStillAccepted() {
        // Renewal rotates licence_key in place and the client only picks the new one up on its
        // next 12-hourly poll. Without this, every renewal would blank a healthy shop out of the
        // portal for half a day, which reads exactly like "this shop has closed".
        active.setLicenseKey("licence-key-rotated");
        active.setPreviousLicenseKey(KEY);
        licenceLookupReturns(List.of(active));

        controller.receive(request(KEY));

        verify(heartbeatRepository).save(any(ClientHeartbeat.class));
    }

    @Test
    @DisplayName("a heartbeat with an unknown licence key records nothing")
    void unknownKeyRecordsNothing() {
        licenceLookupReturns(List.of(active));

        controller.receive(request("not-this-shops-key"));

        verify(heartbeatRepository, never()).save(any());
    }

    @Test
    @DisplayName("no ACTIVE licence for that machine records nothing")
    void noActiveLicenceRecordsNothing() {
        licenceLookupReturns(List.of());

        controller.receive(request(KEY));

        verify(heartbeatRepository, never()).save(any());
    }

    @Test
    @DisplayName("a second heartbeat inside the dedup window is dropped")
    void duplicateInsideWindowIsDropped() {
        licenceLookupReturns(List.of(active));
        when(heartbeatRepository.existsByClientCodeAndMachineIdAndReceivedAtAfter(
                anyString(), anyString(), any())).thenReturn(true);

        controller.receive(request(KEY));

        // Costs nothing to drop: totalSales is cumulative, so the next accepted report carries
        // the whole picture again. This is why the payload is not a delta.
        verify(heartbeatRepository, never()).save(any());
    }

    @Test
    @DisplayName("a malformed body is ignored rather than throwing")
    void malformedBodyIsIgnored() {
        ClientHeartbeatController.HeartbeatRequest empty =
                new ClientHeartbeatController.HeartbeatRequest();

        assertThat(controller.receive(empty).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.receive(null).getStatusCode().value()).isEqualTo(200);
        verify(heartbeatRepository, never()).save(any());
    }

    @Test
    @DisplayName("every outcome is 200, so the endpoint is not an oracle for which clients exist")
    void everyOutcomeIs200() {
        licenceLookupReturns(List.of());

        assertThat(controller.receive(request(KEY)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a repository failure never escapes to the caller")
    void repositoryFailureIsSwallowed() {
        when(licenseRepository.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc(
                anyString(), anyString(), anyString())).thenThrow(new RuntimeException("db down"));

        // A heartbeat is the least important thing this server does; it must never return a 500
        // a client might retry, nor take the portal down with it.
        assertThat(controller.receive(request(KEY)).getStatusCode().value()).isEqualTo(200);
    }
}
