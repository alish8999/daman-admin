package com.daman.admin.repository;

import com.daman.admin.entity.HostedInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HostedInstanceRepository extends JpaRepository<HostedInstance, Long> {
    Optional<HostedInstance> findByClientCode(String clientCode);
    Optional<HostedInstance> findBySubdomain(String subdomain);
    boolean existsBySubdomain(String subdomain);
}
