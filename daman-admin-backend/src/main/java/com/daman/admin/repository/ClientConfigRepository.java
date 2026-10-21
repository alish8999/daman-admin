package com.daman.admin.repository;

import com.daman.admin.entity.ClientConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientConfigRepository extends JpaRepository<ClientConfig, Long> {
    Optional<ClientConfig> findByClientCode(String clientCode);
    boolean existsByClientCode(String clientCode);

    /** Every client code starting with the prefix; used to pick the next sequential trial code. */
    @org.springframework.data.jpa.repository.Query("select c.clientCode from ClientConfig c where c.clientCode like concat(:prefix, '%')")
    java.util.List<String> findClientCodesByPrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);
}
