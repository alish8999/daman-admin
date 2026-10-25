package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.service.HostedStoreProvisioningService.PreparedProvision;
import com.daman.admin.service.HostedStoreProvisioningService.ProvisionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hand-off: 202-shaped behaviour (the stage loop does NOT run inside the caller's call) and the
 * one-run-per-client guard that stops a duplicate click minting two {@code ACTIVE} licence rows.
 *
 * <p>Nothing here starts a thread by default — the dispatcher seam captures the stage loop so a
 * test decides exactly when it runs. One case uses the real per-client threads, to prove the
 * production dispatcher really is asynchronous and really does keep clients independent.
 */
class HostedStoreProvisionLauncherTest {

    private HostedStoreProvisioningService service;

    /** Stage loops handed to the dispatcher but not yet run, with the key they were filed under. */
    private final List<String> dispatchedKeys = new ArrayList<>();
    private final List<Runnable> dispatched = new ArrayList<>();

    private HostedStoreProvisionLauncher launcher;

    @BeforeEach
    void setUp() {
        service = mock(HostedStoreProvisioningService.class);
        launcher = new HostedStoreProvisionLauncher(service, (clientCode, stageLoop) -> {
            dispatchedKeys.add(clientCode);
            dispatched.add(stageLoop);
        });
    }

    private static HostedInstance row(String code, String status) {
        HostedInstance i = new HostedInstance();
        i.setId(1L);
        i.setClientCode(code);
        i.setSubdomain(code);
        i.setPort(8091);
        i.setServiceName("daman-store-" + code);
        i.setStatus(status);
        return i;
    }

    private PreparedProvision prepared(String code) {
        return new PreparedProvision(row(code, "PROVISIONING"), ProvisionStage.ALLOCATE, null, false);
    }

    private static ProvisionRequest request(String code) {
        return new ProvisionRequest(code, code, null);
    }

    @Test
    void startReturnsThePROVISIONINGRowWithoutRunningAnyStage() {
        // This is what makes the 202 honest: the caller gets a row immediately, and the 125-150 s
        // of stages happen afterwards, off the request thread, because the admin API is reached
        // through a Cloudflare tunnel that gives up on a silent origin after 100 s.
        when(service.prepare(any())).thenReturn(prepared("acme"));

        HostedStoreProvisionLauncher.Started started = launcher.start(request("acme"));

        assertThat(started.alreadyActive()).isFalse();
        assertThat(started.instance().getStatus()).isEqualTo("PROVISIONING");
        verify(service, never()).runStages(any());
        assertThat(dispatched).hasSize(1);
        assertThat(dispatchedKeys).containsExactly("acme");

        // ...and the loop does run, once the dispatcher gets to it.
        dispatched.get(0).run();
        verify(service).runStages(any());
    }

    @Test
    void aSecondStartWhileOneIsInFlightIsRefused() {
        when(service.prepare(any())).thenReturn(prepared("acme"));
        launcher.start(request("acme"));

        assertThatThrownBy(() -> launcher.start(request("acme")))
                .isInstanceOf(HostedStoreProvisionLauncher.AlreadyRunningException.class)
                .hasMessageContaining("already running");

        // The refused click prepared nothing and dispatched nothing: no second row, and crucially
        // no second run that would reach licence() and insert a duplicate ACTIVE licence row.
        verify(service).prepare(any());
        assertThat(dispatched).hasSize(1);
        assertThat(launcher.isRunning("acme")).isTrue();
    }

    @Test
    void theGuardIsReleasedWhenTheRunFinishesSoAFailedStoreCanBeResumed() {
        when(service.prepare(any())).thenReturn(prepared("acme"));
        launcher.start(request("acme"));
        dispatched.get(0).run();

        assertThat(launcher.isRunning("acme")).isFalse();
        launcher.start(request("acme"));  // the resume the runbook tells the owner to do
        assertThat(dispatched).hasSize(2);
    }

    @Test
    void theGuardIsReleasedEvenWhenTheRunBlowsUp() {
        when(service.prepare(any())).thenReturn(prepared("acme"));
        when(service.runStages(any())).thenThrow(new IllegalStateException("the final save failed"));
        launcher.start(request("acme"));

        dispatched.get(0).run();   // must not propagate: nothing is there to catch it

        assertThat(launcher.isRunning("acme")).isFalse();
    }

    @Test
    void aRefusalFromPrepareLeavesNoGuardBehind() {
        // Otherwise a mistyped subdomain would lock that client out until the backend restarted.
        when(service.prepare(any()))
                .thenThrow(new HostedStoreProvisioningService.RefusedException("Invalid subdomain"));

        assertThatThrownBy(() -> launcher.start(request("acme")))
                .isInstanceOf(HostedStoreProvisioningService.RefusedException.class);

        assertThat(launcher.isRunning("acme")).isFalse();
        assertThat(dispatched).isEmpty();
    }

    @Test
    void aPROVISIONINGRowIsNotTreatedAsAnInFlightRun() {
        // A crashed or restarted run legitimately leaves a PROVISIONING row. It must stay resumable:
        // the guard is on a run being in flight IN THIS JVM, never on a row's status.
        HostedInstance crashed = row("acme", "PROVISIONING");
        crashed.setFailedStage("PAGES_ACTIVE");
        when(service.prepare(any()))
                .thenReturn(new PreparedProvision(crashed, ProvisionStage.PAGES_ACTIVE, null, false));

        assertThat(launcher.isRunning("acme")).isFalse();
        HostedStoreProvisionLauncher.Started started = launcher.start(request("acme"));

        assertThat(started.alreadyActive()).isFalse();
        assertThat(dispatched).hasSize(1);
    }

    @Test
    void anAlreadyActiveClientIsReportedWithoutDispatchingOrHoldingTheGuard() {
        HostedInstance active = row("acme", "ACTIVE");
        when(service.prepare(any()))
                .thenReturn(new PreparedProvision(active, ProvisionStage.VERIFY, null, true));

        HostedStoreProvisionLauncher.Started started = launcher.start(request("acme"));

        assertThat(started.alreadyActive()).isTrue();
        assertThat(started.instance()).isSameAs(active);
        assertThat(dispatched).isEmpty();
        verify(service, never()).runStages(any());
        // Not held: a second look at an already-ACTIVE client must keep answering 409, not 409-for-
        // the-wrong-reason.
        assertThat(launcher.isRunning("acme")).isFalse();
    }

    @Test
    void oneClientsRunDoesNotBlockAnother() {
        when(service.prepare(any())).thenReturn(prepared("acme"), prepared("beta"));

        launcher.start(request("acme"));
        launcher.start(request("beta"));

        assertThat(dispatchedKeys).containsExactly("acme", "beta");
        assertThat(launcher.isRunning("acme")).isTrue();
        assertThat(launcher.isRunning("beta")).isTrue();
    }

    @Test
    void theClientCodeKeyIsTrimmedAndCaseInsensitive() {
        when(service.prepare(any())).thenReturn(prepared("acme"));
        launcher.start(new ProvisionRequest(" Acme ", "acme", null));

        assertThat(launcher.isRunning("acme")).isTrue();
        assertThatThrownBy(() -> launcher.start(request("ACME")))
                .isInstanceOf(HostedStoreProvisionLauncher.AlreadyRunningException.class);
    }

    @Test
    void theProductionDispatcherRunsTheLoopOnAnotherThreadAndReleasesTheGuard() throws Exception {
        // The real per-client-thread dispatcher, not the capturing seam: proves start() does not
        // block on the stage loop, which is the entire point of the 202.
        HostedStoreProvisionLauncher real = new HostedStoreProvisionLauncher(service);
        CountDownLatch inStageLoop = new CountDownLatch(1);
        CountDownLatch releaseStageLoop = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        when(service.prepare(any())).thenReturn(prepared("acme"));
        when(service.runStages(any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            inStageLoop.countDown();
            assertThat(releaseStageLoop.await(5, TimeUnit.SECONDS)).isTrue();
            return row("acme", "ACTIVE");
        });

        try {
            HostedStoreProvisionLauncher.Started started = real.start(request("acme"));

            // start() returned while the loop is still inside runStages.
            assertThat(started.instance().getStatus()).isEqualTo("PROVISIONING");
            assertThat(inStageLoop.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(real.isRunning("acme")).isTrue();
            assertThatThrownBy(() -> real.start(request("acme")))
                    .isInstanceOf(HostedStoreProvisionLauncher.AlreadyRunningException.class);

            releaseStageLoop.countDown();
            for (int i = 0; i < 100 && real.isRunning("acme"); i++) {
                Thread.sleep(20);
            }
            assertThat(real.isRunning("acme")).isFalse();
            assertThat(runs).hasValue(1);
        } finally {
            releaseStageLoop.countDown();
            real.shutdown();
        }
    }
}
