package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.event.LicenceRemintedEvent;
import com.daman.admin.repository.HostedInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hosted store gets its re-minted key immediately, instead of waiting for the 12-hour poll.
 *
 * <p>This is the only place in the fleet where instant delivery is possible: the admin backend
 * runs on the same box as the stores and can call their own activation endpoint. A desktop install
 * has to come and fetch its key.
 *
 * <p>Fail-soft throughout. The licence row has already been saved by the time this runs, and a
 * store that is down must not undo a correct mint — the store would pick the key up on its next
 * poll anyway.
 */
class HostedLicencePushListenerTest {

    HostedInstanceRepository instances;
    ServerOps ops;
    HostedLicencePushListener listener;

    @BeforeEach
    void setUp() {
        instances = mock(HostedInstanceRepository.class);
        ops = mock(ServerOps.class);
        listener = new HostedLicencePushListener(instances, ops);
    }

    private HostedInstance instance(String clientCode, String status) {
        HostedInstance i = new HostedInstance();
        i.setClientCode(clientCode);
        i.setPort(9101);
        i.setStatus(status);
        return i;
    }

    private LicenceRemintedEvent event() {
        return new LicenceRemintedEvent(1L, "viora", "m-host", "new-key");
    }

    @Test
    void aRemintForAHostedInstancePushesTheKeyToItsPort() {
        when(instances.findByClientCode("viora")).thenReturn(Optional.of(instance("viora", "ACTIVE")));
        when(ops.probeMachineId(9101)).thenReturn(Optional.of("m-host"));

        listener.onReminted(new LicenceRemintedEvent(1L, "viora", "m-host", "new-key"));

        verify(ops).activateLicence(9101, "new-key");
    }

    /**
     * The bug this guards. `viora` holds three licences — two shop machines and its hosted store
     * — and `daman` two. Looking the store up by clientCode alone pushed every one of that
     * client's re-minted keys at the live store, and a key for a DIFFERENT machine is rejected by
     * {@code LicenseService.verifyLicenseKey}, which clears the cached licence features, colours
     * and profile BEFORE the machine check and (unlike attemptRenewal) never restores them. The
     * store then serves a config with no branding and no frontend-gated add-ons to every new
     * visitor until its process restarts, and the 12-hour poll cannot heal it.
     */
    @Test
    void aKeyForADifferentMachineIsNotPushedToTheStore() {
        when(instances.findByClientCode("viora")).thenReturn(Optional.of(instance("viora", "ACTIVE")));
        when(ops.probeMachineId(9101)).thenReturn(Optional.of("m-host"));

        listener.onReminted(new LicenceRemintedEvent(2L, "viora", "m-a-shop-till", "shop-key"));

        verify(ops, never()).activateLicence(anyInt(), anyString());
    }

    /** Cannot tell whose key it is: do nothing. The store polls for its own key anyway. */
    @Test
    void anUnprobableStoreIsNotPushedTo() {
        when(instances.findByClientCode("viora")).thenReturn(Optional.of(instance("viora", "ACTIVE")));
        when(ops.probeMachineId(9101)).thenReturn(Optional.empty());

        listener.onReminted(event());

        verify(ops, never()).activateLicence(anyInt(), anyString());
    }

    /** A desktop licence has no hosted instance; nothing to push to. */
    @Test
    void aRemintForADesktopLicencePushesNothing() {
        when(instances.findByClientCode("viora")).thenReturn(Optional.empty());

        listener.onReminted(event());

        verify(ops, never()).activateLicence(anyInt(), anyString());
    }

    @Test
    void aFailedPushIsSwallowedAndLogged() {
        when(instances.findByClientCode("viora")).thenReturn(Optional.of(instance("viora", "ACTIVE")));
        when(ops.probeMachineId(9101)).thenReturn(Optional.of("m-host"));
        doThrow(new RuntimeException("connection refused"))
                .when(ops).activateLicence(anyInt(), anyString());

        assertThatCode(() -> listener.onReminted(event())).doesNotThrowAnyException();
    }

    /** A suspended store is deliberately not running; poking it would restart nothing and log noise. */
    @Test
    void aSuspendedInstanceIsNotPushedTo() {
        when(instances.findByClientCode("viora")).thenReturn(Optional.of(instance("viora", "SUSPENDED")));

        listener.onReminted(event());

        verify(ops, never()).activateLicence(anyInt(), anyString());
    }

    @Test
    void aStoreStillProvisioningIsNotPushedTo() {
        when(instances.findByClientCode("viora")).thenReturn(Optional.of(instance("viora", "PROVISIONING")));

        listener.onReminted(event());

        verify(ops, never()).activateLicence(anyInt(), anyString());
    }

    /** A repository blow-up must not escape either — the mint is already committed. */
    @Test
    void aRepositoryFailureIsSwallowed() {
        when(instances.findByClientCode("viora")).thenThrow(new RuntimeException("db gone"));

        assertThatCode(() -> listener.onReminted(event())).doesNotThrowAnyException();
        verify(ops, never()).activateLicence(anyInt(), anyString());
    }
}
