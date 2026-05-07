package com.daman.admin.repository;

import com.daman.admin.entity.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {
    List<AppVersion> findAllByOrderByReleaseDateDesc();
    Optional<AppVersion> findTopByOrderByReleaseDateDesc();
    Optional<AppVersion> findByVersionNumber(String versionNumber);
    boolean existsByVersionNumber(String versionNumber);
}
