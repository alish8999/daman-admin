package com.daman.admin.config;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Records the already-live viora store in the hosted-instance registry so its port (8090)
 * is never handed to another store. Registry-only: nothing about viora itself is touched.
 * Idempotent via the findByClientCode guard.
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class HostedInstanceVioraBackfillRunner implements CommandLineRunner {

    private final HostedInstanceRepository repository;

    @Override
    public void run(String... args) {
        if (repository.findByClientCode("viora").isPresent()) return;
        HostedInstance h = new HostedInstance();
        h.setClientCode("viora");
        h.setSubdomain("viora");
        h.setPort(8090);
        h.setServiceName("daman-store-viora");
        h.setDataDir("/root/.daman/viora");
        h.setFrontendHostname("viora.damansoft.com");
        h.setApiHostname("viora-api.damansoft.com");
        h.setStatus("ACTIVE");
        repository.save(h);
        log.info("Recorded viora in hosted_instances (port 8090)");
    }
}
