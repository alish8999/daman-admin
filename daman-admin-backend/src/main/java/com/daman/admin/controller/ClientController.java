package com.daman.admin.controller;

import com.daman.admin.dto.BuildStatusDto;
import com.daman.admin.dto.ClientConfigDto;
import com.daman.admin.dto.ClientConfigExportDto;
import com.daman.admin.dto.ClientConfigRequest;
import com.daman.admin.entity.BuildLog;
import com.daman.admin.service.BuildService;
import com.daman.admin.service.ClientConfigService;
import com.daman.admin.service.DevRunService;
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
    private final DevRunService devRunService;

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
    // Dev "run as this client" (LOCAL admin backend only — see DevRunService)
    //
    // SECURITY: these endpoints write the local filesystem and mint a dev
    // licence with no machine-ID friction. Acceptable ONLY because the admin
    // backend is a locally-run developer tool — never expose it on a network.
    // ------------------------------------------------------------------

    @PostMapping("/{clientCode}/dev-run")
    public ResponseEntity<?> devRun(@PathVariable String clientCode,
                                    @RequestBody DevRunService.DevRunRequest req) {
        try {
            return ResponseEntity.ok(devRunService.devRun(clientCode, req));
        } catch (IllegalArgumentException | IllegalStateException | java.io.UncheckedIOException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/dev-current")
    public DevRunService.DevCurrent devCurrent() {
        return devRunService.devCurrent();
    }

    @PostMapping("/dev-reset")
    public ResponseEntity<Void> devReset() {
        devRunService.devReset();
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/dev-machine-id")
    public ResponseEntity<?> devMachineId(@RequestBody java.util.Map<String, String> body) {
        try {
            devRunService.setDevMachineId(body.get("machineId"));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", String.valueOf(e.getMessage())));
        }
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

    @PostMapping("/{clientCode}/build/open-folder")
    public ResponseEntity<Void> openOutputFolder(@PathVariable String clientCode) {
        Path dir = buildService.getOutputDir(clientCode);
        if (dir == null) return ResponseEntity.notFound().build();
        try {
            // Opens the folder in the host OS file manager (Windows Explorer on desktop builds)
            new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
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
