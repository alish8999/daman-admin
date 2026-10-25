package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.service.HostedStoreProvisioningService.PreparedProvision;
import com.daman.admin.service.HostedStoreProvisioningService.ProvisionRequest;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts a hosted-store provision and gets out of the way.
 *
 * <p><b>Why this exists at all.</b> Provisioning takes 125-150 s on the happy path
 * ({@code PAGES_ACTIVE} alone is ~105 s, and the {@code START} health wait can add 60 s), but the
 * only route to this backend is {@code admin-api.damansoft.com} through a Cloudflare tunnel, and
 * Cloudflare's edge answers <b>524 after 100 s</b> of origin silence on this zone's plan. Holding
 * the HTTP response open for the whole sequence therefore reported failure on every <i>successful</i>
 * Create: the owner saw Cloudflare's own HTML error while the server quietly finished and marked
 * the row {@code ACTIVE}, and a second click then answered 409, which rendered as another failure.
 * So the controller returns <b>202 with the {@code PROVISIONING} row</b> and the dashboard polls
 * {@code GET /api/hosted-stores} until that row reads {@code ACTIVE} or {@code FAILED}.
 *
 * <p>Spec §11 left this open — "block on the poll, or return immediately and let the dashboard show
 * activation progress". Blocking was chosen and the transport cannot carry it.
 *
 * <p><b>One run per client code at a time.</b> Two concurrent runs for one client would both reach
 * {@link HostedStoreProvisioningService}'s {@code licence()} stage, both find no {@code ACTIVE}
 * licence row for {@code (machineId, clientCode)}, and insert one each — after which that
 * {@code Optional}-returning finder throws {@code IncorrectResultSizeDataAccessException} for every
 * later licence operation on that client. A second <i>in-flight</i> run is therefore refused
 * ({@link AlreadyRunningException} → 409), and whatever does get through is serialised on a single
 * thread per client code.
 *
 * <p>The guard is on a run being <b>in flight in this JVM</b>, deliberately not on the row's status:
 * a crashed or restarted run legitimately leaves a {@code PROVISIONING} row behind, and that row
 * must stay resumable by re-running Create. Different clients never wait on each other — each gets
 * its own single-threaded executor, so provisioning store B does not queue behind store A's 150 s.
 */
@Slf4j
@Service
public class HostedStoreProvisionLauncher {

    /**
     * How one client's stage loop is handed off. The seam exists so tests can run the loop on the
     * calling thread, or hold it, instead of racing a real thread pool.
     */
    interface StageDispatcher {
        void dispatch(String clientCode, Runnable stageLoop);

        /** Releases any threads held. Called from {@link HostedStoreProvisionLauncher#shutdown()}. */
        default void shutdown() {}
    }

    /** A second run for a client that already has one in flight. The controller answers 409. */
    public static class AlreadyRunningException extends IllegalStateException {
        public AlreadyRunningException(String message) {
            super(message);
        }
    }

    /**
     * What the caller needs to answer the request: the row as it stands right now, and whether this
     * client was already {@code ACTIVE} (in which case nothing was started and nothing will be).
     */
    public record Started(HostedInstance instance, boolean alreadyActive) {}

    private final HostedStoreProvisioningService service;
    private final StageDispatcher dispatcher;

    /** Client codes with a stage loop in flight right now. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public HostedStoreProvisionLauncher(HostedStoreProvisioningService service) {
        this(service, new PerClientThreads());
    }

    HostedStoreProvisionLauncher(HostedStoreProvisioningService service, StageDispatcher dispatcher) {
        this.service = service;
        this.dispatcher = dispatcher;
    }

    /**
     * Decides every refusal synchronously, then hands the stage loop to this client's own thread.
     *
     * @return the registry row as it stands when this returns — {@code PROVISIONING} for a run just
     *         started (the caller answers 202), or the untouched {@code ACTIVE} row when the client
     *         already has a store ({@code alreadyActive}, so the caller answers 409).
     * @throws HostedStoreProvisioningService.RefusedException     bad input; nothing persisted (400)
     * @throws HostedStoreProvisioningService.UnavailableException no capacity / no Cloudflare config (503)
     * @throws AlreadyRunningException                             a run for this client is in flight (409)
     */
    public Started start(ProvisionRequest request) {
        String key = key(request.clientCode());
        if (!inFlight.add(key)) {
            throw new AlreadyRunningException("A provision for client '" + request.clientCode()
                    + "' is already running. Watch GET /api/hosted-stores until its row reads"
                    + " ACTIVE or FAILED, then re-run Create if it failed.");
        }
        boolean dispatched = false;
        try {
            // Refusals and the already-ACTIVE answer are decided before anything runs, so they stay
            // immediate and keep their own status codes.
            PreparedProvision prepared = service.prepare(request);
            if (prepared.alreadyActive()) {
                return new Started(prepared.instance(), true);
            }
            dispatcher.dispatch(key, () -> runAndRelease(key, prepared));
            dispatched = true;
            log.info("Provisioning of hosted store '{}' handed off; it will take ~2 minutes and the"
                    + " registry row is PROVISIONING until it is ACTIVE or FAILED", key);
            return new Started(prepared.instance(), false);
        } finally {
            // Only reached with dispatched == false when prepare() threw or the hand-off itself
            // failed; once dispatched, runAndRelease owns the release.
            if (!dispatched) {
                inFlight.remove(key);
            }
        }
    }

    /** Whether a stage loop for this client code is running in this JVM right now. */
    public boolean isRunning(String clientCode) {
        return inFlight.contains(key(clientCode));
    }

    private void runAndRelease(String key, PreparedProvision prepared) {
        try {
            service.runStages(prepared);
        } catch (RuntimeException e) {
            // runStages records a stage failure on the row itself, so reaching here means something
            // outside the stage loop broke (e.g. the final save). Log it: nothing else can.
            log.error("Provisioning of hosted store '{}' ended abnormally; its row keeps the last"
                    + " stage it recorded and a re-run resumes from there", key, e);
        } finally {
            inFlight.remove(key);
        }
    }

    /**
     * Trimmed and lower-cased, so {@code "Acme "} and {@code "acme"} cannot both hold a run.
     *
     * <p>Deliberately coarser than the thing it protects: licence rows and registry rows are keyed
     * on the exact client code, so a case-sensitive guard would technically be enough. Erring
     * coarse means the guard can only ever be too strict, never too loose — and "too strict" here
     * costs one self-explanatory 409 in a scenario that needs two client codes differing <i>only</i>
     * in case to be provisioned in the same two minutes. Real codes ({@code abu_ammar},
     * {@code Bistro-cafe}, {@code Hadid}, {@code 2-brothers}) are nowhere near that.
     */
    private static String key(String clientCode) {
        return clientCode == null ? "" : clientCode.trim().toLowerCase(Locale.ROOT);
    }

    @PreDestroy
    void shutdown() {
        dispatcher.shutdown();
    }

    /**
     * One single-threaded executor per client code, created on first use and kept. The count is
     * bounded by the number of distinct clients ever provisioned in this JVM's lifetime — a handful
     * on a box spec §7 sizes at about three stores — and the threads are daemons, so a hosted-store
     * provision never holds up an admin-backend shutdown. A run cut short that way leaves exactly
     * the {@code PROVISIONING}/{@code FAILED} row the resume path is built for.
     */
    private static final class PerClientThreads implements StageDispatcher {
        private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

        @Override
        public void dispatch(String clientCode, Runnable stageLoop) {
            executors.computeIfAbsent(clientCode, PerClientThreads::singleDaemonThread)
                    .execute(stageLoop);
        }

        @Override
        public void shutdown() {
            Collection<ExecutorService> all = executors.values();
            all.forEach(ExecutorService::shutdownNow);
            all.clear();
        }

        private static ExecutorService singleDaemonThread(String clientCode) {
            return Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "provision-" + clientCode);
                thread.setDaemon(true);
                return thread;
            });
        }
    }
}
