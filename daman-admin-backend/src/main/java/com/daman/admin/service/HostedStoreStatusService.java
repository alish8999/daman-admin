package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Live state of the hosted-store fleet: what the registry cannot know on its own. Every OS fact
 * goes through {@link ServerOps}; this class never shells out or opens a socket.
 *
 * <p>{@code liveStatus} and {@code staleness} are separate axes — a store is "running" <em>and</em>
 * "stale". Staleness is three-valued because {@link ServerOps#unitStartedAt} returns an empty
 * Optional both for a never-started unit and for a timestamp it could not parse: an absent
 * timestamp is {@code unknown}, never {@code current}.
 */
@Service
public class HostedStoreStatusService {

    public static final String UNKNOWN = "unknown";

    /** {@code backupSizeBytes} and {@code startedAt} are null when not known. */
    public record LiveState(String liveStatus, String staleness, Instant startedAt,
                            String backupAt, Long backupSizeBytes) {}

    public record FleetState(List<LiveState> states, Instant jarModifiedAt, long totalMemoryMb,
                             int committedStores, int heapMb, int maxStores) {}

    private final ServerOps ops;

    @Autowired
    public HostedStoreStatusService(ServerOps ops) {
        this.ops = ops;
    }

    /** Reads the shared jar's mtime once for the whole fleet; states are in {@code rows} order. */
    public FleetState describe(List<HostedInstance> rows) {
        Instant jarAt = ops.fileModifiedAt(StoreUnitRenderer.SHARED_JAR).orElse(null);
        List<LiveState> states = rows.stream().map(r -> of(r, jarAt)).toList();
        long totalMb = ops.totalMemoryMb();
        return new FleetState(states, jarAt, totalMb, rows.size(), StoreUnitRenderer.HEAP_MB,
                HostedCapacity.maxStores(totalMb, StoreUnitRenderer.HEAP_MB,
                        HostedCapacity.NON_STORE_RESERVE_MB, HostedCapacity.RAM_MARGIN_MB));
    }

    public LiveState of(HostedInstance row, Instant sharedJarModifiedAt) {
        // Reading a backup directory is a filesystem read: it needs no live unit, and it matters
        // most for a suspended store, which the operator reads before deciding to remove it.
        Optional<ServerOps.BackupInfo> backup = newestBackup(row);
        String status = row.getStatus();
        if ("SUSPENDED".equals(status)) return notProbed("suspended", backup);
        if ("FAILED".equals(status)) return notProbed("failed", backup);
        if ("PROVISIONING".equals(status)) return notProbed("starting", backup);

        String live;
        if (!ops.unitActive(row.getServiceName())) {
            live = "stopped";
        } else if (!ops.health(row.getPort())) {
            live = "starting";
        } else {
            live = "running";
        }

        Instant startedAt = ops.unitStartedAt(row.getServiceName()).orElse(null);
        String staleness = UNKNOWN;
        if ("running".equals(live) && startedAt != null && sharedJarModifiedAt != null) {
            staleness = startedAt.isBefore(sharedJarModifiedAt) ? "stale" : "current";
        }

        return new LiveState(live, staleness, startedAt, backupAt(backup), backupSize(backup));
    }

    /** A row with no dataDir yet (early PROVISIONING) has no backups directory to ask about. */
    private Optional<ServerOps.BackupInfo> newestBackup(HostedInstance row) {
        String dir = row.getDataDir();
        if (dir == null || dir.isBlank()) return Optional.empty();
        return ops.newestBackup(dir + "/backups");
    }

    private static String backupAt(Optional<ServerOps.BackupInfo> b) {
        return b.map(x -> x.modifiedAt().toString()).orElse(UNKNOWN);
    }

    private static Long backupSize(Optional<ServerOps.BackupInfo> b) {
        return b.map(ServerOps.BackupInfo::sizeBytes).orElse(null);
    }

    private static LiveState notProbed(String live, Optional<ServerOps.BackupInfo> backup) {
        return new LiveState(live, UNKNOWN, null, backupAt(backup), backupSize(backup));
    }
}
