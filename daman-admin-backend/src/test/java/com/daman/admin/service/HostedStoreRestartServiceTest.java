package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedStoreRestartService.RestartReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HostedStoreRestartServiceTest {

    private HostedInstanceRepository repository;
    private ServerOps ops;
    private HostedStoreRestartService service;

    @BeforeEach
    void setUp() {
        repository = mock(HostedInstanceRepository.class);
        ops = mock(ServerOps.class);
        // 3 health polls, no sleep: the tests never wait on a real clock.
        service = new HostedStoreRestartService(repository, ops, 3, 0);
    }

    private HostedInstance store(long id, String code, int port, String status) {
        HostedInstance i = new HostedInstance();
        i.setId(id);
        i.setClientCode(code);
        i.setPort(port);
        i.setServiceName("daman-store-" + code);
        i.setStatus(status);
        return i;
    }

    @Test
    void walkRestartsActiveStoresInIdOrderAndHaltsAtTheFirstUnhealthy() {
        // Returned out of id order on purpose: the walk must sort, not trust the repository.
        when(repository.findAll()).thenReturn(List.of(
                store(3, "c", 9103, "ACTIVE"), store(1, "a", 9101, "ACTIVE"),
                store(2, "b", 9102, "ACTIVE")));
        when(ops.health(9101)).thenReturn(true);
        when(ops.health(9102)).thenReturn(false);
        when(ops.health(9103)).thenReturn(true);

        RestartReport report = service.walk();

        assertThat(report.walked()).extracting(HostedStoreRestartService.RestartResult::clientCode)
                .containsExactly("a", "b");
        assertThat(report.walked().get(0).healthy()).isTrue();
        assertThat(report.walked().get(1).healthy()).isFalse();
        // c was never reached: neither walked nor skipped by rule.
        assertThat(report.walked()).extracting(HostedStoreRestartService.RestartResult::clientCode)
                .doesNotContain("c");
        assertThat(report.skipped()).extracting(HostedStoreRestartService.SkippedStore::clientCode)
                .doesNotContain("c");
    }

    @Test
    void walkSkipsNonActiveRowsWithAReason() {
        when(repository.findAll()).thenReturn(List.of(
                store(1, "sleeping", 9101, "SUSPENDED"), store(2, "live", 9102, "ACTIVE")));
        when(ops.health(anyInt())).thenReturn(true);

        RestartReport report = service.walk();

        assertThat(report.skipped()).hasSize(1);
        assertThat(report.skipped().get(0).clientCode()).isEqualTo("sleeping");
        assertThat(report.skipped().get(0).reason()).isNotBlank();
        assertThat(report.walked()).extracting(HostedStoreRestartService.RestartResult::clientCode)
                .containsExactly("live");
    }
}
