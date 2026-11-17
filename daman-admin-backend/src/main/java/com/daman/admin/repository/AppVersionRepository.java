package com.daman.admin.repository;

import com.daman.admin.entity.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {
    // release_date is a DATE and several releases land on one day routinely, so it cannot
    // order them on its own - and element zero of this list IS "the latest version" to the
    // Build card, to the guard that refuses an older number, and to the release-note lookup
    // for a build started without one. The id breaks the tie: among releases dated the same
    // day, the one written last is the latest. See AppVersionOrderingTest.
    List<AppVersion> findAllByOrderByReleaseDateDescIdDesc();
    Optional<AppVersion> findTopByOrderByReleaseDateDescIdDesc();
    Optional<AppVersion> findByVersionNumber(String versionNumber);
    boolean existsByVersionNumber(String versionNumber);
}
