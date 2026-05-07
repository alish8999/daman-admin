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

    private final Map<String, BuildStatusDto> buildStatuses = new ConcurrentHashMap<>();
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor();

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
        return buildStatuses.getOrDefault(clientCode,
                BuildStatusDto.builder()
                        .clientCode(clientCode)
                        .status("IDLE")
                        .build());
    }

    public BuildStatusDto startBuild(String clientCode, String platform, String versionNumber) {
        if (workspacePath == null || workspacePath.isBlank()) {
            throw new IllegalStateException("daman.workspace is not configured in application.yml");
        }

        BuildStatusDto current = buildStatuses.get(clientCode);
        if (current != null && "BUILDING".equals(current.getStatus())) {
            throw new IllegalStateException("Build already in progress for client: " + clientCode);
        }

        clientConfigRepository.findByClientCode(clientCode)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientCode));

        BuildStatusDto status = BuildStatusDto.builder()
                .clientCode(clientCode)
                .platform(platform)
                .status("BUILDING")
                .startedAt(LocalDateTime.now())
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();

        buildStatuses.put(clientCode, status);

        buildExecutor.submit(() -> executeBuild(clientCode, platform, versionNumber));

        return status;
    }

    private void executeBuild(String clientCode, String platform, String versionNumber) {
        BuildStatusDto status = buildStatuses.get(clientCode);
        try {
            Path workspace = Path.of(workspacePath);
            Path backendRoot = workspace.resolve("daman-backend");
            Path frontendRoot = workspace.resolve("daman-frontend");

            addLog(status, "Writing client configuration...");
            ClientConfigExportDto exportDto = writeClientConfig(clientCode, backendRoot, frontendRoot, status);
            addLog(status, "Configuration written");

            addLog(status, "Embedding license public key...");
            writePublicKey(backendRoot);
            addLog(status, "Public key embedded");

            addLog(status, "Building backend JAR (Maven)...");
            runProcess(backendRoot, mavenCommand(backendRoot), status);
            addLog(status, "Backend built");

            addLog(status, "Copying backend JAR...");
            copyBackendJar(backendRoot, frontendRoot);
            addLog(status, "JAR copied");

            if (!Files.exists(frontendRoot.resolve("node_modules"))) {
                addLog(status, "Installing npm dependencies...");
                runProcess(frontendRoot, shellCommand("npm", "install"), status);
                addLog(status, "Dependencies installed");
            }

            addLog(status, "Building Angular frontend...");
            runProcess(frontendRoot, shellCommand("npx", "ng", "build", "--configuration", "production"), status);
            addLog(status, "Frontend built");

            String platformLabel = platform.equalsIgnoreCase("win7")
                    ? "win7 — Electron 22.3.27 (Windows 7/8 compatible)"
                    : platform;
            addLog(status, "Packaging Electron app (" + platformLabel + ")...");
            runProcess(frontendRoot, electronBuilderCommand(platform), status);
            addLog(status, "Electron app packaged");

            String rawArtifact = findArtifact(frontendRoot, platform);
            if (rawArtifact != null) {
                String finalArtifact = relocateArtifact(
                        rawArtifact, clientCode, exportDto.getAppName(), versionNumber, workspace, status);
                status.setArtifactPath(finalArtifact);
                status.setArtifactName(Path.of(finalArtifact).getFileName().toString());
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
                                     String versionNumber, Path workspace, BuildStatusDto status) throws IOException {
        String safeClient = clientCode.replaceAll("[^a-zA-Z0-9_-]", "_");
        String safeApp    = appName   != null ? appName.replaceAll("[^a-zA-Z0-9_-]", "_") : "App";
        String safeVer    = (versionNumber != null && !versionNumber.isBlank()) ? versionNumber : "latest";

        Path src  = Path.of(rawPath);
        String originalName = src.getFileName().toString();
        int dotIdx = originalName.lastIndexOf('.');
        String ext = dotIdx >= 0 ? originalName.substring(dotIdx) : "";

        String newName = safeApp + "_v" + safeVer + "_" + safeClient + ext;

        Path outDir = workspace.resolve("clients-build").resolve(safeClient);
        Files.createDirectories(outDir);
        Path dest = outDir.resolve(newName);
        Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        addLog(status, "Artifact saved to: " + dest);

        writeChangelog(outDir, versionNumber, status);
        return dest.toString();
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
        return buildLogRepository.findByClientCodeOrderByStartedAtDesc(clientCode);
    }

    // ------------------------------------------------------------------
    // Config writing
    // ------------------------------------------------------------------

    private ClientConfigExportDto writeClientConfig(String clientCode, Path backendRoot, Path frontendRoot,
                                                     BuildStatusDto status) throws IOException {
        ClientConfigExportDto exportDto = clientConfigService.export(clientCode);

        extractImages(exportDto, clientCode, frontendRoot);

        logEnabledFeatures(status, exportDto);

        String json = objectMapper.writeValueAsString(exportDto);

        Path backendDest = backendRoot.resolve("src/main/resources/client.config.json");
        Files.writeString(backendDest, json);

        Path frontendAssets = frontendRoot.resolve("src/assets");
        Files.createDirectories(frontendAssets);
        Files.writeString(frontendAssets.resolve("client.config.json"), json);
        return exportDto;
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
        String mvnwPath = backendRoot.resolve(mvnw).toString();

        if (isWin) {
            return new String[]{"cmd.exe", "/c", mvnwPath, "clean", "package", "-DskipTests"};
        }
        return new String[]{mvnwPath, "clean", "package", "-DskipTests"};
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

    private void copyBackendJar(Path backendRoot, Path frontendRoot) throws IOException {
        Path src = backendRoot.resolve("target/daman-backend-0.0.1-SNAPSHOT.jar");
        if (!Files.exists(src)) {
            throw new RuntimeException("Backend JAR not found at " + src);
        }
        Path destDir = frontendRoot.resolve("backend");
        Files.createDirectories(destDir);
        Files.copy(src, destDir.resolve("daman-backend.jar"), StandardCopyOption.REPLACE_EXISTING);
    }

    private String findArtifact(Path frontendRoot, String platform) {
        Path distDir = frontendRoot.resolve("dist-electron");
        if (!Files.exists(distDir)) return null;

        String ext = switch (platform.toLowerCase()) {
            case "mac" -> ".dmg";
            case "linux" -> ".AppImage";
            default -> ".exe"; // covers "win" and "win7"
        };

        try (Stream<Path> files = Files.list(distDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(ext))
                    .findFirst()
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public Path getArtifactFile(String clientCode) {
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
