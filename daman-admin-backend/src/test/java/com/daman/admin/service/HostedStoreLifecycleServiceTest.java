package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.entity.License;
import com.daman.admin.repository.HostedInstanceRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.service.HostedStoreLifecycleService.LifecycleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HostedStoreLifecycleServiceTest {

    private ServerOps ops;
    private HostedInstanceRepository repository;
    private LicenseRepository licenses;
    private HostedInstance row;
    private HostedStoreLifecycleService service;

    @BeforeEach
    void setUp() {
        ops = mock(ServerOps.class);
        repository = mock(HostedInstanceRepository.class);
        licenses = mock(LicenseRepository.class);
        row = row("acme", "ACTIVE");
        when(repository.findByClientCode("acme")).thenReturn(Optional.of(row));
        when(repository.findByClientCode("ghost")).thenReturn(Optional.empty());
        // 3 health polls, no sleep: the tests never wait on a real clock.
        service = new HostedStoreLifecycleService(repository, ops, licenses, 3, 0);
    }

    private static HostedInstance row(String code, String status) {
        HostedInstance h = new HostedInstance();
        h.setClientCode(code);
        h.setPort(8091);
        h.setServiceName("daman-store-" + code);
        h.setDataDir("/root/stores/" + code);
        h.setSubdomain(code);
        h.setApiHostname(code + "-api.damansoft.com");
        h.setStatus(status);
        return h;
    }

    @Test
    void suspendDisablesTheUnitAndMarksTheRow() {
        // disable --now, not stop: Restart=always + enabled means a stop comes back on reboot.
        LifecycleResult r = service.suspend("acme");

        verify(ops).stopAndDisable("daman-store-acme");
        assertThat(row.getStatus()).isEqualTo("SUSPENDED");
        verify(repository).save(row);
        assertThat(r.status()).isEqualTo("SUSPENDED");
    }

    @Test
    void suspendingAnAlreadySuspendedStoreIsANoOpThatReportsSuccess() {
        row.setStatus("SUSPENDED");

        LifecycleResult r = service.suspend("acme");

        assertThat(r.status()).isEqualTo("SUSPENDED");
        verify(ops, never()).stopAndDisable(anyString());
        verify(repository, never()).save(any(HostedInstance.class));
    }

    @Test
    void resumingAnAlreadyRunningStoreIsANoOpThatReportsSuccess() {
        LifecycleResult r = service.resume("acme");

        assertThat(r.status()).isEqualTo("ACTIVE");
        verify(ops, never()).enableAndStart(anyString());
        verify(repository, never()).save(any(HostedInstance.class));
    }

    @Test
    void resumeEnablesStartsAndWaitsForHealthBeforeMarkingActive() {
        row.setStatus("SUSPENDED");
        when(ops.health(8091)).thenReturn(false, false, true);

        LifecycleResult r = service.resume("acme");

        InOrder order = inOrder(ops);
        order.verify(ops).enableAndStart("daman-store-acme");
        order.verify(ops, times(3)).health(8091);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(r.status()).isEqualTo("ACTIVE");
    }

    @Test
    void resumeThatNeverConfirmsHealthyStillMarksTheRowActiveSoItIsNotInvisible() {
        row.setStatus("SUSPENDED");
        when(ops.health(anyInt())).thenReturn(false);

        LifecycleResult r = service.resume("acme");

        // Registry = intent, live axis = reality: SUSPENDED + running would be invisible and
        // skipped by release walks. The unit is deliberately not rolled back (slow cold boot).
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        verify(ops, never()).stopAndDisable(anyString());
        verify(repository).save(row);
        assertThat(r.status()).isEqualTo(HostedStoreLifecycleService.UNHEALTHY);
        assertThat(r.message()).contains("ACTIVE").contains("3").contains("live state");
    }

    @Test
    void restartOneRefusesASuspendedStoreAndPointsAtResume() {
        row.setStatus("SUSPENDED");

        assertThatThrownBy(() -> service.restartOne("acme"))
                .isInstanceOf(HostedStoreLifecycleService.ConflictException.class)
                .hasMessageContaining("Resume");
        verify(ops, never()).restart(anyString());
    }

    @Test
    void restartOneRestartsAnActiveStoreAndWaitsForHealth() {
        when(ops.health(8091)).thenReturn(false, true);

        LifecycleResult r = service.restartOne("acme");

        InOrder order = inOrder(ops);
        order.verify(ops).restart("daman-store-acme");
        order.verify(ops, times(2)).health(8091);
        assertThat(r.status()).isEqualTo("ACTIVE");
    }

    @Test
    void restartOneThatNeverBecomesHealthyReportsUnhealthy() {
        when(ops.health(anyInt())).thenReturn(false);

        LifecycleResult r = service.restartOne("acme");

        assertThat(r.status()).isEqualTo(HostedStoreLifecycleService.UNHEALTHY);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void suspendRefusesAStoreThatIsNotActive() {
        row.setStatus("PROVISIONING");

        assertThatThrownBy(() -> service.suspend("acme"))
                .isInstanceOf(HostedStoreLifecycleService.ConflictException.class);
        verify(ops, never()).stopAndDisable(anyString());
    }

    @Test
    void anUnknownClientIsNotFound() {
        assertThatThrownBy(() -> service.suspend("ghost"))
                .isInstanceOf(HostedStoreLifecycleService.NotFoundException.class);
        assertThatThrownBy(() -> service.resume("ghost"))
                .isInstanceOf(HostedStoreLifecycleService.NotFoundException.class);
        assertThatThrownBy(() -> service.restartOne("ghost"))
                .isInstanceOf(HostedStoreLifecycleService.NotFoundException.class);
    }

    @Test
    void theDefaultHealthWaitIsBoundedUnderTheCloudflareEdgeTimeout() {
        // 25 x 3 s = 75 s < the 100 s after which the edge answers 524.
        assertThat(HostedStoreLifecycleService.HEALTH_ATTEMPTS
                * HostedStoreLifecycleService.HEALTH_INTERVAL_MS).isEqualTo(75_000L);
    }

    // ---- remove -------------------------------------------------------------------------

    private static final String ACME_DB = "/root/stores/acme/.daman/acme/daman_db.sqlite";
    private static final String ACME_ZIP = "/root/removed-stores/acme-" + LocalDate.now() + ".zip";
    private static final String INGRESS = """
            ingress:
              - hostname: admin-api.damansoft.com
                service: http://localhost:8082
              - hostname: acme-api.damansoft.com
                service: http://localhost:8091
              - service: http_status:404
            """;

    private void suspendedAcmeWithDatabase() {
        row.setStatus("SUSPENDED");
        when(ops.fileModifiedAt(ACME_DB)).thenReturn(Optional.of(java.time.Instant.now()));
        when(ops.fileModifiedAt("/root/stores/acme")).thenReturn(Optional.of(java.time.Instant.now()));
        when(ops.readIngress()).thenReturn(INGRESS);
    }

    @Test
    void removeRefusesARunningStoreAndPointsAtSuspendFirst() {
        assertThatThrownBy(() -> service.remove("acme"))
                .isInstanceOf(HostedStoreLifecycleService.ConflictException.class)
                .hasMessageContaining("Suspend");
        verify(ops, never()).stopAndDisable(anyString());
        verify(ops, never()).deleteTree(any(), any());
        verify(repository, never()).delete(any(HostedInstance.class));
    }

    @Test
    void removeRefusesAnUnknownClient() {
        assertThatThrownBy(() -> service.remove("ghost"))
                .isInstanceOf(HostedStoreLifecycleService.NotFoundException.class);
    }

    @Test
    void removeRunsItsStepsInTheSafeOrder() {
        suspendedAcmeWithDatabase();

        HostedStoreLifecycleService.RemoveResult r = service.remove("acme");

        // The copy exists before anything irreversible happens.
        InOrder o = inOrder(ops, repository);
        o.verify(ops).stopAndDisable("daman-store-acme");
        o.verify(ops).zipFileInto(eq(ACME_DB), eq(ACME_ZIP), eq("daman_db.sqlite"));
        o.verify(ops).deleteUnit("daman-store-acme");
        o.verify(ops).writeIngress(any());
        o.verify(ops).reloadCloudflared();
        o.verify(ops).deleteTree("/root/stores/acme", "acme");
        o.verify(repository).delete(row);
        assertThat(r.keptBackup()).isEqualTo(ACME_ZIP);
        assertThat(r.backupSkipped()).isFalse();
    }

    @Test
    void aProvisionedStoresBackupComesFromItsOwnDatabasePathNotTheLegacyOne() {
        suspendedAcmeWithDatabase();

        service.remove("acme");

        verify(ops, never()).zipFileInto(eq("/root/stores/acme/daman_db.sqlite"), any(), any());
        verify(ops).zipFileInto(eq(ACME_DB), any(), any());
    }

    @Test
    void aLegacyStoreIsBackedUpFromBesideItsDataDir() {
        HostedInstance viora = row("viora", "SUSPENDED");
        viora.setDataDir("/root/.daman/viora");
        when(repository.findByClientCode("viora")).thenReturn(Optional.of(viora));
        when(ops.fileModifiedAt("/root/.daman/viora/daman_db.sqlite"))
                .thenReturn(Optional.of(java.time.Instant.now()));
        when(ops.fileModifiedAt("/root/.daman/viora")).thenReturn(Optional.of(java.time.Instant.now()));
        when(ops.readIngress()).thenReturn(INGRESS);

        service.remove("viora");

        verify(ops).zipFileInto(eq("/root/.daman/viora/daman_db.sqlite"),
                eq("/root/removed-stores/viora-" + LocalDate.now() + ".zip"), eq("daman_db.sqlite"));
    }

    @Test
    void removeRefusesASuspendedStoreWhoseDatabaseCannotBeFound() {
        // "No database" for a real customer means we looked in the wrong place, not that they have
        // no data. Skipping the backup there would delete a paying customer with no copy kept.
        row.setStatus("SUSPENDED");
        when(ops.fileModifiedAt("/root/stores/acme")).thenReturn(Optional.of(java.time.Instant.now()));
        when(ops.fileModifiedAt(ACME_DB)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove("acme")).hasMessageContaining("database");
        verify(ops, never()).deleteTree(any(), any());
        verify(ops, never()).deleteUnit(anyString());
        verify(ops, never()).stopAndDisable(anyString());
        verify(repository, never()).delete(any(HostedInstance.class));
    }

    @Test
    void removeRefusesADataDirOutsideTheAllowedRootsBeforeTouchingAnything() {
        suspendedAcmeWithDatabase();
        row.setDataDir("/root/.daman/data");

        assertThatThrownBy(() -> service.remove("acme"))
                .isInstanceOf(HostedStoreLifecycleService.ConflictException.class);
        verify(ops, never()).stopAndDisable(anyString());
    }

    @Test
    void removeRefusesAnApiHostnameThatIsNotThisStoreOwn() {
        suspendedAcmeWithDatabase();
        row.setApiHostname("admin-api.damansoft.com");

        assertThatThrownBy(() -> service.remove("acme"))
                .isInstanceOf(HostedStoreLifecycleService.ConflictException.class)
                .hasMessageContaining("hostname");
        verify(ops, never()).stopAndDisable(anyString());
        verify(ops, never()).writeIngress(any());
    }

    @Test
    void removeDropsTheApiHostnameFromTheIngress() {
        // The cross-tenant leak this exists to prevent: nextFreePort() reissues the freed port,
        // and a surviving rule would point the departed hostname at the new store.
        suspendedAcmeWithDatabase();
        org.mockito.ArgumentCaptor<String> written = org.mockito.ArgumentCaptor.forClass(String.class);

        service.remove("acme");

        verify(ops).writeIngress(written.capture());
        assertThat(written.getValue()).doesNotContain("acme-api.damansoft.com")
                .contains("admin-api.damansoft.com");
    }

    @Test
    void removeIsIdempotentOverAlreadyMissingPieces() {
        // A previous run deleted the data directory and the ingress rule, then died before the row.
        row.setStatus("SUSPENDED");
        when(ops.fileModifiedAt(anyString())).thenReturn(Optional.empty());
        when(ops.readIngress()).thenReturn(INGRESS.replace("acme-api", "gone-api"));

        HostedStoreLifecycleService.RemoveResult r = service.remove("acme");

        assertThat(r.backupSkipped()).isTrue();
        assertThat(r.keptBackup()).isNull();
        verify(ops, never()).zipFileInto(any(), any(), any());
        verify(ops, never()).writeIngress(any());
        verify(ops).deleteTree("/root/stores/acme", "acme");
        verify(repository).delete(row);
        assertThat(r.steps()).anyMatch(s -> s.contains("already"));
    }

    @Test
    void removeRevokesTheLicenceRowUsingTheSameSelectionAsRevokeByClient() {
        suspendedAcmeWithDatabase();
        License old = new License();
        old.setStatus("EXPIRED");
        License live = new License();
        live.setStatus("ACTIVE");
        when(licenses.findByClientCodeOrderByActivatedAtDesc("acme")).thenReturn(List.of(old, live));

        HostedStoreLifecycleService.RemoveResult r = service.remove("acme");

        assertThat(live.getStatus()).isEqualTo("REVOKED");
        assertThat(live.getRevokedAt()).isNotNull();
        assertThat(old.getStatus()).isEqualTo("EXPIRED");
        verify(licenses).save(live);
        assertThat(r.licenceRevoked()).isTrue();
    }

    @Test
    void removeWithNoLicenceRowIsNotAnError() {
        suspendedAcmeWithDatabase();
        when(licenses.findByClientCodeOrderByActivatedAtDesc("acme")).thenReturn(List.of());

        assertThat(service.remove("acme").licenceRevoked()).isFalse();
        verify(repository).delete(row);
    }

    @Test
    void removeDeletesTheRegistryRowLast() {
        suspendedAcmeWithDatabase();

        service.remove("acme");

        InOrder o = inOrder(ops, licenses, repository);
        o.verify(ops).deleteTree(any(), any());
        o.verify(repository).delete(row);
    }

    @Test
    void removeResumesAfterTheUnitIsGoneEvenThoughTheDisableThrows() {
        // The real SystemdServerOps.stopAndDisable throws for a missing unit (systemctl disable
        // exits 1). A Mockito no-op cannot be intolerant, so make it behave like the real one.
        suspendedAcmeWithDatabase();
        org.mockito.Mockito.doThrow(new IllegalStateException("unit does not exist"))
                .when(ops).stopAndDisable(anyString());

        HostedStoreLifecycleService.RemoveResult r = service.remove("acme");

        verify(ops).stopAndDisable("daman-store-acme");
        verify(ops).zipFileInto(any(), any(), any());
        verify(ops).deleteUnit("daman-store-acme");
        verify(repository).delete(row);
        assertThat(r.steps()).anyMatch(s -> s.contains("already stopped or gone"));
    }

    @Test
    void aCrlfIngressFileWithoutTheRuleIsNotRewrittenAndCloudflaredIsNotRestarted() {
        suspendedAcmeWithDatabase();
        when(ops.readIngress()).thenReturn(INGRESS.replace("acme-api", "gone-api").replace("\n", "\r\n"));

        service.remove("acme");

        verify(ops, never()).writeIngress(any());
        verify(ops, never()).reloadCloudflared();
    }

    @Test
    void aUnitThatIsMidRestartIsStillStoppedBeforeTheBackupIsTaken() {
        // unitActive would have said false for an activating/deactivating unit; the stop must be
        // attempted regardless so the copy is never taken from a live database.
        // NOTE: the unitActive stub below is decorative - remove() no longer calls unitActive at all.
        // The test is still meaningful: its InOrder verify would fail if a unitActive gate were ever
        // reintroduced, which is the regression it names.
        suspendedAcmeWithDatabase();
        when(ops.unitActive("daman-store-acme")).thenReturn(false);

        service.remove("acme");

        InOrder o = inOrder(ops);
        o.verify(ops).stopAndDisable("daman-store-acme");
        o.verify(ops).zipFileInto(any(), any(), any());
    }
}
