package com.daman.admin.controller;

import com.daman.admin.config.TrialRateLimiter;
import com.daman.admin.dto.TrialRegisterRequest;
import com.daman.admin.service.NoTrialForMachineException;
import com.daman.admin.service.TrialAlreadyUsedException;
import com.daman.admin.service.TrialRegistrationService;
import com.daman.admin.service.TrialsDisabledException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * ANONYMOUS (listed in AuthFilter.PUBLIC_PATHS). Keep this thin and keep its responses
 * free of anything but the licence key: error bodies are fixed strings that echo nothing.
 *
 * <p>Guard order, cheapest first, all before any row is touched: body size cap (413, the body
 * is never buffered past the cap and never parsed), JSON parse (400), rate limit (429), then the
 * service. The body is read by hand rather than via {@code @RequestBody} precisely so the cap
 * applies before a single byte is materialised into a String.
 */
@RestController
@RequestMapping("/api/trial")
@RequiredArgsConstructor
public class TrialController {

    private final TrialRegistrationService service;
    private final TrialRateLimiter limiter;
    private final ObjectMapper objectMapper;

    @PostMapping("/register")
    public ResponseEntity<?> register(HttpServletRequest http) {
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
        TrialRegisterRequest body;
        try {
            body = raw.length == 0 ? null : objectMapper.readValue(raw, TrialRegisterRequest.class);
        } catch (RuntimeException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        if (body == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        // A restoreOnly probe writes nothing and mints nothing, so it gets its own, more
        // generous bucket - sharing the registration one would let a few button clicks lock
        // the user out of registering. See TrialRateLimiter.allowRestore.
        boolean restoreOnly = Boolean.TRUE.equals(body.restoreOnly());
        boolean allowed = restoreOnly
                ? limiter.allowRestore(clientIp(http), body.machineId())
                : limiter.allow(clientIp(http), body.machineId());
        if (!allowed) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        try {
            return ResponseEntity.ok(service.register(body, body.machineId(), body.deviceInfo()));
        } catch (TrialsDisabledException e) {
            return error(HttpStatus.FORBIDDEN, "trials_disabled");
        } catch (TrialAlreadyUsedException e) {
            return error(HttpStatus.CONFLICT, "trial_already_used");
        } catch (NoTrialForMachineException e) {
            return error(HttpStatus.NOT_FOUND, "no_trial_for_machine");
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
    }

    /**
     * admin-api.damansoft.com sits behind a Cloudflare tunnel, so the TCP peer is the local
     * cloudflared (loopback) for every caller; the real address is in CF-Connecting-IP. That
     * header is trusted ONLY when the peer is loopback, so a direct caller cannot spoof it.
     */
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
