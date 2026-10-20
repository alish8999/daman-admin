package com.daman.admin.repository;

import com.daman.admin.entity.TrialRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrialRegistrationRepository extends JpaRepository<TrialRegistration, Long> {
    Optional<TrialRegistration> findByMachineId(String machineId);

    List<TrialRegistration> findAllByOrderByRegisteredAtDesc();

    /** A List, not an Optional: clientCode is not DB-unique here, and a throw would be swallowed anyway. */
    List<TrialRegistration> findAllByClientCode(String clientCode);
}
