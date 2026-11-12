package com.daman.admin.service;

import com.daman.admin.service.HostedStoreRestartLauncher.AlreadyRunningException;
import com.daman.admin.service.HostedStoreRestartLauncher.RestartRun;
import com.daman.admin.service.HostedStoreRestartLauncher.State;
import com.daman.admin.service.HostedStoreRestartService.RestartReport;
import com.daman.admin.service.HostedStoreRestartService.RestartResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HostedStoreRestartLauncherTest {

    private final HostedStoreRestartService service = mock(HostedStoreRestartService.class);

    private static RestartResult ok(String code) {
        return new RestartResult(code, true, true, null);
    }

    private static RestartResult unhealthy(String code) {
        return new RestartResult(code, true, false, "never became healthy");
    }

    private HostedStoreRestartLauncher onCallingThread() {
        return new HostedStoreRestartLauncher(service, Runnable::run);
    }

    @Test
    void startRunsTheWalkAndEndsInDone() {
        when(service.walk(any())).thenReturn(new RestartReport(List.of(ok("a"), ok("b")), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        launcher.start();

        RestartRun run = launcher.current();
        assertThat(run.state()).isEqualTo(State.DONE);
        assertThat(run.walked()).hasSize(2);
        assertThat(run.startedAt()).isNotNull();
        assertThat(run.finishedAt()).isNotNull();
    }

    @Test
    void aSecondStartWhileOneIsInFlightIsRefused() {
        AtomicReference<Runnable> held = new AtomicReference<>();
        HostedStoreRestartLauncher launcher = new HostedStoreRestartLauncher(service, held::set);

        RestartRun first = launcher.start();

        assertThat(first.state()).isEqualTo(State.RUNNING);
        assertThat(launcher.current().state()).isEqualTo(State.RUNNING);
        assertThrows(AlreadyRunningException.class, launcher::start);
    }

    @Test
    void anIdleLauncherReportsIdleWithEmptyListsAndNeverNull() {
        RestartRun run = onCallingThread().current();

        assertThat(run).isNotNull();
        assertThat(run.state()).isEqualTo(State.IDLE);
        assertThat(run.walked()).isEmpty();
        assertThat(run.skipped()).isEmpty();
    }

    @Test
    void aHaltedWalkEndsInHalted() {
        when(service.walk(any())).thenReturn(new RestartReport(List.of(ok("a"), unhealthy("b")), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        launcher.start();

        assertThat(launcher.current().state()).isEqualTo(State.HALTED);
    }

    @Test
    void anEmptyWalkEndsInNoop() {
        when(service.walk(any())).thenReturn(new RestartReport(List.of(), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        launcher.start();

        assertThat(launcher.current().state()).isEqualTo(State.NOOP);
    }

    @Test
    void aFinishedRunFreesTheGuardForTheNextOne() {
        when(service.walk(any())).thenReturn(new RestartReport(List.of(ok("a")), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        launcher.start();
        launcher.start();

        assertThat(launcher.current().state()).isEqualTo(State.DONE);
    }

    @Test
    void theSnapshotNamesTheStoreTheWalkIsCurrentlyOn() {
        AtomicReference<String> seenWhileWalking = new AtomicReference<>();
        HostedStoreRestartLauncher[] box = new HostedStoreRestartLauncher[1];
        when(service.walk(any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> cb = inv.getArgument(0);
            cb.accept("a");
            seenWhileWalking.set(box[0].current().currentClientCode() + "/" + box[0].current().state());
            return new RestartReport(List.of(ok("a")), List.of());
        });
        box[0] = onCallingThread();

        box[0].start();

        assertThat(seenWhileWalking.get()).isEqualTo("a/RUNNING");
        assertThat(box[0].current().currentClientCode()).isNull();
        assertThat(box[0].current().state()).isEqualTo(State.DONE);
    }

    @Test
    void aWalkThatThrowsEndsInHaltedNamesTheCauseAndFreesTheGuard() {
        when(service.walk(any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> cb = inv.getArgument(0);
            cb.accept("b");
            throw new IllegalStateException("ssh gone");
        }).thenReturn(new RestartReport(List.of(ok("a")), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        launcher.start();

        RestartRun run = launcher.current();
        assertThat(run.state()).isEqualTo(State.HALTED);
        assertThat(run.walked()).hasSize(1);
        RestartResult cause = run.walked().get(0);
        assertThat(cause.clientCode()).isEqualTo("b");
        assertThat(cause.healthy()).isFalse();
        assertThat(cause.error()).contains("IllegalStateException").contains("ssh gone");

        launcher.start(); // guard freed
        assertThat(launcher.current().state()).isEqualTo(State.DONE);
    }

    @Test
    void anErrorThrownByTheWalkStillFreesTheGuard() {
        when(service.walk(any())).thenThrow(new NoClassDefFoundError("x"))
                .thenReturn(new RestartReport(List.of(ok("a")), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        assertThrows(NoClassDefFoundError.class, launcher::start);

        assertThat(launcher.current().state()).isEqualTo(State.HALTED);
        launcher.start();
        assertThat(launcher.current().state()).isEqualTo(State.DONE);
    }
}
