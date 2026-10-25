package com.daman.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The real {@link ServerOps}: the only class in the hosted-store feature that runs a command,
 * writes outside the application's own data directory or opens a socket. Everything it does
 * assumes the Linux box admin-backend runs on as root; {@link #availableMemoryMb()} fails loudly
 * on any other platform rather than letting a dev machine pretend it could provision a store.
 *
 * <p>No command is ever built by string concatenation and no shell is involved: arguments are
 * passed as a list, and every service name is checked against {@link #SAFE_UNIT_NAME} first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemdServerOps implements ServerOps {

    /** No path separators, no "%" (systemd expands specifiers), no whitespace, no CR/LF. */
    private static final Pattern SAFE_UNIT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long COMMAND_TIMEOUT_SECONDS = 60;

    private final ObjectMapper mapper;

    @Value("${daman.hosted.unit-dir:/etc/systemd/system}")
    private String unitDir;

    @Value("${daman.hosted.ingress-file:/etc/cloudflared/config.yml}")
    private String ingressFile;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    @Override
    public void mkdir(String path) {
        try {
            Files.createDirectories(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException("could not create directory " + path, e);
        }
    }

    @Override
    public void writeUnit(String serviceName, String unitText) {
        requireSafeUnitName(serviceName);
        Path unit = Path.of(unitDir, serviceName + ".service");
        writeLf(unit, unitText);
        // The unit carries this store's JWT signing secret and /etc/systemd/system is
        // world-readable by default. systemd reads units as root, so 0600 costs nothing.
        restrictToOwner(unit);
        log.info("Wrote systemd unit {}", unit);
    }

    @Override
    public void daemonReloadAndStart(String serviceName) {
        requireSafeUnitName(serviceName);
        run("systemctl", "daemon-reload");
        run("systemctl", "enable", "--now", serviceName);
    }

    @Override
    public void restart(String serviceName) {
        requireSafeUnitName(serviceName);
        run("systemctl", "restart", serviceName);
    }

    @Override
    public String readIngress() {
        try {
            return Files.readString(Path.of(ingressFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + ingressFile, e);
        }
    }

    @Override
    public void writeIngress(String yaml) {
        writeLf(Path.of(ingressFile), yaml);
        log.info("Rewrote cloudflared ingress {}", ingressFile);
    }

    @Override
    public void reloadCloudflared() {
        // SIGHUP, not "systemctl restart": cloudflared re-reads its ingress list on SIGHUP while
        // keeping the tunnel up. A restart would briefly take admin-api and viora offline — and
        // the provisioning request being served right now arrives through that same tunnel.
        run("systemctl", "kill", "-s", "HUP", "cloudflared");
    }

    @Override
    public Optional<String> probeMachineId(int port) {
        Optional<String> body = get(port, "/api/license/status");
        if (body.isEmpty()) {
            return Optional.empty();
        }
        try {
            String machineId = mapper.readTree(body.get()).path("machineId").asString("");
            return machineId.isBlank() ? Optional.empty() : Optional.of(machineId);
        } catch (RuntimeException e) {
            log.warn("store on port {} returned an unreadable /api/license/status body", port);
            return Optional.empty();
        }
    }

    @Override
    public boolean health(int port) {
        return get(port, "/api/health").isPresent();
    }

    @Override
    public void activateLicence(int port, String licenseKey) {
        String body = mapper.writeValueAsString(Map.of("licenseKey", licenseKey));
        HttpRequest request = HttpRequest.newBuilder(localUri(port, "/api/license/activate"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("store on port " + port + " refused the licence (HTTP "
                        + response.statusCode() + "): " + response.body());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not activate the licence on port " + port, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted activating the licence on port " + port, e);
        }
    }

    @Override
    public long availableMemoryMb() {
        Path meminfo = Path.of("/proc/meminfo");
        try {
            for (String line : Files.readAllLines(meminfo, StandardCharsets.UTF_8)) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / 1024;
                }
            }
            throw new IllegalStateException(meminfo + " has no MemAvailable line");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + meminfo
                    + " — hosted stores can only be provisioned on the Linux server", e);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static void requireSafeUnitName(String serviceName) {
        if (serviceName == null || !SAFE_UNIT_NAME.matcher(serviceName).matches()) {
            throw new IllegalArgumentException("Unsafe systemd service name: " + serviceName);
        }
    }

    /**
     * Writes {@code text} with LF line endings, explicitly, through a temp file in the same
     * directory so a half-written unit or ingress file can never be left behind.
     *
     * <p>The LF conversion is not belt-and-braces: this code is developed on Windows, where a
     * text-mode writer or {@code System.lineSeparator()} would reintroduce CR, and systemd
     * rejects a unit containing one.
     */
    private static void writeLf(Path target, String text) {
        byte[] bytes = text.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
        Path tmp = target.resolveSibling(target.getFileName() + ".daman-tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + target, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.warn("could not remove temp file {} — {}", tmp, e.getMessage());
            }
        }
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            log.warn("could not restrict permissions on {} — {}", file, e.getMessage());
        }
    }

    /** Runs a command with no shell, failing loudly on a non-zero exit or a timeout. */
    private static void run(String... command) {
        String printable = String.join(" ", command);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("timed out after " + COMMAND_TIMEOUT_SECONDS + "s: " + printable);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(printable + " exited " + process.exitValue()
                        + (output.isEmpty() ? "" : ": " + output));
            }
            log.info("ran: {}", printable);
        } catch (IOException e) {
            throw new UncheckedIOException("could not run " + printable, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + printable, e);
        }
    }

    /** The response body of a 200, or empty for anything else — a store that is still booting. */
    private Optional<String> get(int port, String path) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(localUri(port, path)).timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static URI localUri(int port, String path) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
