package com.daman.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    /** A DNS hostname and nothing else: no scheme, no port, no path, no userinfo, no CR/LF. */
    private static final Pattern SAFE_PUBLIC_HOSTNAME =
            Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PORT_PROBE_TIMEOUT = Duration.ofMillis(500);
    private static final long COMMAND_TIMEOUT_SECONDS = 60;
    /** 10 checks, 3 s apart: ~30 s for cloudflared to report active after a restart. */
    private static final int TUNNEL_ATTEMPTS = 10;
    private static final Duration TUNNEL_POLL_DELAY = Duration.ofSeconds(3);

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
        // ownerOnly: the unit carries this store's JWT signing secret and /etc/systemd/system is
        // world-readable by default. systemd reads units as root, so 0600 costs nothing. The
        // permission is applied to the temp file BEFORE it is moved into place, so the secret is
        // never once visible at the unit's real path with the default 0644 — and a chmod that
        // fails throws, rather than leaving a readable secret behind and carrying on.
        writeLf(unit, unitText, true);
        log.info("Wrote systemd unit {} (rw-------)", unit);
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
    public void stopAndDisable(String serviceName) {
        requireSafeUnitName(serviceName);
        run("systemctl", "disable", "--now", serviceName);
    }

    @Override
    public void enableAndStart(String serviceName) {
        requireSafeUnitName(serviceName);
        run("systemctl", "enable", "--now", serviceName);
    }

    @Override
    public void deleteUnit(String serviceName) {
        requireSafeUnitName(serviceName);
        Path unit = Path.of(unitDir, serviceName + ".service");
        try {
            Files.deleteIfExists(unit);
        } catch (IOException e) {
            throw new UncheckedIOException("could not delete " + unit, e);
        }
        log.info("Deleted systemd unit {}", unit);
        run("systemctl", "daemon-reload");
    }

    @Override
    public boolean unitActive(String serviceName) {
        requireSafeUnitName(serviceName);
        return isActive(serviceName);
    }

    @Override
    public Optional<Instant> unitStartedAt(String serviceName) {
        requireSafeUnitName(serviceName);
        String value = runCapture("systemctl", "show", "-p", "ActiveEnterTimestamp", "--value", serviceName);
        return parseSystemdTimestamp(value);
    }

    @Override
    public Optional<Instant> fileModifiedAt(String path) {
        try {
            Path file = Path.of(path);
            return Files.exists(file) ? Optional.of(Files.getLastModifiedTime(file).toInstant()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<BackupInfo> newestBackup(String dir) {
        Path directory = Path.of(dir);
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> {
                        try {
                            return new BackupInfo(f.getFileName().toString(), Files.size(f),
                                    Files.getLastModifiedTime(f).toInstant());
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(b -> b != null)
                    .max(Comparator.comparing(BackupInfo::modifiedAt));
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + dir, e);
        }
    }

    @Override
    public void zipFileInto(String sourceFile, String targetZip, String entryName) {
        Path source = Path.of(sourceFile);
        Path target = Path.of(targetZip);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(target);
                 ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(source, zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            // Never leave a truncated archive that could pass for a real backup.
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // the original failure is the one worth reporting
            }
            throw new UncheckedIOException("could not zip " + sourceFile + " into " + targetZip, e);
        }
    }

    @Override
    public void deleteTree(String path, String clientCode) {
        if (!StorePaths.isAllowedDataDir(path, clientCode)) {
            throw new IllegalArgumentException("Refusing to delete " + path + " for client " + clientCode);
        }
        Path root = Path.of(path);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            // Deepest first. Files.walk does not follow symlinks, and Files.delete removes a link
            // itself rather than its target.
            for (Path p : (Iterable<Path>) tree.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not delete " + path, e);
        }
        log.info("Deleted data directory {}", path);
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
        // Not ownerOnly: the ingress file is shared, hand-maintained and carries no secret, so its
        // existing mode is left exactly as the operator set it.
        writeLf(Path.of(ingressFile), yaml, false);
        log.info("Rewrote cloudflared ingress {}", ingressFile);
    }

    /**
     * Restarts cloudflared and then proves it came back; throws if it did not.
     *
     * <p><b>Never send SIGHUP to cloudflared.</b> On 2026-10-25 this method ran
     * {@code systemctl kill -s HUP cloudflared}, expecting a config reload. cloudflared does not
     * reload on SIGHUP, it exits: the journal read "Sent signal SIGHUP ... Deactivated
     * successfully", and because that exit is clean, {@code Restart=on-failure} never revived it.
     * The admin API and a live customer's store (viora) were offline for ~4.5 minutes (Cloudflare
     * 530 / error 1033) until the tunnel was started by hand. Do not reintroduce it as an
     * "optimisation".
     *
     * <p>The original objection to {@code systemctl restart} (it would drop the tunnel carrying
     * the very provisioning request being served) is obsolete: provisioning is asynchronous now
     * (the endpoint answers 202 at once and the stages run on a background executor), so no
     * request is in flight through the tunnel while this runs.
     *
     * <p>Trade-off, deliberate: the restart interrupts every hostname on the tunnel, viora
     * included, for a few seconds. That is accepted against a 4.5-minute outage. Defence in depth
     * still wanted on the server: {@code cloudflared.service} should carry {@code Restart=always}
     * (not yet applied, blocked on a separate permission).
     *
     * <p>A throw here leaves the provision on a resumable FAILED row at the INGRESS stage, which
     * is the right outcome: a tunnel that fails to return takes the admin API and every hosted
     * store with it, so this must be loud.
     */
    @Override
    public void reloadCloudflared() {
        run("systemctl", "restart", "cloudflared");
        for (int attempt = 1; attempt <= TUNNEL_ATTEMPTS; attempt++) {
            if (isActive("cloudflared")) {
                log.info("cloudflared is active again (check {} of {})", attempt, TUNNEL_ATTEMPTS);
                return;
            }
            if (attempt < TUNNEL_ATTEMPTS) {
                try {
                    Thread.sleep(TUNNEL_POLL_DELAY.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted waiting for cloudflared to come back", e);
                }
            }
        }
        throw new IllegalStateException("cloudflared did not report active within ~"
                + (TUNNEL_ATTEMPTS * TUNNEL_POLL_DELAY.toSeconds()) + "s of 'systemctl restart cloudflared'."
                + " The tunnel is DOWN: the admin API and every hosted store are unreachable until it is"
                + " started (systemctl start cloudflared; journalctl -u cloudflared). Then re-run Create"
                + " to resume from INGRESS.");
    }

    @Override
    public Optional<String> probeMachineId(int port) {
        return licenceStatus(port).map(LicenceStatus::machineId).filter(id -> !id.isBlank());
    }

    @Override
    public Optional<LicenceStatus> licenceStatus(int port) {
        Optional<String> body = get(port, "/api/license/status");
        if (body.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode status = mapper.readTree(body.get());
            return Optional.of(new LicenceStatus(
                    status.path("licensed").asBoolean(false),
                    status.path("clientCode").asString(""),
                    status.path("machineId").asString("")));
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
    public boolean publicHealth(String apiHostname) {
        if (apiHostname == null || !SAFE_PUBLIC_HOSTNAME.matcher(apiHostname).matches()) {
            throw new IllegalArgumentException("Unsafe hostname for a health probe: " + apiHostname);
        }
        // Built from a validated hostname and a fixed path, so nothing from a request reaches the
        // URL. Any failure — DNS, TLS, 502/530 from the edge, timeout — is simply "not reachable":
        // the caller retries on a budget and then fails the stage loudly.
        URI uri = URI.create("https://" + apiHostname + "/api/health");
        try {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(REQUEST_TIMEOUT)
                            // An explicit, identifiable agent rather than "Java-http-client/21":
                            // this request crosses Cloudflare's edge, and a nondescript default
                            // agent is the kind of thing a managed WAF rule challenges — which
                            // would fail this stage for a store that is perfectly healthy.
                            .header("User-Agent", "Daman-Provisioning-HealthCheck/1")
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                // Logged at WARN, with the code, because this is the one line that tells a 530
                // (tunnel/origin down) apart from a 403 generated by the edge itself.
                log.warn("{} answered HTTP {} — not reachable yet", uri, response.statusCode());
            }
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.info("{} is not reachable yet ({})", uri, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean portInUse(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        // A plain TCP connect: anything accepting on the loopback counts, Daman store or not.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    (int) PORT_PROBE_TIMEOUT.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
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
    public long totalMemoryMb() {
        Path meminfo = Path.of("/proc/meminfo");
        try {
            for (String line : Files.readAllLines(meminfo, StandardCharsets.UTF_8)) {
                // MemTotal, not MemAvailable: the guard reserves committed heaps from the total
                // rather than trusting a momentary free-memory reading. See HostedCapacity.
                if (line.startsWith("MemTotal:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / 1024;
                }
            }
            throw new IllegalStateException(meminfo + " has no MemTotal line");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + meminfo
                    + " — hosted stores can only be provisioned on the Linux server", e);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static final Pattern SYSTEMD_TIMESTAMP =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})\\s+(\\S+)");

    /**
     * Parses systemd's {@code ActiveEnterTimestamp} ({@code Mon 2026-10-26 14:56:41 UTC}). Empty,
     * with a WARN, for a blank or unparseable value: one misbehaving unit must not break a whole
     * fleet poll, so callers treat empty as "unknown", never as "current".
     *
     * <p>Deliberately lenient where systemd varies: the leading day-of-week is ignored (the date is
     * authoritative, so a wrong weekday cannot conflict), the zone may be an abbreviation
     * ({@code UTC}, {@code EET}) or a numeric offset ({@code +03}, {@code +03:00}), nothing depends
     * on the default locale, and the timestamp is found anywhere in the text so a warning line
     * merged in from stderr does not hide it.
     */
    static Optional<Instant> parseSystemdTimestamp(String value) {
        if (value == null || value.isBlank()) {
            log.warn("systemd reported no start time (unit never started?)");
            return Optional.empty();
        }
        Matcher m = SYSTEMD_TIMESTAMP.matcher(value);
        while (m.find()) {
            try {
                ZoneId zone = ZoneId.of(m.group(3), ZoneId.SHORT_IDS);
                return Optional.of(LocalDateTime.parse(m.group(1) + "T" + m.group(2)).atZone(zone).toInstant());
            } catch (DateTimeException e) {
                // try the next candidate, then give up below
            }
        }
        log.warn("could not parse systemd timestamp '{}'", value);
        return Optional.empty();
    }

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
     *
     * @param ownerOnly when true the file is {@code rw-------} from the moment it is created, and a
     *                  failure to make it so aborts the write instead of being logged and ignored.
     *                  Used for anything carrying a secret — the temp file is created with the
     *                  restricted mode <i>before</i> any content is written and the mode survives
     *                  the move, so the secret is never readable at either path for any window.
     */
    private static void writeLf(Path target, String text, boolean ownerOnly) {
        byte[] bytes = text.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
        Path tmp = target.resolveSibling(target.getFileName() + ".daman-tmp");
        try {
            // A leftover temp file from a crashed run would keep its old (possibly wide) mode, so
            // it is removed rather than overwritten.
            Files.deleteIfExists(tmp);
            if (ownerOnly) {
                createOwnerOnly(tmp);
                Files.write(tmp, bytes);
                // Belt and braces on top of the creation mode: proves the mode before the content
                // becomes reachable under the real name, and throws if it cannot be set.
                requireOwnerOnly(tmp);
            } else {
                Files.write(tmp, bytes);
            }
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

    /** Creates an empty {@code rw-------} file. A non-POSIX filesystem leaves it to be created by the write. */
    private static void createOwnerOnly(Path file) throws IOException {
        try {
            Files.createFile(file,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem, so it cannot be the Linux server either; requireOwnerOnly
            // says the same thing again, once, below.
            log.warn("{} is not on a POSIX filesystem — creating it without a permission mode", file);
        }
    }

    /**
     * Forces {@code rw-------}, and <b>throws</b> if it cannot: the caller only asks for this on a
     * file holding a JWT signing secret, and "logged a warning and carried on" leaves that secret
     * world-readable on a box whose {@code /etc/systemd/system} is 0755.
     */
    private static void requireOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (a dev machine). Nothing to enforce and nothing it could break:
            // the real unit directory only exists on the Linux server.
            log.warn("cannot restrict permissions on {}: not a POSIX filesystem", file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not restrict permissions on " + file
                    + " — refusing to leave a file carrying a JWT signing secret readable", e);
        }
    }

    /**
     * Runs a command with no shell, failing loudly on a non-zero exit <b>or</b> a timeout.
     *
     * <p>Output is redirected to a temp file rather than read from a pipe, so the timeout can
     * actually fire: reading the pipe to EOF blocks until the child closes stdout — which normally
     * means until it exits — so a {@code waitFor} placed after such a read is only ever reached by
     * a process that has already finished. Draining into a file also removes the other half of the
     * problem, a chatty child deadlocking on a full pipe buffer while nobody is reading it.
     */
    private static void run(String... command) {
        runCapture(command);
    }

    /** {@link #run} that also returns the command's trimmed combined output. */
    private static String runCapture(String... command) {
        String printable = String.join(" ", command);
        Path captured = null;
        try {
            captured = Files.createTempFile("daman-cmd-", ".log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(captured.toFile())
                    .start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("timed out after " + COMMAND_TIMEOUT_SECONDS
                        + "s, killed: " + printable);
            }
            if (process.exitValue() != 0) {
                String output = readCaptured(captured);
                throw new IllegalStateException(printable + " exited " + process.exitValue()
                        + (output.isEmpty() ? "" : ": " + output));
            }
            log.info("ran: {}", printable);
            return readCaptured(captured);
        } catch (IOException e) {
            throw new UncheckedIOException("could not run " + printable, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + printable, e);
        } finally {
            if (captured != null) {
                try {
                    Files.deleteIfExists(captured);
                } catch (IOException e) {
                    log.warn("could not remove temp file {} — {}", captured, e.getMessage());
                }
            }
        }
    }

    /** True when {@code systemctl is-active --quiet <unit>} exits 0. Any failure to ask counts as "not active". */
    private static boolean isActive(String unit) {
        requireSafeUnitName(unit);
        try {
            Process p = new ProcessBuilder("systemctl", "is-active", "--quiet", unit)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Best-effort: the command already failed, so an unreadable log must not mask why. */
    private static String readCaptured(Path captured) {
        try {
            return Files.readString(captured, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
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
