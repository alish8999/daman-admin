package com.daman.admin.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The sparse per-licence feature override: how a single licence differs from its client's
 * default feature set.
 *
 * <p><b>Sparse, not a copy, and that is the whole design.</b> An override holds only the keys
 * that DIFFER. A full copy would freeze the licence at the moment it was customised: a flag the
 * owner later turns on for the whole business would never reach it, and nothing in the portal
 * would say so. Sparse means the licence says "identical to the business except these two
 * flags", which stays true as the business changes.
 *
 * <p>{@code null} or blank JSON means <b>inherit everything</b> — the value every existing
 * licence row carries, and the reason the column is nullable.
 *
 * <p>Pure and static on purpose: no Spring bean, no state, so the resolution rule can be tested
 * without a context and read without chasing collaborators.
 */
public final class FeatureOverride {

    private FeatureOverride() { }

    /**
     * The sparse override map stored in {@code licenses.features_override_json}.
     *
     * <p>Never throws. A null, blank, malformed, or non-object document all mean the same thing —
     * "inherit everything" — because the alternative is a hand-edited column being able to fail a
     * save or, worse, a mint. Only genuine booleans are kept: a JSON {@code null} or a
     * {@code "yes"} cannot say which way a flag goes, so it is not an override.
     */
    public static Map<String, Boolean> parse(String json, ObjectMapper mapper) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) return out;
            root.properties().forEach(e -> {
                if (e.getValue() != null && e.getValue().isBoolean()) {
                    out.put(e.getKey(), e.getValue().asBoolean());
                }
            });
        } catch (RuntimeException e) {
            // A hand-edited or truncated column is "inherit", never an error.
            return new LinkedHashMap<>();
        }
        return out;
    }

    /**
     * {@code base} (the client's default set) with {@code override} applied key by key.
     *
     * <p>A key the override omits is inherited. <b>A key the override has but {@code base} does
     * not is ignored</b> — that is how a retired or renamed flag in an old override stops being
     * able to resurrect itself.
     *
     * <p>Neither argument is mutated.
     */
    public static Map<String, Boolean> merge(Map<String, Boolean> base, Map<String, Boolean> override) {
        Map<String, Boolean> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (override != null) {
            override.forEach((k, v) -> {
                if (out.containsKey(k) && v != null) out.put(k, v);
            });
        }
        return out;
    }

    /**
     * The sparse map as JSON for the column, or {@code null} when it is empty — so clearing an
     * override clears the column rather than storing {@code "{}"}, and "inherit everything" has
     * exactly one representation.
     *
     * <p>Key-sorted, so two equal overrides serialise identically and a cheap string comparison
     * can tell whether a save actually changed anything.
     */
    public static String write(Map<String, Boolean> sparse, ObjectMapper mapper) {
        if (sparse == null || sparse.isEmpty()) return null;
        return mapper.writeValueAsString(new TreeMap<>(sparse));
    }
}
