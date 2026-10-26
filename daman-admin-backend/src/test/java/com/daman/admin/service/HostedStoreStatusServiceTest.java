package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HostedStoreStatusServiceTest {

    private static final Instant JAR_AT = Instant.parse("2026-10-26T14:56:31Z");

    private ServerOps ops;
    private HostedStoreStatusService service;
    private HostedInstance row;

    @BeforeEach
    void setUp() {
        ops = mock(ServerOps.class);
        service = new HostedStoreStatusService(ops);
        row = row("acme", "ACTIVE");
        when(ops.unitActive(anyString())).thenReturn(true);
        when(ops.health(Mockito.anyInt())).thenReturn(true);
        when(ops.unitStartedAt(anyString())).thenReturn(Optional.of(JAR_AT.plusSeconds(10)));
        when(ops.newestBackup(anyString())).thenReturn(Optional.of(
                new ServerOps.BackupInfo("b.sqlite", 4096L, Instant.parse("2026-10-26T03:00:00Z"))));
        when(ops.totalMemoryMb()).thenReturn(3814L);
    }

    private static HostedInstance row(String code, String status) {
        HostedInstance i = new HostedInstance();
        i.setClientCode(code);
        i.setPort(8091);
        i.setServiceName("daman-store-" + code);
        i.setDataDir("/root/stores/" + code);
        i.setStatus(status);
        return i;
    }

    @Test
    void staleWhenTheUnitStartedBeforeTheJarWasWritten() {
        when(ops.unitStartedAt("daman-store-acme"))
                .thenReturn(Optional.of(Instant.parse("2026-10-26T14:50:00Z")));
        var s = service.of(row, JAR_AT);
        assertThat(s.liveStatus()).isEqualTo("running");
        assertThat(s.staleness()).isEqualTo("stale");
    }

    @Test
    void currentWhenTheUnitStartedAfterTheJarWasWritten() {
        when(ops.unitStartedAt("daman-store-acme"))
                .thenReturn(Optional.of(Instant.parse("2026-10-26T14:56:41Z")));
        assertThat(service.of(row, JAR_AT).staleness()).isEqualTo("current");
    }

    @Test
    void unknownStalenessWhenTheStartTimeCouldNotBeRead() {
        // unitStartedAt() returns empty both for a never-started unit AND for a timestamp it failed
        // to parse. Reporting "current" there would make a parse bug look like a healthy fleet.
        when(ops.unitStartedAt(anyString())).thenReturn(Optional.empty());
        var s = service.of(row, JAR_AT);
        assertThat(s.liveStatus()).isEqualTo("running");
        assertThat(s.staleness()).isEqualTo("unknown");
        assertThat(s.startedAt()).isNull();
    }

    @Test
    void unknownStalenessWhenTheJarTimestampCouldNotBeRead() {
        assertThat(service.of(row, null).staleness()).isEqualTo("unknown");
    }

    @Test
    void onlyARunningStoreIsEverStale() {
        when(ops.unitStartedAt(anyString())).thenReturn(Optional.of(JAR_AT.minusSeconds(600)));
        when(ops.unitActive(anyString())).thenReturn(false);
        assertThat(service.of(row, JAR_AT).staleness()).isEqualTo("unknown");
        assertThat(service.of(row("s", "SUSPENDED"), JAR_AT).staleness()).isEqualTo("unknown");
    }

    @Test
    void suspendedRowIsSuspendedWhateverTheUnitSays() {
        assertThat(service.of(row("s", "SUSPENDED"), JAR_AT).liveStatus()).isEqualTo("suspended");
    }

    @Test
    void activeRowWithADeadUnitReadsStopped() {
        when(ops.unitActive(anyString())).thenReturn(false);
        assertThat(service.of(row, JAR_AT).liveStatus()).isEqualTo("stopped");
    }

    @Test
    void activeUnitThatIsNotYetHealthyReadsStarting() {
        when(ops.health(Mockito.anyInt())).thenReturn(false);
        var s = service.of(row, JAR_AT);
        assertThat(s.liveStatus()).isEqualTo("starting");
        assertThat(s.staleness()).isEqualTo("unknown");
    }

    @Test
    void failedRowReadsFailedWithoutProbingTheUnit() {
        assertThat(service.of(row("f", "FAILED"), JAR_AT).liveStatus()).isEqualTo("failed");
        verify(ops, never()).unitActive(anyString());
        verify(ops, never()).health(Mockito.anyInt());
        verify(ops, never()).unitStartedAt(anyString());
    }

    @Test
    void suspendedRowStillReportsItsBackup() {
        var s = service.of(row("s", "SUSPENDED"), JAR_AT);
        assertThat(s.liveStatus()).isEqualTo("suspended");
        assertThat(s.backupAt()).isEqualTo("2026-10-26T03:00:00Z");
        assertThat(s.backupSizeBytes()).isEqualTo(4096L);
        verify(ops, never()).unitActive(anyString());
        verify(ops, never()).health(Mockito.anyInt());
        verify(ops, never()).unitStartedAt(anyString());
    }

    @Test
    void provisioningRowIsNotProbed() {
        var s = service.of(row("p", "PROVISIONING"), JAR_AT);
        assertThat(s.liveStatus()).isEqualTo("starting");
        verify(ops, never()).unitActive(anyString());
        verify(ops, never()).health(Mockito.anyInt());
        verify(ops, never()).unitStartedAt(anyString());
    }

    @Test
    void oneRowThatThrowsDegradesToUnknownAndTheOthersSurvive() {
        when(ops.newestBackup("/root/stores/bad/backups")).thenThrow(new java.io.UncheckedIOException(new java.io.IOException("denied")));
        var fleet = service.describe(List.of(row("good", "ACTIVE"), row("bad", "ACTIVE"), row("good2", "ACTIVE")));
        assertThat(fleet.states()).hasSize(3);
        assertThat(fleet.states().get(0).liveStatus()).isEqualTo("running");
        assertThat(fleet.states().get(1).liveStatus()).isEqualTo("unknown");
        assertThat(fleet.states().get(1).staleness()).isEqualTo("unknown");
        assertThat(fleet.states().get(2).liveStatus()).isEqualTo("running");
    }

    @Test
    void rowWithNoDataDirReadsBackupUnknownWithoutAskingTheDisk() {
        HostedInstance early = row("e", "PROVISIONING");
        early.setDataDir(null);
        var s = service.of(early, JAR_AT);
        assertThat(s.backupAt()).isEqualTo("unknown");
        verify(ops, never()).newestBackup(anyString());
        early.setDataDir("  ");
        assertThat(service.of(early, JAR_AT).backupAt()).isEqualTo("unknown");
        verify(ops, never()).newestBackup(anyString());
    }

    @Test
    void absentBackupDirectoryReadsUnknownAndDoesNotThrow() {
        // A store whose owner redirected its backups is not a store without them.
        when(ops.newestBackup(anyString())).thenReturn(Optional.empty());
        var s = service.of(row, JAR_AT);
        assertThat(s.backupAt()).isEqualTo("unknown");
        assertThat(s.backupSizeBytes()).isNull();
    }

    @Test
    void backupIsReadFromTheStoresBackupsDirectory() {
        var s = service.of(row, JAR_AT);
        verify(ops).newestBackup("/root/stores/acme/backups");
        assertThat(s.backupAt()).isEqualTo("2026-10-26T03:00:00Z");
        assertThat(s.backupSizeBytes()).isEqualTo(4096L);
    }

    @Test
    void describeReadsTheJarTimestampOncePerFleetNotOncePerStore() {
        when(ops.fileModifiedAt(StoreUnitRenderer.SHARED_JAR)).thenReturn(Optional.of(JAR_AT));
        var fleet = service.describe(List.of(row("a", "ACTIVE"), row("b", "ACTIVE"), row("c", "ACTIVE")));
        verify(ops, times(1)).fileModifiedAt(anyString());
        assertThat(fleet.states()).hasSize(3);
        assertThat(fleet.committedStores()).isEqualTo(3);
        assertThat(fleet.totalMemoryMb()).isEqualTo(3814L);
        assertThat(fleet.heapMb()).isEqualTo(StoreUnitRenderer.HEAP_MB);
        assertThat(fleet.maxStores()).isEqualTo(HostedCapacity.maxStores(3814L, StoreUnitRenderer.HEAP_MB,
                HostedCapacity.NON_STORE_RESERVE_MB, HostedCapacity.RAM_MARGIN_MB));
        assertThat(fleet.maxStores()).isEqualTo(3);
        // One reading feeds both fields: two shell-outs could disagree with each other.
        verify(ops, times(1)).totalMemoryMb();
        assertThat(fleet.jarModifiedAt()).isEqualTo(JAR_AT);
    }

    private static final ServerOps.BoxStats BOX = new ServerOps.BoxStats(
            3906292L * 1024, 1596532L * 1024, 0.01, 2, 39956590592L, 3871309824L, 3687582L);

    @Test
    void aRunningStoreCarriesItsOwnMemory() {
        when(ops.storeMemoryBytes("daman-store-acme")).thenReturn(Optional.of(682250240L));
        assertThat(service.of(row, JAR_AT).memoryBytes()).isEqualTo(682250240L);
    }

    @Test
    void anUnreadableMemoryFileIsNullNotZero() {
        when(ops.storeMemoryBytes(anyString())).thenReturn(Optional.empty());
        var s = service.of(row, JAR_AT);
        assertThat(s.liveStatus()).isEqualTo("running");
        assertThat(s.memoryBytes()).isNull();
    }

    @Test
    void aStoppedOrSuspendedStoreIsNotAskedForMemory() {
        when(ops.unitActive(anyString())).thenReturn(false);
        assertThat(service.of(row, JAR_AT).memoryBytes()).isNull();
        assertThat(service.of(row("s", "SUSPENDED"), JAR_AT).memoryBytes()).isNull();
        verify(ops, never()).storeMemoryBytes(anyString());
    }

    @Test
    void aThrowingMemoryReadDegradesOnlyThatRow() {
        when(ops.storeMemoryBytes("daman-store-bad")).thenThrow(new IllegalStateException("boom"));
        when(ops.storeMemoryBytes("daman-store-good")).thenReturn(Optional.of(5L));
        var fleet = service.describe(List.of(row("good", "ACTIVE"), row("bad", "ACTIVE")));
        assertThat(fleet.states().get(0).memoryBytes()).isEqualTo(5L);
        // Only the memory cell degrades; the store keeps its real status and staleness.
        assertThat(fleet.states().get(1).liveStatus()).isEqualTo("running");
        assertThat(fleet.states().get(1).backupAt()).isNotEqualTo("unknown");
        assertThat(fleet.states().get(1).memoryBytes()).isNull();
    }

    @Test
    void describeCarriesTheBoxStatsAndAnUnreadableBoxLeavesTheRestIntact() {
        when(ops.boxStats()).thenReturn(Optional.of(BOX));
        assertThat(service.describe(List.of(row("a", "ACTIVE"))).boxStats()).isEqualTo(BOX);

        when(ops.boxStats()).thenReturn(Optional.empty());
        var fleet = service.describe(List.of(row("a", "ACTIVE")));
        assertThat(fleet.boxStats()).isNull();
        assertThat(fleet.states()).hasSize(1);
        assertThat(fleet.totalMemoryMb()).isEqualTo(3814L);
    }
}
