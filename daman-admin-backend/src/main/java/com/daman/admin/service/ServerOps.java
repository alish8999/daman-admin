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

    /** Makes cloudflared pick up an edited ingress list without dropping the tunnel. */
    void reloadCloudflared();

    /**
     * The machine id the store's own backend computed, read from its {@code /api/license/status}.
     * Empty when the store is not answering — it is the store, not this process, that owns the value.
     */
    Optional<String> probeMachineId(int port);

    /** True when the store answers {@code /api/health} with 200. Never throws. */
    boolean health(int port);

    /** Posts the licence key to the store's own {@code /api/license/activate}. Throws if it is refused. */
    void activateLicence(int port, String licenseKey);

    /** Free RAM in MB, as the capacity guard's input. */
    long availableMemoryMb();
}
