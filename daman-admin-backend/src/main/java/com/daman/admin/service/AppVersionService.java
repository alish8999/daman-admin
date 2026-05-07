package com.daman.admin.service;

import com.daman.admin.dto.AppVersionDto;
import com.daman.admin.dto.AppVersionRequest;
import com.daman.admin.entity.AppVersion;
import com.daman.admin.repository.AppVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AppVersionService {

    private final AppVersionRepository repository;

    public List<AppVersionDto> findAll() {
        return repository.findAllByOrderByReleaseDateDesc().stream().map(this::toDto).toList();
    }

    public AppVersionDto create(AppVersionRequest req) {
        if (repository.existsByVersionNumber(req.getVersionNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Version already exists: " + req.getVersionNumber());
        }
        AppVersion v = new AppVersion();
        v.setVersionNumber(req.getVersionNumber());
        v.setReleaseDate(req.getReleaseDate());
        v.setChangelogText(req.getChangelogText());
        return toDto(repository.save(v));
    }

    public AppVersionDto update(Long id, AppVersionRequest req) {
        AppVersion v = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found: " + id));
        if (!v.getVersionNumber().equals(req.getVersionNumber())
                && repository.existsByVersionNumber(req.getVersionNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Version already exists: " + req.getVersionNumber());
        }
        v.setVersionNumber(req.getVersionNumber());
        v.setReleaseDate(req.getReleaseDate());
        v.setChangelogText(req.getChangelogText());
        return toDto(repository.save(v));
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found: " + id);
        }
        repository.deleteById(id);
    }

    private AppVersionDto toDto(AppVersion v) {
        return AppVersionDto.builder()
                .id(v.getId())
                .versionNumber(v.getVersionNumber())
                .releaseDate(v.getReleaseDate())
                .changelogText(v.getChangelogText())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
