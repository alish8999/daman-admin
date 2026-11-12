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
        when(service.walk()).thenReturn(new RestartReport(List.of(ok("a"), ok("b")), List.of()));
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
        when(service.walk()).thenReturn(new RestartReport(List.of(ok("a"), unhealthy("b")), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        launcher.start();

        assertThat(launcher.current().state()).isEqualTo(State.HALTED);
    }

    @Test
    void anEmptyWalkEndsInNoop() {
        when(service.walk()).thenReturn(new RestartReport(List.of(), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        launcher.start();

        assertThat(launcher.current().state()).isEqualTo(State.NOOP);
    }

    @Test
    void aFinishedRunFreesTheGuardForTheNextOne() {
        when(service.walk()).thenReturn(new RestartReport(List.of(ok("a")), List.of()));
        HostedStoreRestartLauncher launcher = onCallingThread();

        launcher.start();
        launcher.start();

        assertThat(launcher.current().state()).isEqualTo(State.DONE);
    }
}
