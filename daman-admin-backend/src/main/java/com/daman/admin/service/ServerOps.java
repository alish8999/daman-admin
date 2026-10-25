package com.daman.admin.service;

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
}
