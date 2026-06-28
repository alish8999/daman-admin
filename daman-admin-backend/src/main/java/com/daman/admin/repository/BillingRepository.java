package com.daman.admin.repository;

import com.daman.admin.entity.Billing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingRepository extends JpaRepository<Billing, Long> {
    List<Billing> findByClientCodeOrderByCreatedAtDesc(String clientCode);
    List<Billing> findAllByOrderByCreatedAtDesc();
}
