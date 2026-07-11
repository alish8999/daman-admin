package com.daman.admin.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory admin session store. Good enough for a single-operator local tool —
 * sessions don't need to survive a backend restart.
 */
@Service
public class SessionService {

    private static final long TTL_SECONDS = 12 * 60 * 60; // 12 hours

    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();

    public String createSession() {
        String token = UUID.randomUUID().toString();
        tokens.put(token, Instant.now().plusSeconds(TTL_SECONDS));
        return token;
    }

    public boolean isValid(String token) {
        if (token == null) return false;
        Instant expiry = tokens.get(token);
        if (expiry == null) return false;
        if (Instant.now().isAfter(expiry)) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    public void invalidate(String token) {
        if (token != null) tokens.remove(token);
    }
}
