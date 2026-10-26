package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedStoreLifecycleService.LifecycleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HostedStoreLifecycleServiceTest {

    private ServerOps ops;
    private HostedInstanceRepository repository;
    private HostedInstance row;
    private HostedStoreLifecycleService service;

    @BeforeEach
    void setUp() {
        ops = mock(ServerOps.class);
        repository = mock(HostedInstanceRepository.class);
        row = row("acme", "ACTIVE");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(row));
        when(repository.findByClientCode("ghost")).thenReturn(Optional.empty());
        // 3 health polls, no sleep: the tests never wait on a real clock.
        service = new HostedStoreLifecycleService(repository, ops, 3, 0);
    }

    private static HostedInstance row(String code, String status) {
        HostedInstance h = new HostedInstance();
        h.setClientCode(code);
        h.setPort(8091);
        h.setServiceName("daman-store-" + code);
        h.setStatus(status);
        return h;
    }

    @Test
    void suspendDisablesTheUnitAndMarksTheRow() {
        // disable --now, not stop: Restart=always + enabled means a stop comes back on reboot.
        LifecycleResult r = service.suspend("acme");

        verify(ops).stopAndDisable("daman-store-acme");
        assertThat(row.getStatus()).isEqualTo("SUSPENDED");
        verify(repository).save(row);
        assertThat(r.status()).isEqualTo("SUSPENDED");
    }

    @Test
    void suspendingAnAlreadySuspendedStoreIsANoOpThatReportsSuccess() {
        row.setStatus("SUSPENDED");

        LifecycleResult r = service.suspend("acme");

        assertThat(r.status()).isEqualTo("SUSPENDED");
        verify(ops, never()).stopAndDisable(anyString());
        verify(repository, never()).save(any(HostedInstance.class));
    }

    @Test
    void resumingAnAlreadyRunningStoreIsANoOpThatReportsSuccess() {
        LifecycleResult r = service.resume("acme");

        assertThat(r.status()).isEqualTo("ACTIVE");
        verify(ops, never()).enableAndStart(anyString());
        verify(repository, never()).save(any(HostedInstance.class));
    }

    @Test
    void resumeEnablesStartsAndWaitsForHealthBeforeMarkingActive() {
        row.setStatus("SUSPENDED");
        when(ops.health(8091)).thenReturn(false, false, true);

        LifecycleResult r = service.resume("acme");

        InOrder order = inOrder(ops);
        order.verify(ops).enableAndStart("daman-store-acme");
        order.verify(ops, times(3)).health(8091);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(r.status()).isEqualTo("ACTIVE");
    }

    @Test
    void resumeThatNeverBecomesHealthyLeavesTheRowSuspendedAndSaysSo() {
        row.setStatus("SUSPENDED");
        when(ops.health(anyInt())).thenReturn(false);

        LifecycleResult r = service.resume("acme");

        assertThat(row.getStatus()).isEqualTo("SUSPENDED");
        verify(repository, never()).save(any(HostedInstance.class));
        assertThat(r.status()).isEqualTo(HostedStoreLifecycleService.UNHEALTHY);
        assertThat(r.message()).contains("SUSPENDED").contains("3");
    }

    @Test
    void restartOneRefusesASuspendedStoreAndPointsAtResume() {
        row.setStatus("SUSPENDED");

        assertThatThrownBy(() -> service.restartOne("acme"))
                .isInstanceOf(HostedStoreLifecycleService.ConflictException.class)
                .hasMessageContaining("Resume");
        verify(ops, never()).restart(anyString());
    }

    @Test
    void restartOneRestartsAnActiveStoreAndWaitsForHealth() {
        when(ops.health(8091)).thenReturn(false, true);

        LifecycleResult r = service.restartOne("acme");

        InOrder order = inOrder(ops);
        order.verify(ops).restart("daman-store-acme");
        order.verify(ops, times(2)).health(8091);
        assertThat(r.status()).isEqualTo("ACTIVE");
    }

    @Test
    void restartOneThatNeverBecomesHealthyReportsUnhealthy() {
        when(ops.health(anyInt())).thenReturn(false);

        LifecycleResult r = service.restartOne("acme");

        assertThat(r.status()).isEqualTo(HostedStoreLifecycleService.UNHEALTHY);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void suspendRefusesAStoreThatIsNotActive() {
        row.setStatus("PROVISIONING");

        assertThatThrownBy(() -> service.suspend("acme"))
                .isInstanceOf(HostedStoreLifecycleService.ConflictException.class);
        verify(ops, never()).stopAndDisable(anyString());
    }

    @Test
    void anUnknownClientIsNotFound() {
        assertThatThrownBy(() -> service.suspend("ghost"))
                .isInstanceOf(HostedStoreLifecycleService.NotFoundException.class);
        assertThatThrownBy(() -> service.resume("ghost"))
                .isInstanceOf(HostedStoreLifecycleService.NotFoundException.class);
        assertThatThrownBy(() -> service.restartOne("ghost"))
                .isInstanceOf(HostedStoreLifecycleService.NotFoundException.class);
    }

    @Test
    void theDefaultHealthWaitIsBoundedUnderTheCloudflareEdgeTimeout() {
        // 25 x 3 s = 75 s < the 100 s after which the edge answers 524.
        assertThat(HostedStoreLifecycleService.HEALTH_ATTEMPTS
                * HostedStoreLifecycleService.HEALTH_INTERVAL_MS).isEqualTo(75_000L);
    }
}
