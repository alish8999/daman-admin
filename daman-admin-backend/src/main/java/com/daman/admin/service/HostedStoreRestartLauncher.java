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
 * <p>The walk is a single call that returns when finished, so there is no per-store progress:
 * {@code currentClientCode} is null throughout and the lists fill in on completion.
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
        RestartRun finished;
        try {
            RestartReport report = service.walk();
            List<RestartResult> walked = List.copyOf(report.walked());
            State state = walked.isEmpty() ? State.NOOP
                    : walked.stream().anyMatch(r -> !r.healthy()) ? State.HALTED : State.DONE;
            finished = new RestartRun(state, null, walked, List.copyOf(report.skipped()),
                    startedAt, Instant.now());
        } catch (RuntimeException e) {
            // walk() reports per-store failures in its result, so reaching here is something
            // outside that. Free the guard and say HALTED rather than leave RUNNING forever.
            log.error("Restart-all walk ended abnormally", e);
            finished = new RestartRun(State.HALTED, null, List.of(), List.of(), startedAt, Instant.now());
        }
        synchronized (this) {
            current = finished;
        }
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
