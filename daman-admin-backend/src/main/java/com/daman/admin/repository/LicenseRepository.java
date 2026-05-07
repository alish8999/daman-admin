package com.daman.admin.repository;

import com.daman.admin.entity.License;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LicenseRepository extends JpaRepository<License, Long> {
    List<License> findByClientCodeOrderByActivatedAtDesc(String clientCode);
    Optional<License> findByMachineIdAndClientCodeAndStatus(String machineId, String clientCode, String status);
    Optional<License> findByMachineIdAndStatus(String machineId, String status);
    List<License> findAllByOrderByActivatedAtDesc();
    long countByClientCodeAndStatus(String clientCode, String status);
    java.util.Optional<License> findByClientCode(String clientCode);
}
