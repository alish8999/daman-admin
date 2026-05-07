package com.daman.admin.repository;

import com.daman.admin.entity.ClientConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientConfigRepository extends JpaRepository<ClientConfig, Long> {
    Optional<ClientConfig> findByClientCode(String clientCode);
    boolean existsByClientCode(String clientCode);
}
