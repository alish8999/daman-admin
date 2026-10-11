package com.daman.admin.service;

import com.daman.admin.dto.ChangelogEntryDto;
import com.daman.admin.entity.ChangelogEntry;
import com.daman.admin.repository.ChangelogEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<ChangelogEntry> existing = repository.findAll();
        List<String> toDelete = existing.stream()
                .map(ChangelogEntry::getId)
                .filter(id -> !incomingIds.contains(id))
                .toList();
        if (!toDelete.isEmpty()) {
            repository.deleteAllById(toDelete);
        }
        // `deployed` is owned by the server, not the pickers. Both of them PUT
        // the ENTIRE library on every save, so taking the incoming value at
        // face value would clear every flag as soon as one picker that doesn't
        // know about the field saves a single edit. An incoming null therefore
        // means "leave it as it was" — and for a bullet that doesn't exist yet,
        // false: newly written release notes have by definition not shipped.
        Map<String, Boolean> storedDeployed = existing.stream()
                .collect(HashMap::new, (m, e) -> m.put(e.getId(), e.getDeployed()), HashMap::putAll);
        List<ChangelogEntry> saved = repository.saveAll(entries.stream()
                .map(dto -> toEntity(dto, storedDeployed.containsKey(dto.getId())
                        ? storedDeployed.get(dto.getId())
                        : Boolean.FALSE))
                .toList());
        return saved.stream().map(this::toDto).toList();
    }

    /**
     * Flags every listed bullet as shipped. Called when a version is saved
     * with those bullets selected (AppVersionService), which is the only way
     * an entry ever becomes deployed — there is no manual toggle.
     *
     * <p>Unknown ids are ignored rather than rejected: the caller is a
     * best-effort hook on the build/version path, and a stale id in a payload
     * must not fail the release it came from.
     */
    @Transactional
    public void markDeployed(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<ChangelogEntry> toMark = repository.findAllById(Set.copyOf(ids)).stream()
                .filter(e -> !Boolean.TRUE.equals(e.getDeployed()))
                .toList();
        if (toMark.isEmpty()) {
            return;
        }
        toMark.forEach(e -> e.setDeployed(Boolean.TRUE));
        repository.saveAll(toMark);
    }

    private ChangelogEntryDto toDto(ChangelogEntry entity) {
        return new ChangelogEntryDto(entity.getId(), entity.getEn(), entity.getAr(), entity.getDeployed());
    }

    private ChangelogEntry toEntity(ChangelogEntryDto dto, Boolean deployed) {
        return ChangelogEntry.builder()
                .id(dto.getId())
                .en(dto.getEn())
                .ar(dto.getAr())
                .deployed(deployed)
                .build();
    }
}
