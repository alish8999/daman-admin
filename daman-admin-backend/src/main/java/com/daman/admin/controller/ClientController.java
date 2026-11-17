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
    private final com.daman.admin.service.LicenceRemintService remintService;

    /**
     * What a features save did: the saved client, and what happened to each of its licence keys.
     *
     * <p>{@code remintError} is non-null only when re-minting itself failed. The save stands
     * either way — losing a correct feature save because signing was briefly unavailable would be
     * the worse outcome, and the next save or renewal picks the keys up.
     */
    public record FeatureSaveResponse(ClientConfigDto client,
                                      List<com.daman.admin.service.LicenceRemintService.RemintOutcome> remintOutcomes,
                                      String remintError) {}

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

    /**
     * The whole-client save the existing portal uses, which carries features inside it.
     *
     * <p>Re-mints this client's live licence keys when the save actually changed the feature set.
     * Today's portal has no separate features action, so without this a sold add-on would still
     * sit in the database until someone happened to renew — which is exactly the drift measured
     * on the live fleet. A profile-only edit mints nothing.
     */
    @PutMapping("/{clientCode}")
    public ClientConfigDto update(@PathVariable String clientCode,
                                  @Valid @RequestBody ClientConfigRequest request) {
        java.util.Map<String, Boolean> before = service.clientFeatures(clientCode);
        ClientConfigDto saved = service.update(clientCode, request);
        if (!before.equals(service.clientFeatures(clientCode))) {
            remintService.remintForClient(clientCode);
        }
        return saved;
    }

    /**
     * The features-only save, and the endpoint the new portal's confirmed "save features" action
     * calls.
     *
     * <p>Separate from the profile save because it re-mints licence keys: that is a consequence
     * worth confirming deliberately rather than attaching to a press of "Save changes".
     *
     * <p>A re-mint failure does not fail the save — the features are stored, and the response
     * carries the error so the portal can say the keys have not gone out yet.
     */
    @PutMapping("/{clientCode}/features")
    public ResponseEntity<FeatureSaveResponse> updateFeatures(
            @PathVariable String clientCode,
            @RequestBody com.daman.admin.dto.FeaturesRequest features) {
        ClientConfigDto saved = service.updateFeatures(clientCode, features);
        try {
            return ResponseEntity.ok(new FeatureSaveResponse(
                    saved, remintService.remintForClient(clientCode), null));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ClientController.class)
                    .error("Features saved for {} but re-minting its licence keys failed", clientCode, e);
            return ResponseEntity.ok(new FeatureSaveResponse(saved, List.of(), e.getMessage()));
        }
    }

    /**
     * Deletes the client and everything keyed to its code - licences, trials, builds and
     * billing. Refuses with 409 when real money has been received; see
     * {@link com.daman.admin.service.ClientConfigService#delete(String)}.
     */
    @DeleteMapping("/{clientCode}")
    public ResponseEntity<?> delete(@PathVariable String clientCode) {
        try {
            service.delete(clientCode);
            return ResponseEntity.noContent().build();
        } catch (com.daman.admin.service.ClientHasPaymentsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("error", "client_has_payments", "message", e.getMessage()));
        }
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

    // ------------------------------------------------------------------
    // Generic build — one neutral installer, no client identity baked in
    // (identity comes from the v2 licence at activation). Local equivalent
    // of the generic-installer CI workflow. All endpoints delegate with the
    // BuildService.GENERIC sentinel, which BuildService normalises internally.
    // ------------------------------------------------------------------

    /**
     * A refusal here is usually "this version has no release note" (see
     * {@code BuildService.requireReleaseNotes}), and that message names exactly what to go and
     * fill in — so it is returned as a 400 body the dashboard can show, not left to become a
     * generic 500 the owner has to read the server log to understand.
     */
    @PostMapping("/generic-build")
    public ResponseEntity<?> triggerGenericBuild(
            @RequestParam(defaultValue = "win") String platform,
            @RequestParam(defaultValue = "") String version) {
        try {
            return ResponseEntity.accepted().body(buildService.startBuild(BuildService.GENERIC, platform, version));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/generic-build/status")
    public BuildStatusDto genericBuildStatus() {
        return buildService.getStatus(BuildService.GENERIC);
    }

    @GetMapping("/generic-build/history")
    public List<BuildLog> genericBuildHistory() {
        return buildService.getBuildHistory(BuildService.GENERIC);
    }

    @PostMapping("/generic-build/open-folder")
    public ResponseEntity<Void> openGenericOutputFolder() {
        Path dir = buildService.getOutputDir(BuildService.GENERIC);
        if (dir == null) return ResponseEntity.notFound().build();
        try {
            new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/generic-build/download")
    public ResponseEntity<Resource> downloadGenericArtifact() {
        Path artifact = buildService.getArtifactFile(BuildService.GENERIC);
        if (artifact == null) return ResponseEntity.notFound().build();
        Resource resource = new FileSystemResource(artifact);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + artifact.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
