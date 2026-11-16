package com.daman.admin.service;

import com.daman.admin.dto.BuildStatusDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Publishes the phone till's APK: R2, then the public download page.
 *
 * A thin driver over {@code daman-admin/deploy-mobile-release.ps1} rather than a
 * second implementation of it. The script is the one place that knows the bucket,
 * the verification and the website rewrite, and it is also runnable by hand when
 * the dashboard is not; two copies of that would drift and only one of them would
 * be the one that ran.
 *
 * What this class adds is the refusals, made before anything is uploaded and
 * before a public repository is pushed. They are cheap here and expensive later:
 * an unsigned APK on the website is a download that fails on every phone that
 * tries it, and reads to a shop as a broken app rather than a missing keystore.
 */
@Slf4j
@Service
public class MobileDeployService {

    /** Digits and dots only. It becomes a filename and then a command-line argument. */
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)+");

    private static final String SCRIPT = "deploy-mobile-release.ps1";

    @Value("${daman.workspace:}")
    private String workspacePath;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile BuildStatusDto status = BuildStatusDto.builder()
            .clientCode("generic")
            .platform("android")
            .status("IDLE")
            .build();

    public BuildStatusDto getStatus() {
        return status;
    }

    /**
     * Everything that can be checked before anything leaves this machine.
     *
     * Returns the APK that would be published. Throws rather than returning a
     * verdict object, because every caller's only sensible response is to stop
     * and show the message.
     */
    public Path preflight(String version) {
        if (version == null || !VERSION.matcher(version.trim()).matches()) {
            throw new IllegalArgumentException(
                    "Version must be digits and dots, like 1.5.1 - got: " + version);
        }
        String v = version.trim();

        if (workspacePath == null || workspacePath.isBlank()) {
            throw new IllegalStateException("daman.workspace is not configured in application.yml");
        }
        Path workspace = Path.of(workspacePath);

        Path script = workspace.resolve("daman-admin").resolve(SCRIPT);
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("Deploy script not found: " + script
                    + " - the dashboard drives " + SCRIPT + " rather than reimplementing it.");
        }

        Path dir = workspace.resolve("clients-build").resolve("generic");
        Path apk = dir.resolve("Daman_" + v + "_generic.apk");
        if (Files.isRegularFile(apk)) {
            return apk;
        }

        // Naming the unsigned build separately is the whole point of carrying its
        // marker through the rename - see BuildService.relocateArtifact.
        Path unsigned = dir.resolve("Daman_" + v + "_generic-unsigned.apk");
        if (Files.isRegularFile(unsigned)) {
            throw new IllegalStateException("Only an unsigned APK exists for " + v + " ("
                    + unsigned.getFileName() + "). It installs on no phone, so it is not"
                    + " publishable. Set damanKeystoreFile, damanKeystorePassword, damanKeyAlias"
                    + " and damanKeyPassword in ~/.gradle/gradle.properties and build again - see"
                    + " Docs/superpowers/runbooks/android-build.md.");
        }

        throw new IllegalStateException("No APK built for " + v + " in " + dir
                + " - build it first (Build -> Android (.apk)).");
    }

    /**
     * The command that runs the script.
     *
     * {@code -NoProfile} because the owner's PowerShell profile is not part of a
     * release, and {@code -ExecutionPolicy Bypass} because the script is a file in
     * this repository rather than something downloaded.
     */
    public String[] deployCommand(String version) {
        Path script = Path.of(workspacePath).resolve("daman-admin").resolve(SCRIPT);
        return new String[]{
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-Version", version.trim()
        };
    }

    /**
     * Uploads the APK and updates the website. Returns as soon as it has started.
     *
     * Single-flight: two deploys at once would race on index.html and on a git
     * push, and the loser would leave the repository half-written.
     */
    public BuildStatusDto startDeploy(String version) {
        if ("DEPLOYING".equals(status.getStatus())) {
            throw new IllegalStateException("A deploy is already running.");
        }
        Path apk = preflight(version);

        BuildStatusDto fresh = BuildStatusDto.builder()
                .clientCode("generic")
                .platform("android")
                .status("DEPLOYING")
                .startedAt(LocalDateTime.now())
                .artifactPath(apk.toString())
                .artifactName(apk.getFileName().toString())
                .logs(Collections.synchronizedList(new ArrayList<>()))
                .build();
        status = fresh;

        executor.submit(() -> run(fresh, version.trim()));
        return fresh;
    }

    private void run(BuildStatusDto s, String version) {
        try {
            addLog(s, "Deploying " + s.getArtifactName() + " to dl.damansoft.com and the website...");
            runProcess(Path.of(workspacePath).resolve("daman-admin"), deployCommand(version), s);
            s.setStatus("SUCCESS");
            addLog(s, "Deploy finished. The website is live once GitHub Pages redeploys.");
        } catch (Exception e) {
            s.setStatus("FAILED");
            addLog(s, "DEPLOY FAILED: " + e.getMessage());
            log.error("Mobile deploy failed for version {}", version, e);
        } finally {
            s.setFinishedAt(LocalDateTime.now());
        }
    }

    private void runProcess(Path workDir, String[] command, BuildStatusDto s)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        String systemRoot = env.getOrDefault("SystemRoot",
                System.getenv("SystemRoot") != null ? System.getenv("SystemRoot") : "C:\\Windows");
        env.putIfAbsent("SystemRoot", systemRoot);
        env.put("PATH", systemRoot + "\\System32" + File.pathSeparator
                + systemRoot + "\\System32\\WindowsPowerShell\\v1.0" + File.pathSeparator
                + env.getOrDefault("PATH", env.getOrDefault("Path", "")));

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                addLog(s, line);
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(SCRIPT + " exited with code " + exit);
        }
    }

    private void addLog(BuildStatusDto s, String message) {
        log.info("[Deploy:android] {}", message);
        s.getLogs().add(message);
    }
}
