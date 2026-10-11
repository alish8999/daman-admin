package com.daman.admin.service;

import com.daman.admin.dto.ChangelogEntryDto;
import com.daman.admin.entity.ChangelogEntry;
import com.daman.admin.repository.ChangelogEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.*;

/**
 * Covers the ownership rule for {@code deployed}: the pickers send the whole
 * library on every save, so the flag has to survive a save that knows nothing
 * about it.
 */
class ChangelogLibraryServiceTest {

    ChangelogEntryRepository repository;
    ChangelogLibraryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChangelogEntryRepository.class);
        service = new ChangelogLibraryService(repository);
        when(repository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<ChangelogEntry> arg = inv.getArgument(0);
            return java.util.stream.StreamSupport.stream(arg.spliterator(), false).toList();
        });
    }

    private static ChangelogEntry entry(String id, Boolean deployed) {
        return ChangelogEntry.builder().id(id).en(id + "-en").ar(id + "-ar").deployed(deployed).build();
    }

    private static Map<String, ChangelogEntryDto> byId(List<ChangelogEntryDto> dtos) {
        return dtos.stream().collect(Collectors.toMap(ChangelogEntryDto::getId, Function.identity()));
    }

    /**
     * The whole reason the server owns this field. A picker that has never
     * heard of `deployed` round-trips the library with it null; taking that at
     * face value would clear the release history of every bullet at once.
     */
    @Test
    void saveAll_incomingNullDeployed_keepsStoredValue() {
        when(repository.findAll()).thenReturn(List.of(entry("shipped", true), entry("pending", false)));

        List<ChangelogEntryDto> result = service.saveAll(List.of(
                new ChangelogEntryDto("shipped", "edited-en", "edited-ar", null),
                new ChangelogEntryDto("pending", "pending-en", "pending-ar", null)));

        Map<String, ChangelogEntryDto> saved = byId(result);
        assertThat(saved.get("shipped").getDeployed()).isTrue();
        assertThat(saved.get("pending").getDeployed()).isFalse();
        // The edit itself still lands — only the flag is protected.
        assertThat(saved.get("shipped").getEn()).isEqualTo("edited-en");
    }

    /**
     * A bullet written now has, by definition, not shipped. It must be false
     * rather than null, because ChangelogDeployedBackfillRunner treats null as
     * "predates the flag, therefore already released" — a new entry left null
     * would be wrongly marked deployed on the next boot.
     */
    @Test
    void saveAll_brandNewEntry_defaultsToNotDeployed() {
        when(repository.findAll()).thenReturn(List.of());

        List<ChangelogEntryDto> result = service.saveAll(List.of(
                new ChangelogEntryDto("fresh", "en", "ar", null)));

        assertThat(result).singleElement()
                .extracting(ChangelogEntryDto::getDeployed).isEqualTo(Boolean.FALSE);
    }

    /** A removed id is still deleted — the deployed rule didn't break that. */
    @Test
    void saveAll_dropsEntriesTheCallerRemoved() {
        when(repository.findAll()).thenReturn(List.of(entry("keep", true), entry("gone", true)));

        service.saveAll(List.of(new ChangelogEntryDto("keep", "en", "ar", null)));

        verify(repository).deleteAllById(List.of("gone"));
    }

    @Test
    void markDeployed_flagsOnlyTheListedEntries() {
        ChangelogEntry pending = entry("pending", false);
        when(repository.findAllById(anyIterable())).thenReturn(List.of(pending));

        service.markDeployed(List.of("pending"));

        assertThat(pending.getDeployed()).isTrue();
        verify(repository).saveAll(List.of(pending));
    }

    /** Nothing to do must not mean an empty write. */
    @Test
    void markDeployed_alreadyDeployed_writesNothing() {
        when(repository.findAllById(anyIterable())).thenReturn(List.of(entry("shipped", true)));

        service.markDeployed(List.of("shipped"));

        verify(repository, never()).saveAll(any());
    }

    /**
     * The caller is a best-effort hook on the release path — a null or empty
     * id list is the normal "changelog typed by hand" case, not an error.
     */
    @Test
    void markDeployed_nullOrEmpty_isANoOp() {
        service.markDeployed(null);
        service.markDeployed(List.of());

        verify(repository, never()).findAllById(anyIterable());
        verify(repository, never()).saveAll(any());
    }
}
