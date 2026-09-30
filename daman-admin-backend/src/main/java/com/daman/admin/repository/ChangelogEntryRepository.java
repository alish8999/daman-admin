package com.daman.admin.repository;

import com.daman.admin.entity.ChangelogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangelogEntryRepository extends JpaRepository<ChangelogEntry, String> {
}
