package com.daman.admin.service;

import com.daman.admin.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Server-side trial knobs, stored in {@code app_settings} as bare strings (not JSON-encoded).
 * Unset, blank, unparseable and out-of-range values all resolve to the default; the latter
 * three log at warn so a misconfiguration is visible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrialSettingsService {

    static final String KEY_DAYS = "trial.days";
    static final String KEY_ENABLED = "trial.enabled";
    static final String KEY_GRACE_DAYS = "trial.graceDays";
    static final String KEY_PRESETS = "trial.presets";

    static final int DEFAULT_DAYS = 7;
    static final int DEFAULT_GRACE_DAYS = 2;

    private final AppSettingRepository appSettingRepository;

    /** Trial length in days; always >= 1. */
    public int days() {
        return intSetting(KEY_DAYS, DEFAULT_DAYS, 1);
    }

    /** Grace period after expiry in days; 0 is allowed. */
    public int graceDays() {
        return intSetting(KEY_GRACE_DAYS, DEFAULT_GRACE_DAYS, 0);
    }

    /** False only for an explicit "false"; anything else (including garbage) leaves trials on. */
    public boolean enabled() {
        String raw = raw(KEY_ENABLED);
        if (raw == null) {
            return true;
        }
        String v = raw.trim();
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        if (!v.isEmpty() && !v.equalsIgnoreCase("true")) {
            log.warn("Setting {} has unrecognised value '{}'; using default true", KEY_ENABLED, raw);
        }
        return true;
    }

    /** Raw presets-override JSON, or null when unset/blank. */
    public String presetsJson() {
        String raw = raw(KEY_PRESETS);
        return raw == null || raw.isBlank() ? null : raw;
    }

    private int intSetting(String key, int def, int min) {
        String raw = raw(key);
        if (raw == null) {
            return def;
        }
        if (raw.isBlank()) {
            log.warn("Setting {} is blank; using default {}", key, def);
            return def;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < min) {
                log.warn("Setting {} value '{}' is out of range (min {}); using default {}", key, raw, min, def);
                return def;
            }
            return n;
        } catch (NumberFormatException e) {
            log.warn("Setting {} value '{}' is not a number; using default {}", key, raw, def);
            return def;
        }
    }

    private String raw(String key) {
        return appSettingRepository.findBySettingKey(key)
                .map(s -> s.getValueJson())
                .orElse(null);
    }
}
