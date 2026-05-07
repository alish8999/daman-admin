package com.daman.admin.repository;

import com.daman.admin.entity.BuildLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuildLogRepository extends JpaRepository<BuildLog, Long> {
    List<BuildLog> findByClientCodeOrderByStartedAtDesc(String clientCode);
    List<BuildLog> findAllByOrderByStartedAtDesc();
}
