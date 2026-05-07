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

    @GetMapping
    public List<AppVersionDto> getAll() {
        return appVersionService.findAll();
    }

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
