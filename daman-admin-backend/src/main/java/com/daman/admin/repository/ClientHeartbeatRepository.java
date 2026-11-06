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
     * The newest heartbeat per client code, in ONE query.
     *
     * <p>The Clients list needs last-seen for every row. Calling
     * {@link #findFirstByClientCodeOrderByReceivedAtDesc} per client is a query per row, which
     * is fine at twenty clients and is the kind of thing that quietly becomes the slowest page
     * in the portal at two hundred.
     *
     * <p>Returns {@code [clientCode, receivedAt, lastSaleOn, appVersion]} rather than entities:
     * the list shows four fields and has no use for the rest.
     */
    @Query("SELECT h.clientCode, h.receivedAt, h.lastSaleOn, h.appVersion FROM ClientHeartbeat h "
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
