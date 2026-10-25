package com.daman.admin.config;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.service.HostedCapacity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HostedInstanceVioraBackfillRunnerTest {

    private final List<HostedInstance> rows = new ArrayList<>();
    private HostedInstanceRepository repo;
    private HostedInstanceVioraBackfillRunner runner;

    @BeforeEach
    void setUp() {
        repo = mock(HostedInstanceRepository.class);
        when(repo.findByClientCode(anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getClientCode().equals(i.getArgument(0))).findFirst());
        when(repo.save(any(HostedInstance.class))).thenAnswer(i -> {
            rows.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(repo.findAll()).thenAnswer(i -> new ArrayList<>(rows));
        runner = new HostedInstanceVioraBackfillRunner(repo);
    }

    @Test void insertsVioraOnceWithPort8090() throws Exception {
        runner.run();
        assertEquals(1, rows.size());
        HostedInstance v = rows.get(0);
        assertEquals("viora", v.getClientCode());
        assertEquals("viora", v.getSubdomain());
        assertEquals(8090, v.getPort());
        assertEquals("daman-store-viora", v.getServiceName());
        assertEquals("/root/.daman/viora", v.getDataDir());
        assertEquals("viora.damansoft.com", v.getFrontendHostname());
        assertEquals("viora-api.damansoft.com", v.getApiHostname());
        assertEquals("ACTIVE", v.getStatus());
        assertNull(v.getJwtSecret());
    }

    @Test void secondRunIsANoOpAndDoesNotDuplicate() throws Exception {
        runner.run();
        runner.run();
        assertEquals(1, rows.size());
        verify(repo, times(1)).save(any(HostedInstance.class));
    }

    @Test void afterBackfillPort8090IsNeverAllocated() throws Exception {
        runner.run();
        List<Integer> ports = repo.findAll().stream().map(HostedInstance::getPort).toList();
        assertNotEquals(8090, HostedCapacity.nextFreePort(ports));
        assertEquals(8091, HostedCapacity.nextFreePort(ports));
    }
}
