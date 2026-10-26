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
                            String backupLabel, Long backupSizeBytes) {}

    public record FleetState(List<LiveState> states, Instant jarModifiedAt, long totalMemoryMb,
                             int committedStores, int heapMb) {}

    private final ServerOps ops;

    @Autowired
    public HostedStoreStatusService(ServerOps ops) {
        this.ops = ops;
    }

    /** Reads the shared jar's mtime once for the whole fleet; states are in {@code rows} order. */
    public FleetState describe(List<HostedInstance> rows) {
        Instant jarAt = ops.fileModifiedAt(StoreUnitRenderer.SHARED_JAR).orElse(null);
        List<LiveState> states = rows.stream().map(r -> of(r, jarAt)).toList();
        return new FleetState(states, jarAt, ops.totalMemoryMb(), rows.size(), StoreUnitRenderer.HEAP_MB);
    }

    public LiveState of(HostedInstance row, Instant sharedJarModifiedAt) {
        String status = row.getStatus();
        if ("SUSPENDED".equals(status)) return notProbed("suspended");
        if ("FAILED".equals(status)) return notProbed("failed");
        if ("PROVISIONING".equals(status)) return notProbed("starting");

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

        Optional<ServerOps.BackupInfo> backup = ops.newestBackup(row.getDataDir() + "/backups");
        return new LiveState(live, staleness, startedAt,
                backup.map(b -> b.modifiedAt().toString()).orElse(UNKNOWN),
                backup.map(ServerOps.BackupInfo::sizeBytes).orElse(null));
    }

    private static LiveState notProbed(String live) {
        return new LiveState(live, UNKNOWN, null, UNKNOWN, null);
    }
}
