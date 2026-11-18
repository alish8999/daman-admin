package com.daman.admin.controller;

import com.daman.admin.dto.AppVersionDto;
import com.daman.admin.dto.AppVersionRequest;
import com.daman.admin.service.AppVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/versions")
@RequiredArgsConstructor
public class AppVersionController {

    private final AppVersionService appVersionService;
    private final com.daman.admin.service.PublishedReleaseService publishedReleaseService;

    @GetMapping
    public List<AppVersionDto> getAll() {
        return appVersionService.findAll();
    }

    /**
     * The newest version that actually exists for download, read from the update feed the shops
     * poll — NOT from {@code app_versions}, which is only authoritative on the machine that runs
     * the build and on this server stopped in September.
     *
     * <p>{@code version} is null when no feed could be read; the portal then falls back to what
     * it already knows rather than showing nothing.
     */
    @GetMapping("/published")
    public PublishedRelease published() {
        return new PublishedRelease(publishedReleaseService.newestPublishedVersion());
    }

    public record PublishedRelease(String version) {}

    @PostMapping
    public ResponseEntity<AppVersionDto> create(@Valid @RequestBody AppVersionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(appVersionService.create(req));
    }

    @PutMapping("/{id}")
    public AppVersionDto update(@PathVariable Long id, @Valid @RequestBody AppVersionRequest req) {
        return appVersionService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        appVersionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
