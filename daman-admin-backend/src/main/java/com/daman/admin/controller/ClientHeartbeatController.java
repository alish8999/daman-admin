package com.daman.admin.controller;

import com.daman.admin.entity.ClientHeartbeat;
import com.daman.admin.entity.License;
import com.daman.admin.repository.ClientHeartbeatRepository;
import com.daman.admin.repository.LicenseRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Receives the daily "I am running" report from an installed client.
 *
 * <p>Anonymous, like its neighbours in {@code AuthFilter.PUBLIC_PATHS} — a shipped POS install
 * holds no admin credential. Anonymous is not the same as unverified: the body carries the
 * licence this machine was issued, and a report is only recorded when {@code clientCode},
 * {@code machineId} and {@code licenseKey} together match an ACTIVE licence row. Forging
 * another shop's activity therefore requires their licence file, which is the same bar as
 * running their software.
 *
 * <p><b>Every outcome is 200 with an empty body.</b> Three separate reasons, and all three
 * matter:
 * <ul>
 *   <li>A prober learns nothing. "404 unknown client" versus "200 accepted" would turn this
 *       into an oracle for which client codes exist.</li>
 *   <li>A client whose licence has been revoked or re-issued does not start retrying, logging
 *       errors, or showing anything to a cashier. It reports into the void, exactly as the
 *       design requires.</li>
 *   <li>The caller has nothing to do with the answer either way. It is fire-and-forget by
 *       design; giving it a failure to handle would only create a way for this to matter.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ClientHeartbeatController {

    /**
     * How close together two reports from one installation may be before the second is
     * dropped.
     *
     * <p>The client sends on boot and then daily, but a till that is switched off and on
     * repeatedly — or a shop that restarts the app to clear a problem — would otherwise write a
     * row per restart, and the history would stop meaning "one report a day". Twelve hours
     * rather than twenty-four so a genuine once-a-day schedule is never silently skipped
     * because yesterday's report happened to land slightly later in the day.
     */
    private static final int MIN_HOURS_BETWEEN = 12;

    private final ClientHeartbeatRepository heartbeatRepository;
    private final LicenseRepository licenseRepository;

    /**
     * Last-seen for every client that has ever reported, for the portal's Clients list.
     *
     * <p>Behind the admin session (not under {@code /api/public/}), and one query rather than
     * one per client — the list renders a row per client, and a per-row query is the kind of
     * thing that is invisible at twenty clients and is the slowest page in the portal at two
     * hundred.
     *
     * <p>Returns only what has actually been reported. A client with no row is simply absent,
     * and the portal decides what to call that — which matters more than it looks during
     * rollout, when "never reported" mostly means "has not taken the update yet" rather than
     * "has stopped trading".
     */
    @GetMapping("/api/client-heartbeats/latest")
    public List<LatestHeartbeat> latest() {
        return heartbeatRepository.findLatestPerClient().stream()
                .map(r -> new LatestHeartbeat(
                        (String) r[0],
                        (LocalDateTime) r[1],
                        (LocalDate) r[2],
                        (String) r[3]))
                .toList();
    }

    /** One client's reported history, newest first, for the client detail view. */
    @GetMapping("/api/client-heartbeats/{clientCode}")
    public List<ClientHeartbeat> history(@PathVariable String clientCode) {
        return heartbeatRepository.findByClientCodeOrderByReceivedAtDesc(clientCode);
    }

    public record LatestHeartbeat(
            String clientCode,
            LocalDateTime lastSeenAt,
            LocalDate lastSaleOn,
            String appVersion) {}

    @PostMapping("/api/public/client-heartbeat")
    public ResponseEntity<Void> receive(@RequestBody HeartbeatRequest body) {
        try {
            record(body);
        } catch (Exception e) {
            // A heartbeat is the least important thing this server does. It must never return a
            // 500 that a client might interpret, retry, or surface — and it must never take the
            // portal down with it.
            log.warn("Heartbeat rejected for {}: {}",
                    body != null ? body.getClientCode() : "(no body)", e.toString());
        }
        return ResponseEntity.ok().build();
    }

    private void record(HeartbeatRequest body) {
        if (body == null || isBlank(body.getClientCode())
                || isBlank(body.getMachineId()) || isBlank(body.getLicenseKey())) {
            return;
        }

        // findAll…OrderByIdDesc, not the Optional variant: two ACTIVE rows for one
        // machine+client is possible after a re-issue, and the Optional form throws on it.
        // Dropping a real heartbeat because a licence was re-issued would be absurd.
        List<License> active = licenseRepository.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc(
                body.getMachineId(), body.getClientCode(), "ACTIVE");
        // previousLicenseKey is accepted too, and that is not laxness — it is the difference
        // between a signal and a lie. A renewal or a v2 re-issue ROTATES licence_key in place,
        // while the client only picks the new one up when LicenseRenewalScheduler next polls,
        // every 12 hours. Matching on the current key alone would silently drop reports from a
        // perfectly healthy shop for up to half a day after every renewal — and in the portal
        // that gap is indistinguishable from "this shop has closed", which is the exact
        // question this whole feature exists to answer.
        boolean keyMatches = active.stream().anyMatch(l ->
                body.getLicenseKey().equals(l.getLicenseKey())
                        || body.getLicenseKey().equals(l.getPreviousLicenseKey()));
        if (!keyMatches) return;

        LocalDateTime now = LocalDateTime.now();
        if (heartbeatRepository.existsByClientCodeAndMachineIdAndReceivedAtAfter(
                body.getClientCode(), body.getMachineId(), now.minusHours(MIN_HOURS_BETWEEN))) {
            return;
        }

        ClientHeartbeat row = new ClientHeartbeat();
        row.setClientCode(body.getClientCode());
        row.setMachineId(body.getMachineId());
        row.setAppVersion(trimToLength(body.getAppVersion(), 40));
        // Server time, never the client's: a till with a wrong clock must not be able to place
        // a heartbeat in the future or bury one in the past.
        row.setReceivedAt(now);
        row.setLastSaleOn(body.getLastSaleOn());
        row.setTotalSales(nonNegativeLong(body.getTotalSales()));
        row.setActiveUserCount(nonNegative(body.getActiveUserCount()));
        heartbeatRepository.save(row);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Defensive: these land in a column, and nothing upstream guarantees a sane length. */
    private static String trimToLength(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** A negative count is meaningless and would only corrupt a future average. */
    private static Long nonNegativeLong(Long n) {
        if (n == null) return null;
        return n < 0 ? 0L : n;
    }

    private static Integer nonNegative(Integer n) {
        if (n == null) return null;
        return n < 0 ? 0 : n;
    }

    @Data
    public static class HeartbeatRequest {
        private String clientCode;
        private String machineId;
        private String licenseKey;
        private String appVersion;
        /** Date only — the question is which day the shop last sold, not at what time. */
        private LocalDate lastSaleOn;
        private Long totalSales;
        private Integer activeUserCount;
    }
}
