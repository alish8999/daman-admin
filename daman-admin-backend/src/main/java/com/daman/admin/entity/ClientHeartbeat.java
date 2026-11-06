package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One report from one installed client that it is running, and roughly how busy it has been.
 *
 * <p><b>A row per heartbeat, deliberately — not a {@code lastSeen} column on the client.</b>
 * The obvious shape is a single timestamp overwritten on each ping. It answers "is this client
 * alive today" and destroys every other question: when someone stopped, whether usage is
 * sliding, whether a quiet fortnight was a holiday or a lost customer. A few hundred rows per
 * client per year is nothing, and history cannot be backfilled once it was not kept. The
 * portal's "last seen" is DERIVED from the newest row rather than stored in place of them.
 *
 * <p><b>Counts, never contents.</b> {@code salesSinceLastPing} and {@code activeUserCount} are
 * numbers; {@code lastSaleOn} is a date with no time. No amounts, no customer or product data,
 * nothing that identifies a person. That line is not arbitrary — damansoft.com/privacy.html
 * tells shop owners their records stay on their own machine, and this design stays on the
 * counts side of it on purpose.
 *
 * <p>Gaps are normal. Clients are offline-first and often have intermittent internet; a client
 * that was offline for ten days sends ONE heartbeat on reconnect, not ten. The gap in the
 * history is itself the signal.
 */
@Getter
@Setter
@Entity
@Table(
    name = "client_heartbeats",
    indexes = {
        // The portal's only read pattern: newest row for a client. Without this the Clients
        // list does a full scan per row once this table has a year of history in it.
        @Index(name = "idx_heartbeat_client_received", columnList = "clientCode, receivedAt")
    }
)
public class ClientHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String clientCode;

    /**
     * Which installation sent it. A client code can legitimately have several machines, so this
     * is what separates "the shop has two tills" from "one till is reporting twice".
     */
    @Column(nullable = false)
    private String machineId;

    /** The build actually running out there, which is rarely the one you last published. */
    private String appVersion;

    /**
     * Server time of receipt, NOT a timestamp the client chose.
     *
     * <p>A client's clock is not trustworthy — this project has already dealt with a till
     * running an hour off (2026-11-05). Taking the time here means a wrong clock on the shop
     * floor cannot put a heartbeat in the future or hide one in the past.
     */
    @Column(nullable = false)
    private LocalDateTime receivedAt;

    /**
     * Date of the client's most recent sale, or null if it has never made one.
     *
     * <p>This is the field that separates "the computer is switched on" from "the business is
     * trading" — a dormant shop that leaves the till running looks perfectly healthy without
     * it. A date, not a timestamp: the question is which day, and a time would be more
     * precision than the question has.
     */
    private LocalDate lastSaleOn;

    /**
     * All-time sale count on this installation. CUMULATIVE, not a delta.
     *
     * A delta was the first design and it was unsound: this endpoint answers 200 to everything,
     * including reports it rejects or drops, so a client could never know whether its baseline
     * should advance — and advancing it on a report that was never stored loses those sales
     * from the history for good. A total costs nothing when a report is lost, and the delta
     * between two consecutive rows is a subtraction away, which is what keeping history is for.
     */
    private Long totalSales;

    /** How many staff accounts the installation has — a rough proxy for the size of the shop. */
    private Integer activeUserCount;
}
