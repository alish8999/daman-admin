package com.daman.admin.repository;

import com.daman.admin.entity.ClientHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ClientHeartbeatRepository extends JpaRepository<ClientHeartbeat, Long> {

    /** The newest heartbeat from one installation — what "last seen" is derived from. */
    Optional<ClientHeartbeat> findFirstByClientCodeOrderByReceivedAtDesc(String clientCode);

    /** That installation's history, newest first, for the client detail view. */
    List<ClientHeartbeat> findByClientCodeOrderByReceivedAtDesc(String clientCode);

    /**
     * The newest heartbeat from one MACHINE — keyed on the pair, unlike
     * {@link #findFirstByClientCodeOrderByReceivedAtDesc}, because the min-interval window is
     * per installation and a client code can cover more than one. Used only to compare the
     * reported app version against the stored one; see the controller's {@code versionChanged}.
     */
    Optional<ClientHeartbeat> findFirstByClientCodeAndMachineIdOrderByReceivedAtDesc(
            String clientCode, String machineId);

    /**
     * The newest heartbeat per client code, in ONE query.
     *
     * <p>The Clients list needs last-seen for every row. Calling
     * {@link #findFirstByClientCodeOrderByReceivedAtDesc} per client is a query per row, which
     * is fine at twenty clients and is the kind of thing that quietly becomes the slowest page
     * in the portal at two hundred.
     *
     * <p>Returns a fixed column list rather than entities: the list shows a handful of fields and
     * has no use for the rest. {@code lastBackupOn} and {@code baseCurrency} are here, not only in
     * the detail view, because their whole value is being scannable DOWN the column — "which shops
     * have no backup" is a question about the fleet, not about one client.
     */
    @Query("SELECT h.clientCode, h.receivedAt, h.lastSaleOn, h.appVersion, "
         + "h.lastBackupOn, h.baseCurrency, h.bootSeconds, h.dbSizeMb FROM ClientHeartbeat h "
         + "WHERE h.receivedAt = ("
         + "    SELECT MAX(h2.receivedAt) FROM ClientHeartbeat h2 WHERE h2.clientCode = h.clientCode"
         + ")")
    List<Object[]> findLatestPerClient();

    /**
     * Whether this installation has already reported since {@code since}.
     *
     * <p>The endpoint is public and a client could be restarted repeatedly — without this, a
     * till rebooted twenty times in an afternoon writes twenty rows and the history stops
     * meaning "one report a day". See ClientHeartbeatController for the ruling.
     */
    boolean existsByClientCodeAndMachineIdAndReceivedAtAfter(
            String clientCode, String machineId, LocalDateTime since);
}
