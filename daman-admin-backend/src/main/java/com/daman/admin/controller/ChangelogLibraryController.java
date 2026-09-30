package com.daman.admin.controller;

import com.daman.admin.dto.ChangelogEntryDto;
import com.daman.admin.service.ChangelogLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/changelog-library")
@RequiredArgsConstructor
public class ChangelogLibraryController {

    private final ChangelogLibraryService changelogLibraryService;

    @GetMapping
    public List<ChangelogEntryDto> getAll() {
        return changelogLibraryService.findAll();
    }

    @PutMapping
    public List<ChangelogEntryDto> saveAll(@RequestBody List<ChangelogEntryDto> entries) {
        return changelogLibraryService.saveAll(entries);
    }
}
