package com.daman.admin.controller;

import com.daman.admin.dto.BuildStatusDto;
import com.daman.admin.dto.ClientConfigDto;
import com.daman.admin.dto.ClientConfigExportDto;
import com.daman.admin.dto.ClientConfigRequest;
import com.daman.admin.entity.BuildLog;
import com.daman.admin.service.BuildService;
import com.daman.admin.service.ClientConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
public class ClientController {

    private final ClientConfigService service;
    private final BuildService buildService;

    @GetMapping
    public List<ClientConfigDto> getAll() {
        return service.findAll();
    }

    @GetMapping("/{clientCode}")
    public ClientConfigDto getOne(@PathVariable String clientCode) {
        return service.findByClientCode(clientCode);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientConfigDto create(@Valid @RequestBody ClientConfigRequest request) {
        return service.create(request);
    }

    @PutMapping("/{clientCode}")
    public ClientConfigDto update(@PathVariable String clientCode,
                                  @Valid @RequestBody ClientConfigRequest request) {
        return service.update(clientCode, request);
    }

    @DeleteMapping("/{clientCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String clientCode) {
        service.delete(clientCode);
    }

    @GetMapping("/{clientCode}/export")
    public ClientConfigExportDto export(@PathVariable String clientCode) {
        return service.export(clientCode);
    }

    @PostMapping("/{clientCode}/prepare-config")
    public ClientConfigService.PrepareConfigResult prepareConfig(@PathVariable String clientCode) {
        return service.prepareDevConfig(clientCode);
    }

    // ------------------------------------------------------------------
    // Dev convenience: returns the first client's config (no code needed)
    // ------------------------------------------------------------------

    @GetMapping("/dev/client-config")
    public ClientConfigExportDto devConfig() {
        return service.exportDefault();
    }

    // ------------------------------------------------------------------
    // Build endpoints
    // ------------------------------------------------------------------

    @PostMapping("/{clientCode}/build")
    public ResponseEntity<BuildStatusDto> triggerBuild(
            @PathVariable String clientCode,
            @RequestParam(defaultValue = "win") String platform,
            @RequestParam(defaultValue = "") String version) {
        BuildStatusDto status = buildService.startBuild(clientCode, platform, version);
        return ResponseEntity.accepted().body(status);
    }

    @GetMapping("/{clientCode}/build/status")
    public BuildStatusDto buildStatus(@PathVariable String clientCode) {
        return buildService.getStatus(clientCode);
    }

    @GetMapping("/{clientCode}/build/history")
    public List<BuildLog> buildHistory(@PathVariable String clientCode) {
        return buildService.getBuildHistory(clientCode);
    }

    @GetMapping("/{clientCode}/build/download")
    public ResponseEntity<Resource> downloadArtifact(@PathVariable String clientCode) {
        Path artifact = buildService.getArtifactFile(clientCode);
        if (artifact == null) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(artifact);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + artifact.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
