package com.daman.admin.service;

import com.daman.admin.dto.ChangelogEntryDto;
import com.daman.admin.entity.ChangelogEntry;
import com.daman.admin.repository.ChangelogEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads/writes the reusable changelog bullet library the "Select Changelog"
 * picker is built from — backed by the changelog_entries table (see
 * ChangelogEntry's doc comment for why this replaced a JSON file checked
 * into daman-admin-frontend).
 */
@Service
@RequiredArgsConstructor
public class ChangelogLibraryService {

    private final ChangelogEntryRepository repository;

    public List<ChangelogEntryDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    /**
     * The picker always sends the FULL library (its own edit/add/remove all
     * mutate a local copy before calling this) — same "replace everything"
     * contract the old file's whole-overwrite had. saveAll() alone would
     * upsert every entry in `entries` but never remove a row the caller
     * dropped, so removed ids are deleted explicitly first.
     */
    @Transactional
    public List<ChangelogEntryDto> saveAll(List<ChangelogEntryDto> entries) {
        Set<String> incomingIds = entries.stream().map(ChangelogEntryDto::getId).collect(Collectors.toSet());
        List<String> toDelete = repository.findAll().stream()
                .map(ChangelogEntry::getId)
                .filter(id -> !incomingIds.contains(id))
                .toList();
        if (!toDelete.isEmpty()) {
            repository.deleteAllById(toDelete);
        }
        List<ChangelogEntry> saved = repository.saveAll(entries.stream().map(this::toEntity).toList());
        return saved.stream().map(this::toDto).toList();
    }

    private ChangelogEntryDto toDto(ChangelogEntry entity) {
        return new ChangelogEntryDto(entity.getId(), entity.getEn(), entity.getAr());
    }

    private ChangelogEntry toEntity(ChangelogEntryDto dto) {
        return ChangelogEntry.builder().id(dto.getId()).en(dto.getEn()).ar(dto.getAr()).build();
    }
}
