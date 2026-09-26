package com.daman.admin.service;

import com.daman.admin.dto.BuildStatusDto;
import com.daman.admin.dto.ClientConfigExportDto;
import com.daman.admin.entity.BuildLog;
import com.daman.admin.repository.AppVersionRepository;
import com.daman.admin.repository.BuildLogRepository;
import com.daman.admin.repository.ClientConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.Comparator;

@Slf4j
@Service
public class BuildService {

    private final ClientConfigRepository clientConfigRepository;
    private final ClientConfigService clientConfigService;
    private final BuildLogRepository buildLogRepository;
    private final LicenseKeyService licenseKeyService;
    private final AppVersionRepository appVersionRepository;

    @Value("${daman.workspace:}")
    private String workspacePath;

    private static final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /**
     * API-facing sentinel for "build the neutral, client-less installer" — the local
     * equivalent of the generic-installer CI workflow. The controller passes this;
     * BuildService normalises it to {@link #GENERIC_KEY} at every public entry point
     * so the status map, the {@code clients-build/<key>/} artifact dir, the persisted
     * {@code build_log.client_code}, and the history query all agree on one real key.
     */
    public static final String GENERIC = "__generic__";

    /** The canonical key a generic build is tracked/stored/looked-up under. */
    private static final String GENERIC_KEY = "generic";

    private final Map<String, BuildStatusDto> buildStatuses = new ConcurrentHashMap<>();
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor();

    /** Maps the {@link #GENERIC} sentinel to its canonical key; every other code passes through. */
    private static String resolveKey(String clientCode) {
        return GENERIC.equals(clientCode) ? GENERIC_KEY : clientCode;
    }

    public BuildService(ClientConfigRepository clientConfigRepository,
                        ClientConfigService clientConfigService,
                        BuildLogRepository buildLogRepository,
                        LicenseKeyService licenseKeyService,
                        AppVersionRepository appVersionRepository) {
        this.clientConfigRepository = clientConfigRepository;
        this.clientConfigService = clientConfigService;
        this.buildLogRepository = buildLogRepository;
        this.licenseKeyService = licenseKeyService;
        this.appVersionRepository = appVersionRepository;
    }

    private static final Map<String, String> MIME_TO_EXT = Map.of(
        "image/png", "png",
        "image/jpeg", "jpg",
        "image/gif", "gif",
        "image/svg+xml", "svg",
        "image/x-icon", "ico",
        "image/webp", "webp"
    );

    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("^data:([^;]+);base64,(.+)$", Pattern.DOTALL);

    private static final DateTimeFormatter LOG_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public BuildStatusDto getStatus(String clientCode) {
        String key = resolveKey(clientCode);
        return buildStatuses.getOrDefault(key,
                BuildStatusDto.builder()
                        .clientCode(key)
                        .status("IDLE")
                        .build());
    }

    public BuildStatusDto startBuild(String clientCode, String platform, String versionNumber) {
        if (workspacePath == null || workspacePath.isBlank()) {
            throw new IllegalStateException("daman.workspace is not configured in application.yml");
        }

        boolean generic = GENERIC.equals(clientCode);
        String key = generic ? GENERIC_KEY : clientCode;

        BuildStatusDto current = buildStatuses.get(key);
        if (current != null && "BUILDING".equals(current.getStatus())) {
            throw new IllegalStateException("Build already in progress for client: " + key);
        }

        // The generic build carries no client identity — its config comes from the
        // checked-in client.config.generic.json, not a ClientConfig row.
        if (!generic) {
            clientConfigRepository.findByClientCode(clientCode)
                    .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));
        }

        BuildStatusDto status = BuildStatusDto.builder()
                .clientCode(key)
                .platform(platform)
                .status("BUILDING")
                .startedAt(LocalDateTime.now())
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();

        buildStatuses.put(key, status);

        buildExecutor.submit(() -> executeBuild(key, platform, versionNumber));

        return status;
    }

    private void executeBuild(String clientCode, String platform, String versionNumber) {
        BuildStatusDto status = buildStatuses.get(clientCode);
        boolean isPos = platform.equalsIgnoreCase("pos");
        boolean generic = GENERIC_KEY.equals(clientCode);
        try {
            Path workspace = Path.of(workspacePath);
            Path backendRoot = workspace.resolve("daman-backend");
            Path frontendRoot = workspace.resolve("daman-frontend");

            addLog(status, "Writing client configuration...");
            ClientConfigExportDto exportDto = generic
                    ? writeGenericConfig(backendRoot, frontendRoot, versionNumber, status)
                    : writeClientConfig(clientCode, backendRoot, frontendRoot, isPos, status);
            addLog(status, "Configuration written");

            addLog(status, "Setting package.json version to " + versionNumber + "...");
            writeFrontendPackageVersion(frontendRoot, versionNumber, status);

            if (!isPos) {
                addLog(status, "Embedding license public key & build metadata...");
                writePublicKey(backendRoot);
                // The generic build's client-meta.properties (client.generic=true, no
                // client.code) is already written by writeGenericConfig — don't stamp
                // a clientCode over it.
                if (!generic) {
                    writeClientMeta(backendRoot, clientCode, versionNumber);
                }
                addLog(status, "License binding written for clientCode=" + clientCode);

                String[] mvnCmd = mavenCommand(backendRoot);
                addLog(status, "Building backend JAR (Maven) using: " + mvnCmd[isWindows() ? 2 : 0]);
                runProcess(backendRoot, mvnCmd, status);
                addLog(status, "Backend built");

                addLog(status, "Copying backend JAR...");
                copyBackendJar(backendRoot, frontendRoot);
                addLog(status, "JAR copied");
            } else {
                addLog(status, "POS build — skipping backend Maven build, JAR copy, and license binding (no bundled backend in this variant)");
            }

            if (!Files.exists(frontendRoot.resolve("node_modules"))) {
                addLog(status, "Installing npm dependencies...");
                runProcess(frontendRoot, shellCommand("npm", "install"), status);
                addLog(status, "Dependencies installed");
            }

            addLog(status, "Building Angular frontend...");
            runProcess(frontendRoot, shellCommand("npx", "ng", "build", "--configuration", "production"), status);
            addLog(status, "Frontend built");

            String platformLabel = switch (platform.toLowerCase()) {
                case "win7" -> "win7 — Electron 22.3.27 (Windows 7/8 compatible)";
                case "winx86" -> "winx86 — 32-bit (x86) Windows build";
                default -> platform;
            };

            // Wipe the platform's own dist dir BEFORE electron-builder runs so we can never
            // pick up a stale installer from a previous client's build.
            String distDirName = isPos ? "dist-electron-pos" : "dist-electron";
            Path distDir = frontendRoot.resolve(distDirName);
            addLog(status, "Cleaning previous Electron output...");
            cleanDirectory(distDir, status);

            addLog(status, "Packaging Electron app (" + platformLabel + ")...");
            runProcess(frontendRoot, electronBuilderCommand(platform), status);
            addLog(status, "Electron app packaged");

            String rawArtifact = findArtifact(frontendRoot, platform);
            if (rawArtifact != null) {
                String finalArtifact = relocateArtifact(
                        rawArtifact, clientCode, exportDto.getAppName(), versionNumber, workspace, platform, status);
                status.setArtifactPath(finalArtifact);
                status.setArtifactName(Path.of(finalArtifact).getFileName().toString());

                // Now that the installer lives in clients-build/, free up disk
                // and ensure no leftovers leak into the next client's build.
                addLog(status, "Cleaning " + distDirName + "...");
                cleanDirectory(distDir, status);
            } else {
                addLog(status, "Warning: no installer artifact found in " + distDirName);
            }

            status.setStatus("SUCCESS");
            status.setFinishedAt(LocalDateTime.now());
            addLog(status, "Build completed successfully");

        } catch (Exception e) {
            status.setStatus("FAILED");
            status.setFinishedAt(LocalDateTime.now());
            addLog(status, "BUILD FAILED: " + e.getMessage());
            log.error("Build failed for client: {}", clientCode, e);
        } finally {
            persistBuildLog(status);
        }
    }

    private String relocateArtifact(String rawPath, String clientCode, String appName,
                                     String versionNumber, Path workspace, String platform,
                                     BuildStatusDto status) throws IOException {
        // Use only ASCII-safe parts for the filename. The previous version derived
        // the leading segment from the (potentially Arabic) appName, which produced
        // ugly "_______" prefixes once non-ASCII characters were stripped. Pin the
        // brand prefix instead so the output is always something like:
        //   Daman_1.0.0_viora.exe (full app) or DamanPOS_1.0.0_viora.exe (POS variant)
        String safeClient = clientCode.replaceAll("[^a-zA-Z0-9_-]", "_");
        String safeVer    = (versionNumber != null && !versionNumber.isBlank())
                ? versionNumber.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "latest";

        Path src  = Path.of(rawPath);
        String originalName = src.getFileName().toString();
        int dotIdx = originalName.lastIndexOf('.');
        String ext = dotIdx >= 0 ? originalName.substring(dotIdx) : "";

        String prefix = platform.equalsIgnoreCase("pos") ? "DamanPOS_" : "Daman_";
        // winx86 (32-bit) shares every other platform's filename otherwise —
        // e.g. building generic as 32-bit after already building it as regular
        // 64-bit win produces the identical "Daman_<ver>_generic.exe" in the
        // same clients-build/generic/ folder, so the second build's
        // REPLACE_EXISTING copy silently overwrites the first. Prefix with
        // "32-" so both artifacts coexist.
        if (platform.equalsIgnoreCase("winx86")) {
            prefix = "32-" + prefix;
        }
        String newName = prefix + safeVer + "_" + safeClient + ext;

        Path outDir = workspace.resolve("clients-build").resolve(safeClient);
        Files.createDirectories(outDir);
        Path dest = outDir.resolve(newName);
        Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        addLog(status, "Artifact saved to: " + dest);

        copyUpdateMetadataIfPresent(src, outDir, status);
        writeChangelog(outDir, versionNumber, status);
        return dest.toString();
    }

    /**
     * electron-updater's "generic" provider (the generic build's Windows
     * auto-update feature) reads latest.yml plus a differential-update
     * .blockmap file from wherever the installer itself is hosted.
     * electron-builder writes both alongside the raw installer in
     * dist-electron/ — but only when package.json's build.publish is
     * configured, and only the caller of relocateArtifact() wipes dist-
     * electron/ right after this method returns, so both would be lost
     * before anyone gets a chance to upload them if not copied here too.
     * Absent (a build whose electron-builder version/config didn't produce
     * them) is not an error — that build simply doesn't support auto-update.
     */
    private void copyUpdateMetadataIfPresent(Path installerSrc, Path outDir, BuildStatusDto status) throws IOException {
        Path distDir = installerSrc.getParent();
        Path blockmapSrc = distDir.resolve(installerSrc.getFileName().toString() + ".blockmap");
        Path latestYmlSrc = distDir.resolve("latest.yml");

        if (Files.exists(blockmapSrc)) {
            Path blockmapDest = outDir.resolve(blockmapSrc.getFileName().toString());
            Files.copy(blockmapSrc, blockmapDest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            addLog(status, "Update metadata saved to: " + blockmapDest);
        }
        if (Files.exists(latestYmlSrc)) {
            Path latestYmlDest = outDir.resolve("latest.yml");
            Files.copy(latestYmlSrc, latestYmlDest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            addLog(status, "Update metadata saved to: " + latestYmlDest);
        }
    }

    private void writeChangelog(Path outDir, String versionNumber, BuildStatusDto status) {
        try {
            var versionOpt = (versionNumber != null && !versionNumber.isBlank())
                    ? appVersionRepository.findByVersionNumber(versionNumber)
                    : appVersionRepository.findTopByOrderByReleaseDateDesc();

            if (versionOpt.isPresent()) {
                var v = versionOpt.get();
                String text = "Version: " + v.getVersionNumber() + "\n"
                        + "Release Date: " + v.getReleaseDate() + "\n\n"
                        + (v.getChangelogText() != null ? v.getChangelogText() : "");
                Files.writeString(outDir.resolve("changelog.txt"), text);
                addLog(status, "changelog.txt written for version " + v.getVersionNumber());
            } else {
                addLog(status, "No version found — changelog.txt skipped");
            }
        } catch (Exception e) {
            addLog(status, "Warning: could not write changelog.txt — " + e.getMessage());
        }
    }

    private void persistBuildLog(BuildStatusDto status) {
        try {
            BuildLog entry = new BuildLog();
            entry.setClientCode(status.getClientCode());
            entry.setPlatform(status.getPlatform());
            entry.setStatus(status.getStatus());
            entry.setArtifactPath(status.getArtifactPath());
            entry.setArtifactName(status.getArtifactName());
            entry.setFinishedAt(status.getFinishedAt());
            entry.setLogs(String.join("\n", status.getLogs()));
            if (status.getStartedAt() != null && status.getFinishedAt() != null) {
                entry.setDurationSeconds(
                    java.time.Duration.between(status.getStartedAt(), status.getFinishedAt()).getSeconds()
                );
            }
            buildLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to persist build log for client: {}", status.getClientCode(), e);
        }
    }

    public List<BuildLog> getBuildHistory(String clientCode) {
        return buildLogRepository.findByClientCodeOrderByStartedAtDesc(resolveKey(clientCode));
    }

    // ------------------------------------------------------------------
    // Config writing
    // ------------------------------------------------------------------

    private ClientConfigExportDto writeClientConfig(String clientCode, Path backendRoot, Path frontendRoot,
                                                     boolean skipBackendCopy, BuildStatusDto status) throws IOException {
        ClientConfigExportDto exportDto = clientConfigService.export(clientCode);

        extractImages(exportDto, clientCode, frontendRoot);

        logEnabledFeatures(status, exportDto);

        String json = objectMapper.writeValueAsString(exportDto);

        if (!skipBackendCopy) {
            Path backendDest = backendRoot.resolve("src/main/resources/client.config.json");
            Files.writeString(backendDest, json);
        }

        Path frontendAssets = frontendRoot.resolve("src/assets");
        Files.createDirectories(frontendAssets);
        Files.writeString(frontendAssets.resolve("client.config.json"), json);
        return exportDto;
    }

    /**
     * Neutral config — no client identity. Reads the checked-in generic files
     * ({@code client.config.generic.json} / {@code client-meta.generic.properties}) from
     * the workspace and writes them over the real names, exactly like the Stage-2
     * generic-installer CI workflow does before packaging. The resulting
     * {@code client-meta.properties} carries {@code client.generic=true} and no
     * {@code client.code=}, so LicenseService accepts any signed, machine-matched,
     * non-expired licence at activation.
     */
    private ClientConfigExportDto writeGenericConfig(Path backendRoot, Path frontendRoot,
                                                     String versionNumber, BuildStatusDto status) throws IOException {
        Path genConfigSrc = frontendRoot.resolve("src/assets/client.config.generic.json");
        Path genMetaSrc   = backendRoot.resolve("src/main/resources/client-meta.generic.properties");
        if (!Files.isRegularFile(genConfigSrc) || !Files.isRegularFile(genMetaSrc)) {
            throw new IOException("Generic build config missing: " + genConfigSrc + " / " + genMetaSrc);
        }

        String json = Files.readString(genConfigSrc);
        Files.writeString(backendRoot.resolve("src/main/resources/client.config.json"), json);
        Path frontendAssets = frontendRoot.resolve("src/assets");
        Files.createDirectories(frontendAssets);
        Files.writeString(frontendAssets.resolve("client.config.json"), json);

        String safeVer = (versionNumber != null && !versionNumber.isBlank()) ? versionNumber : "unknown";
        String meta = Files.readString(genMetaSrc).trim()
                + "\nclient.version=" + safeVer
                + "\nclient.builtAt=" + LocalDateTime.now() + "\n";
        Files.writeString(backendRoot.resolve("src/main/resources/client-meta.properties"), meta);

        // The generic config references these brand assets by name. A missing asset is
        // cosmetic (Angular tolerates it; the installer just gets the default icon) —
        // warn, don't fail. favicon.ico is currently expected to be absent.
        for (String rel : new String[]{"brand/logo.png", "brand/logo-light.png", "favicon.ico"}) {
            if (!Files.isRegularFile(frontendAssets.resolve(rel))) {
                addLog(status, "WARNING: generic brand asset " + rel + " not found at "
                        + frontendAssets.resolve(rel));
            }
        }

        addLog(status, "Generic build — neutral client.config.json + client.generic=true meta written");

        return ClientConfigExportDto.builder().appName("Daman").build();   // appName only used for artifact naming
    }

    private void logEnabledFeatures(BuildStatusDto status, ClientConfigExportDto exportDto) {
        if (exportDto.getFeatures() == null) {
            addLog(status, "Enabled features: (none)");
            return;
        }
        List<String> enabled = new ArrayList<>();
        if (exportDto.getFeatures().isMultiLanguage()) {
            enabled.add("multiLanguage");
        }
        if (exportDto.getFeatures().isBarcode()) {
            enabled.add("barcode");
        }
        addLog(status, "Enabled features: " + (enabled.isEmpty() ? "(none)" : String.join(", ", enabled)));
    }

    private void writePublicKey(Path backendRoot) throws IOException {
        String publicKeyPem = licenseKeyService.getPublicKeyPem();
        Path dest = backendRoot.resolve("src/main/resources/license-public.pem");
        Files.writeString(dest, publicKeyPem);
    }

    /**
     * Bind this build to a specific {@code clientCode} so the runtime license
     * check can reject licenses that were issued for a different client.
     * The file is written into the JAR's classpath resources, so an attacker
     * cannot swap clientCodes by simply replacing a file on disk — they would
     * have to repack the fat JAR. Combined with the per-machine signed
     * license, this prevents the "copy User A's binary to User B's PC and
     * activate with B's own license" attack.
     */
    private void writeClientMeta(Path backendRoot, String clientCode, String versionNumber) throws IOException {
        Path dest = backendRoot.resolve("src/main/resources/client-meta.properties");
        String safeVer = (versionNumber != null && !versionNumber.isBlank()) ? versionNumber : "unknown";
        // Plain Properties format — escape backslashes just in case.
        String body = String.join("\n",
                "# Auto-generated by BuildService — do not edit by hand.",
                "# Used by LicenseService to bind this binary to a specific client.",
                "client.code=" + clientCode,
                "client.version=" + safeVer,
                "client.builtAt=" + LocalDateTime.now()
        ) + "\n";
        Files.writeString(dest, body);
    }

    /**
     * Patches daman-frontend/package.json's top-level "version" field to this build's
     * versionNumber. Electron's {@code app.getVersion()} reads that field at runtime —
     * it's what main.js forwards to the renderer as {@code window.daman.appVersion} for
     * the topbar badge and what the Help > About dialog shows — so without this the
     * installed app's own version display would silently lag behind whatever the admin
     * dashboard's build modal was told to use for the artifact filename/changelog.
     * A regex on the first {@code "version": "..."} line (JSON conventionally emits
     * "name"/"version" first) is used instead of a full JSON round-trip, so the rest of
     * the file's formatting/key order is left untouched.
     */
    private void writeFrontendPackageVersion(Path frontendRoot, String versionNumber, BuildStatusDto status) throws IOException {
        if (versionNumber == null || versionNumber.isBlank()) {
            addLog(status, "No version number given — package.json version left unchanged");
            return;
        }
        Path pkgJson = frontendRoot.resolve("package.json");
        String content = Files.readString(pkgJson);
        String escaped = versionNumber.replace("\\", "\\\\").replace("\"", "\\\"");
        String updated = content.replaceFirst(
                "\"version\"\\s*:\\s*\"[^\"]*\"",
                Matcher.quoteReplacement("\"version\": \"" + escaped + "\"")
        );
        if (updated.equals(content)) {
            addLog(status, "WARNING: could not find \"version\" field in package.json — left unchanged");
            return;
        }
        Files.writeString(pkgJson, updated);
    }

    private void extractImages(ClientConfigExportDto config, String clientCode, Path frontendRoot) throws IOException {
        Path brandDir = frontendRoot.resolve("src/assets/brand");
        Files.createDirectories(brandDir);

        if (config.getLogo() != null) {
            String dark = config.getLogo().getDark();
            if (isDataUrl(dark)) {
                String name = clientCode + "-logo-dark." + extFromDataUrl(dark);
                writeBase64ToFile(dark, brandDir.resolve(name));
                config.getLogo().setDark("assets/brand/" + name);
            }

            String light = config.getLogo().getLight();
            if (isDataUrl(light)) {
                String name = clientCode + "-logo-light." + extFromDataUrl(light);
                writeBase64ToFile(light, brandDir.resolve(name));
                config.getLogo().setLight("assets/brand/" + name);
            }
        }

        if (isDataUrl(config.getFavicon())) {
            String name = clientCode + "-favicon." + extFromDataUrl(config.getFavicon());
            writeBase64ToFile(config.getFavicon(), brandDir.resolve(name));
            config.setFavicon("assets/brand/" + name);
        }
    }

    private boolean isDataUrl(String value) {
        return value != null && value.startsWith("data:");
    }

    private String extFromDataUrl(String dataUrl) {
        Matcher m = DATA_URL_PATTERN.matcher(dataUrl);
        return m.matches() ? MIME_TO_EXT.getOrDefault(m.group(1), "png") : "png";
    }

    private void writeBase64ToFile(String dataUrl, Path dest) throws IOException {
        Matcher m = DATA_URL_PATTERN.matcher(dataUrl);
        if (m.matches()) {
            Files.write(dest, Base64.getDecoder().decode(m.group(2)));
        }
    }

    // ------------------------------------------------------------------
    // Process execution
    // ------------------------------------------------------------------

    private String[] mavenCommand(Path backendRoot) {
        boolean isWin = isWindows();
        String mvnw = isWin ? "mvnw.cmd" : "mvnw";
        Path mvnwPath = backendRoot.resolve(mvnw);
        Path wrapperProps = backendRoot.resolve(".mvn/wrapper/maven-wrapper.properties");

        // Use the project's Maven wrapper only when both the script and its
        // properties file are present. Otherwise fall back to a system-wide
        // `mvn` binary so a missing/corrupted .mvn folder doesn't break builds.
        boolean wrapperUsable = Files.isRegularFile(mvnwPath) && Files.isRegularFile(wrapperProps);

        String executable = wrapperUsable
                ? mvnwPath.toString()
                : (isWin ? "mvn.cmd" : "mvn");

        // -Pdesktop-client excludes the H2/PostgreSQL JDBC drivers from the fat
        // jar (see daman-backend/pom.xml) — every build this service produces
        // ships as a desktop client installer, which only ever connects via
        // SQLite, so bundling either driver is pure dead weight in the .exe.
        if (isWin) {
            return new String[]{"cmd.exe", "/c", executable, "clean", "package", "-DskipTests", "-Pdesktop-client"};
        }
        return new String[]{executable, "clean", "package", "-DskipTests", "-Pdesktop-client"};
    }

    private String[] shellCommand(String... args) {
        if (isWindows()) {
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.addAll(Arrays.asList(args));
            return cmd.toArray(new String[0]);
        }
        return args;
    }

    private String[] electronBuilderCommand(String platform) {
        List<String> args = new ArrayList<>();
        args.add("npx");
        args.add("electron-builder");

        switch (platform.toLowerCase()) {
            case "mac" -> args.add("--mac");
            case "linux" -> args.add("--linux");
            case "win7" -> {
                // Windows 7/8 build: override Electron version to 22.x via inline config flag.
                // electron-builder downloads its own binary based on this version — no package.json change needed.
                args.add("--win");
                args.add("--config.electronVersion=22.3.27");
                args.add("--publish");
                args.add("never");
            }
            case "winx86" -> {
                // 32-bit-only Windows build: a separate electron-builder config
                // (electron-builder.win-x86.json) declaring arch: ["ia32"] only —
                // NOT the --ia32 CLI flag against the main package.json config.
                // Verified empirically: electron-builder's --ia32/--x64 CLI flags
                // ADD that architecture to whatever's already going to be built
                // rather than replacing the config's declared arch list, so
                // "--win --ia32" against a config that already lists x64 (or
                // defaults to it) produces a combined x64+ia32 installer, not
                // an ia32-only one — same mistake package.json's own win.target
                // briefly made (arch: ["x64","ia32"] as the *default* for every
                // plain "win" build, silently doubling every Windows installer's
                // size, JRE included, whether or not anyone asked for 32-bit).
                // A dedicated config, same shape as electron-builder.pos.json's
                // precedent, is the only reliable way to get an exclusively
                // 32-bit payload. Needs jre/win-ia32 (a real 32-bit JRE) present
                // in the frontend checkout, matching this config's jre/win-${arch}
                // templating.
                //
                // Pinned to the same Electron 22.3.27 as "win7": a genuinely
                // 32-bit-only CPU is realistically old hardware that may well
                // also be stuck on Windows 7/8 (Electron 23+ dropped Windows
                // 7/8 support entirely, independent of CPU architecture), and
                // 22.3.27 still publishes a win32-ia32 build, so pinning it
                // here costs nothing on newer Windows while covering the
                // oldest machines too.
                args.add("--config");
                args.add("electron-builder.win-x86.json");
                args.add("--win");
                args.add("--config.electronVersion=22.3.27");
                args.add("--publish");
                args.add("never");
            }
            case "pos" -> {
                // POS build: a separate electron-builder config (different appId/productName,
                // no bundled backend jar/JRE, output dir dist-electron-pos). --win is explicit
                // even though the config only defines a Windows target, so this never silently
                // depends on electron-builder's host-OS auto-detection.
                args.add("--config");
                args.add("electron-builder.pos.json");
                args.add("--win");
                args.add("--publish");
                args.add("never");
            }
            default -> { args.add("--win"); args.add("--publish"); args.add("never"); }
        }

        return shellCommand(args.toArray(new String[0]));
    }

    private void runProcess(Path workDir, String[] command, BuildStatusDto status)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        String javaHome = resolveJavaHome();

        StringBuilder pathBuilder = new StringBuilder();
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome);
            pathBuilder.append(Path.of(javaHome, "bin")).append(File.pathSeparator);
        }

        if (isWindows()) {
            String systemRoot = env.getOrDefault("SystemRoot",
                    System.getenv("SystemRoot") != null ? System.getenv("SystemRoot") : "C:\\Windows");
            env.putIfAbsent("SystemRoot", systemRoot);
            pathBuilder.append(systemRoot).append("\\System32").append(File.pathSeparator)
                       .append(systemRoot).append("\\System32\\WindowsPowerShell\\v1.0").append(File.pathSeparator);
        }

        String existingPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        pathBuilder.append(existingPath);
        env.put("PATH", pathBuilder.toString());

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                addLog(status, line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Process exited with code " + exitCode);
        }
    }

    private String resolveJavaHome() {
        String home = System.getenv("DAMAN_JAVA_HOME");
        if (home != null && !home.isBlank()) return home;

        home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) return home;

        return System.getProperty("java.home");
    }

    // ------------------------------------------------------------------
    // Artifact helpers
    // ------------------------------------------------------------------

    /**
     * Recursively deletes the contents of {@code dir} (the directory itself is preserved).
     * Failures on individual files are logged but never thrown — a locked file from a
     * previous run (e.g. an installer the user is still running) shouldn't kill the build.
     */
    private void cleanDirectory(Path dir, BuildStatusDto status) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walker = Files.walk(dir)) {
            walker.sorted(Comparator.reverseOrder())
                  .filter(p -> !p.equals(dir))
                  .forEach(p -> {
                      try {
                          Files.deleteIfExists(p);
                      } catch (IOException ex) {
                          addLog(status, "  could not delete " + p.getFileName() + ": " + ex.getMessage());
                      }
                  });
        } catch (IOException e) {
            addLog(status, "Warning: could not clean " + dir + ": " + e.getMessage());
        }
    }

    private void copyBackendJar(Path backendRoot, Path frontendRoot) throws IOException {
        // Maven names the jar after pom.xml's <version> (daman-backend-1.0.1.jar,
        // daman-backend-0.0.1-SNAPSHOT.jar, ...) — glob instead of hardcoding one
        // exact filename, so a version bump doesn't break every future build the
        // way it just did here (hardcoded to the pre-2026-08-22 0.0.1-SNAPSHOT
        // name). Same fix as daman-frontend/electron/main.js's resolveBackendJarPath().
        Path targetDir = backendRoot.resolve("target");
        Path src;
        try (var stream = Files.list(targetDir)) {
            src = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.matches("daman-backend-.*\\.jar")
                                && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
                                && !name.endsWith(".jar.original");
                    })
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Backend JAR not found in " + targetDir));
        }
        Path destDir = frontendRoot.resolve("backend");
        Files.createDirectories(destDir);
        Files.copy(src, destDir.resolve("daman-backend.jar"), StandardCopyOption.REPLACE_EXISTING);
    }

    private String findArtifact(Path frontendRoot, String platform) {
        String distDirName = platform.equalsIgnoreCase("pos") ? "dist-electron-pos" : "dist-electron";
        Path distDir = frontendRoot.resolve(distDirName);
        if (!Files.exists(distDir)) return null;

        String ext = switch (platform.toLowerCase()) {
            case "mac" -> ".dmg";
            case "linux" -> ".AppImage";
            default -> ".exe"; // covers "win", "win7", and "pos"
        };

        try (Stream<Path> files = Files.list(distDir)) {
            // Pick the most recently modified matching file. This protects
            // against any stale installer that managed to survive cleanDirectory().
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(ext))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public Path getOutputDir(String clientCode) {
        if (workspacePath == null || workspacePath.isBlank()) return null;
        String safeClient = resolveKey(clientCode).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        Path dir = Path.of(workspacePath).resolve("clients-build").resolve(safeClient);
        return Files.exists(dir) ? dir : null;
    }

    public Path getArtifactFile(String rawClientCode) {
        String clientCode = resolveKey(rawClientCode);
        // First check in-memory status (current/recent build)
        BuildStatusDto status = buildStatuses.get(clientCode);
        if (status != null && status.getArtifactPath() != null) {
            Path path = Path.of(status.getArtifactPath());
            if (Files.exists(path)) return path;
        }
        // Fall back to latest successful build log
        return buildLogRepository.findByClientCodeOrderByStartedAtDesc(clientCode).stream()
                .filter(l -> "SUCCESS".equals(l.getStatus()) && l.getArtifactPath() != null)
                .findFirst()
                .map(l -> Path.of(l.getArtifactPath()))
                .filter(Files::exists)
                .orElse(null);
    }

    // ------------------------------------------------------------------

    private void addLog(BuildStatusDto status, String message) {
        String ts = LocalDateTime.now().format(LOG_FMT);
        status.getLogs().add("[" + ts + "] " + message);
        log.info("[Build:{}] {}", status.getClientCode(), message);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
