package com.daman.admin.controller;

import com.daman.admin.config.TrialRateLimiter;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.service.LicenseKeyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * ANONYMOUS (listed in AuthFilter.PUBLIC_PATHS). Lets a desktop install fetch its OWN
 * renewed licence instead of the owner emailing a key for the client to paste by hand.
 *
 * <h2>Why this is safe to expose</h2>
 * The caller must PRESENT a validly signed licence, and the only thing it can learn is the
 * current key for the very machine and client that licence already names. So a caller who
 * can use this endpoint at all already holds the credentials inside the response - there is
 * no new disclosure. An attacker without a signed licence learns nothing, and one who
 * somehow has a victim's licence gains nothing either: every licence is machine-bound, so
 * the key only runs on the machine it was minted for.
 *
 * <p>Contrast {@code /api/licenses/activate}, removed on 2026-10-19: that MINTED a licence
 * for a caller-supplied machineId off a guessable clientCode. This endpoint mints nothing,
 * writes nothing, and cannot be aimed at a machine the caller has no licence for.
 *
 * <p>machineId and clientCode are read from the SIGNED payload, never from the request body -
 * a body-supplied machineId would turn this straight back into a read-oracle for other
 * machines' keys.
 *
 * <p>Expiry and revocation of the presented key are deliberately NOT checked: renewing an
 * expired licence is the entire point. What governs the answer is the CURRENT row - only an
 * {@code ACTIVE} licence is returned, so revoking a client's licence stops renewal working,
 * which is the intended lever for non-payment.
 */
@Slf4j
@RestController
@RequestMapping("/api/license")
@RequiredArgsConstructor
public class LicenseRenewalController {

    private final LicenseKeyService licenseKeyService;
    private final LicenseRepository licenses;
    private final TrialRateLimiter limiter;
    private final ObjectMapper objectMapper;

    @PostMapping("/renewal")
    public ResponseEntity<?> renewal(HttpServletRequest http) {
        // Same guard order as TrialController, cheapest first, all before any row is read:
        // size cap (the body is never buffered past it), JSON parse, rate limit, then work.
        if (http.getContentLengthLong() > TrialRateLimiter.MAX_BODY_BYTES) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large");
        }
        byte[] raw;
        try {
            raw = TrialRateLimiter.readCapped(http.getInputStream());
        } catch (IOException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        if (raw == null) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large");
        }
        String presented;
        try {
            JsonNode body = raw.length == 0 ? null : objectMapper.readTree(raw);
            presented = body == null ? null : body.path("licenseKey").asString(null);
        } catch (RuntimeException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        if (presented == null || presented.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }

        JsonNode payload = licenseKeyService.verifiedPayload(presented);
        if (payload == null) {
            // Not a licence we minted. Rate-limited on the machine id we could not read, so
            // this branch is limited per IP only - which is what stops it being a signing oracle.
            if (!limiter.allowRestore(clientIp(http), "")) {
                return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
            }
            return error(HttpStatus.BAD_REQUEST, "invalid_license");
        }

        String machineId = payload.path("machineId").asString("");
        String clientCode = payload.path("clientCode").asString("");
        if (machineId.isBlank() || clientCode.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_license");
        }
        if (!limiter.allowRestore(clientIp(http), machineId)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }

        var current = licenses.findByMachineIdAndClientCodeAndStatus(machineId, clientCode, "ACTIVE");
        if (current.isEmpty()) {
            // Revoked, deleted, or never existed. Same answer for all three on purpose: the
            // desktop's behaviour is identical (keep what it has) and distinguishing them
            // would tell an anonymous caller about this client's account state.
            log.info("Renewal refused: no ACTIVE licence for machineId={} clientCode={}", machineId, clientCode);
            return error(HttpStatus.NOT_FOUND, "no_license_for_machine");
        }
        return ResponseEntity.ok(Map.of("licenseKey", current.get().getLicenseKey()));
    }

    /** Same loopback/CF-Connecting-IP rule as TrialController - see the note there. */
    private static String clientIp(HttpServletRequest http) {
        String peer = http.getRemoteAddr();
        if (peer != null && (peer.equals("127.0.0.1") || peer.equals("::1") || peer.equals("0:0:0:0:0:0:0:1"))) {
            String cf = http.getHeader("CF-Connecting-IP");
            if (cf != null && !cf.isBlank()) {
                return cf.trim();
            }
        }
        return peer == null ? "" : peer;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
