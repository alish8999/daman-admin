package com.daman.admin.service;

import com.daman.admin.service.HostedStoreRestartService.RestartReport;
import com.daman.admin.service.HostedStoreRestartService.RestartResult;
import com.daman.admin.service.HostedStoreRestartService.SkippedStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the fleet restart walk on a background thread so a browser can start it and poll.
 *
 * <p><b>Why this exists.</b> {@link HostedStoreRestartService#walk()} is bounded at 40 health polls
 * x 3 s = 120 s <i>per store</i>, so one walk can far outrun the 100 s after which Cloudflare's edge
 * answers 524. The deploy scripts call the synchronous {@code POST /restart-all} over SSH, where
 * Cloudflare is not in the path; the dashboard goes through the edge and therefore uses the
 * 202-and-poll pair this launcher backs. Same shape as {@link HostedStoreProvisionLauncher}.
 *
 * <p><b>One walk at a time.</b> Two concurrent walks would restart the same stores twice over. A
 * second {@link #start()} while one is in flight is refused with {@link AlreadyRunningException}.
 *
 * <p><b>Thread-safety.</b> The snapshot is an immutable {@link RestartRun} (its lists are copied)
 * held in a volatile field and replaced whole; {@link #start()} and the completion step are
 * synchronized, so a reader never sees a half-written state.
 *
 * <p>Progress: the service reports each store just before restarting it, and that republishes the
 * snapshot as RUNNING with {@code currentClientCode} set, so a poller can see which store a
 * 120 s-per-store walk is on. {@code walked} and {@code skipped} fill in on completion.
 */
@Slf4j
@Service
public class HostedStoreRestartLauncher {

    public enum State { IDLE, RUNNING, DONE, HALTED, NOOP }

    /** The walk as it stands right now. Immutable; safe to hand straight to Jackson. */
    public record RestartRun(State state, String currentClientCode, List<RestartResult> walked,
                             List<SkippedStore> skipped, Instant startedAt, Instant finishedAt) {
        static RestartRun idle() {
            return new RestartRun(State.IDLE, null, List.of(), List.of(), null, null);
        }
    }

    /** Seam so tests run the walk on the calling thread, or hold it, instead of racing a pool. */
    interface StageDispatcher {
        void dispatch(Runnable walk);

        default void shutdown() {}
    }

    /** A second walk while one is in flight. The controller answers 409. */
    public static class AlreadyRunningException extends IllegalStateException {
        public AlreadyRunningException(String message) {
            super(message);
        }
    }

    private final HostedStoreRestartService service;
    private final StageDispatcher dispatcher;
    private volatile RestartRun current = RestartRun.idle();

    @Autowired
    public HostedStoreRestartLauncher(HostedStoreRestartService service) {
        this(service, new SingleThread());
    }

    HostedStoreRestartLauncher(HostedStoreRestartService service, StageDispatcher dispatcher) {
        this.service = service;
        this.dispatcher = dispatcher;
    }

    /** Begins a walk and returns the RUNNING snapshot. @throws AlreadyRunningException if one is in flight. */
    public synchronized RestartRun start() {
        if (current.state() == State.RUNNING) {
            throw new AlreadyRunningException("A restart-all walk is already running."
                    + " Poll GET /api/hosted-stores/restart-all/status until it finishes.");
        }
        RestartRun running = new RestartRun(State.RUNNING, null, List.of(), List.of(), Instant.now(), null);
        current = running;
        try {
            dispatcher.dispatch(this::runWalk);
        } catch (RuntimeException e) {
            current = RestartRun.idle(); // hand-off failed: nothing is running, so do not wedge the guard
            throw e;
        }
        // With a calling-thread dispatcher the walk has already finished and replaced current.
        return current.state() == State.RUNNING ? running : current;
    }

    /** The latest snapshot; never null. */
    public RestartRun current() {
        return current;
    }

    private void runWalk() {
        Instant startedAt = current.startedAt();
        RestartRun finished = null;
        try {
            RestartReport report = service.walk(this::onStoreStarting);
            List<RestartResult> walked = List.copyOf(report.walked());
            State state = walked.isEmpty() ? State.NOOP
                    : walked.stream().anyMatch(r -> !r.healthy()) ? State.HALTED : State.DONE;
            finished = new RestartRun(state, null, walked, List.copyOf(report.skipped()),
                    startedAt, Instant.now());
        } catch (Throwable t) {
            // walk() reports per-store failures in its result, so reaching here is something outside
            // that. Surface the cause as an unhealthy entry (the record shape is fixed), naming the
            // store the walk was on, so the report reads HALTED and says why.
            log.error("Restart-all walk ended abnormally", t);
            finished = new RestartRun(State.HALTED, null,
                    List.of(new RestartResult(current.currentClientCode(), false, false,
                            t.getClass().getSimpleName() + ": " + t.getMessage())),
                    List.of(), startedAt, Instant.now());
            // AssertionError is deliberately not rethrown: it is already recorded as HALTED above, and only genuine JVM-level Errors (OOM etc.) should escape. Do not "fix" this.
            if (t instanceof Error error && !(t instanceof AssertionError)) {
                throw error;
            }
        } finally {
            // Unmissable: whatever happened above, RUNNING must not outlive the walk.
            RestartRun terminal = finished != null ? finished
                    : new RestartRun(State.HALTED, null, List.of(), List.of(), startedAt, Instant.now());
            synchronized (this) {
                current = terminal;
            }
        }
    }

    /** Republishes the snapshot (whole, never mutated) with the store the walk is now on. */
    private synchronized void onStoreStarting(String clientCode) {
        RestartRun now = current;
        current = new RestartRun(State.RUNNING, clientCode, now.walked(), now.skipped(),
                now.startedAt(), null);
    }

    @PreDestroy
    void shutdown() {
        dispatcher.shutdown();
    }

    /** One daemon thread: a walk never holds up an admin-backend shutdown. */
    private static final class SingleThread implements StageDispatcher {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "restart-all");
            thread.setDaemon(true);
            return thread;
        });

        @Override
        public void dispatch(Runnable walk) {
            executor.execute(walk);
        }

        @Override
        public void shutdown() {
            executor.shutdownNow();
        }
    }
}
