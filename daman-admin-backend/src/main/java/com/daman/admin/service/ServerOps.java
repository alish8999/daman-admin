package com.daman.admin.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Every operation that touches the server's OS or a store's own HTTP port, behind one seam so the
 * provisioning orchestrator can be unit-tested with a fake. {@link SystemdServerOps} is the only
 * implementation that shells out or opens a socket; nothing else in this feature does either.
 *
 * <p>Implementations fail loudly: an operation that cannot be completed throws, so the stage that
 * called it is recorded as the failure point. The two probes are the exception — {@link #health}
 * and {@link #probeMachineId} answer "no" for a store that is not up yet, because that is a normal
 * intermediate state while a freshly started unit boots.
 */
public interface ServerOps {

    /**
     * What a store's own {@code /api/license/status} reports about itself — the only authority on
     * whether an activation actually stuck, since the licence is written and verified inside the
     * store's process, not here.
     */
    record LicenceStatus(boolean licensed, String clientCode, String machineId) {}

    /** The newest backup file in a directory: name only (no path), size and last-modified time. */
    record BackupInfo(String name, long sizeBytes, Instant modifiedAt) {}

    /**
     * Live numbers about the box itself, read from files (no process spawn). Every field is
     * required: a box that can only report some of them reports none (empty), so a caller never has
     * to render a half-known strip.
     */
    record BoxStats(long ramTotalBytes, long ramAvailableBytes, double load1, int cores,
                    long diskTotalBytes, long diskFreeBytes, long uptimeSeconds) {}

    /** Creates the directory and every missing parent. No-op when it already exists. */
    void mkdir(String path);

    /**
     * Writes {@code /etc/systemd/system/<serviceName>.service}. Implementations must write LF
     * bytes explicitly — a CR anywhere in a unit file makes systemd reject it.
     */
    void writeUnit(String serviceName, String unitText);

    /** {@code systemctl daemon-reload} then {@code systemctl enable --now <serviceName>}. */
    void daemonReloadAndStart(String serviceName);

    /** {@code systemctl restart <serviceName>}. Used by the shared-jar release walk (Task 10). */
    void restart(String serviceName);

    /** The whole shared cloudflared {@code config.yml}, verbatim. */
    String readIngress();

    /** Replaces the shared cloudflared {@code config.yml}. LF bytes only, same as {@link #writeUnit}. */
    void writeIngress(String yaml);

    /**
     * Makes cloudflared pick up an edited ingress list by restarting it, and throws unless it is
     * active again afterwards. The restart briefly interrupts every hostname on the tunnel. Never
     * SIGHUP: that terminates cloudflared (see the real implementation).
     */
    void reloadCloudflared();

    /**
     * The machine id the store's own backend computed, read from its {@code /api/license/status}.
     * Empty when the store is not answering — it is the store, not this process, that owns the value.
     */
    Optional<String> probeMachineId(int port);

    /** The store's own view of its licence. Empty when it is not answering. Never throws. */
    Optional<LicenceStatus> licenceStatus(int port);

    /** True when the store answers {@code /api/health} with 200 on {@code 127.0.0.1}. Never throws. */
    boolean health(int port);

    /**
     * True when the store answers {@code https://<apiHostname>/api/health} with 200 — i.e. through
     * Cloudflare's edge and the tunnel, the way a customer reaches it. Never throws for a network
     * failure or a non-200 (that is the answer), but may throw for an invalid hostname.
     *
     * <p>Deliberately separate from {@link #health}: that one proves the JVM is up, this one proves
     * the ingress rule and the DNS record actually point at it.
     */
    boolean publicHealth(String apiHostname);

    /**
     * True when <b>anything</b> is already listening on {@code port}. Deliberately not
     * {@link #health}: that only recognises a Daman store, so it would hand out a port held by
     * some unrelated process and the new store would fail to bind a minute later.
     */
    boolean portInUse(int port);

    /** Posts the licence key to the store's own {@code /api/license/activate}. Throws if it is refused. */
    void activateLicence(int port, String licenseKey);

    /**
     * TOTAL RAM in MB, as the capacity guard's input — not free RAM. The guard reserves each
     * existing store's full heap from this total instead of trusting a momentary free-memory
     * reading, which a freshly booted JVM makes look far rosier than it is.
     */
    long totalMemoryMb();

    /** Box RAM/load/disk/uptime from /proc and the filesystem. Empty, never an exception, when unreadable. */
    Optional<BoxStats> boxStats();

    /**
     * Memory charged to one store's systemd unit (cgroup v2 {@code memory.current}), in bytes.
     * Empty, never an exception, when the file is missing or unparseable (cgroup v1, unit not running).
     */
    Optional<Long> storeMemoryBytes(String serviceName);

    /** {@code systemctl disable --now <serviceName>}: stops it and keeps it from starting on boot. */
    void stopAndDisable(String serviceName);

    /** {@code systemctl enable --now <serviceName>}: the inverse of {@link #stopAndDisable}. */
    void enableAndStart(String serviceName);

    /**
     * Removes {@code /etc/systemd/system/<serviceName>.service} (no error if already gone), then
     * {@code systemctl daemon-reload}. Does not stop the unit; call {@link #stopAndDisable} first.
     */
    void deleteUnit(String serviceName);

    /** True when systemd reports the unit active. Any failure to ask counts as "not active". */
    boolean unitActive(String serviceName);

    /**
     * When the unit last entered the active state ({@code ActiveEnterTimestamp}). Empty for a unit
     * that has never started (systemd prints a blank value), or an unparseable one.
     */
    Optional<Instant> unitStartedAt(String serviceName);

    /** Last-modified time of a file, or empty when it does not exist. */
    Optional<Instant> fileModifiedAt(String path);

    /** The most recently modified regular file directly inside {@code dir}; empty when there is none. */
    Optional<BackupInfo> newestBackup(String dir);

    /** Writes a zip at {@code targetZip} containing {@code sourceFile} as the single entry {@code entryName}. */
    void zipFileInto(String sourceFile, String targetZip, String entryName);

    /**
     * Recursively deletes {@code path}. Throws {@link IllegalArgumentException}, before touching
     * anything, unless {@link StorePaths#isAllowedDataDir} accepts the path for {@code clientCode}.
     */
    void deleteTree(String path, String clientCode);
}
