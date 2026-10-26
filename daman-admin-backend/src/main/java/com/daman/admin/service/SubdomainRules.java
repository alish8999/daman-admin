package com.daman.admin.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure rules for what a hosted store's subdomain may be. */
public final class SubdomainRules {

    /** Names that already serve live infrastructure. Any label ending "-api" is reserved too. */
    /**
     * Subdomains and client codes Daman's own infrastructure owns. A client code in here is
     * refused by provisioning, because the service name is derived from it and a provision would
     * overwrite the matching unit.
     *
     * <p>{@code viora} was in this set until 2026-10-27. It was there to protect viora's
     * hand-written unit — the one unit on the box not rendered by {@link StoreUnitRenderer},
     * carrying no {@code -Duser.home} and therefore keeping its data at {@code /root/.daman/viora}
     * instead of {@code /root/stores/viora}. That bespoke shape made Remove a one-way door for
     * viora alone: the reserved check is only applied when no registry row exists, so viora was
     * exempt purely because its row existed, and a Remove would have deleted the row and made a
     * re-Create impossible.
     *
     * <p>It was removed as the first step of normalising viora into an ordinary store (tear down,
     * re-provision onto the rendered unit and {@code /root/stores/viora}, restore the database).
     * Once viora has a registry row again the entry is inert anyway, since the check is skipped
     * for an existing row — so it is not worth putting back.
     */
    public static final Set<String> RESERVED = Set.of("admin", "admin-api", "dl", "www");

    private static final Pattern VALID = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$");

    private static final int MAX_LENGTH = 32;

    private SubdomainRules() {}

    /**
     * Suggests a subdomain for a client code: lowercases, maps '_' to '-', drops other illegal
     * characters, truncates to 32 characters and trims edge hyphens. Returns "" when nothing
     * usable remains (null, or no ASCII alphanumerics, e.g. an Arabic-only code). No fallback
     * label is invented: callers must treat the result as a suggestion and check
     * {@link #isValid(String)} before using it.
     */
    public static String sanitise(String clientCode) {
        if (clientCode == null) return "";
        String s = clientCode.toLowerCase(Locale.ROOT).replace('_', '-').replaceAll("[^a-z0-9-]", "");
        s = s.replaceAll("^-+", "");
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH);
        return s.replaceAll("-+$", "");
    }

    public static boolean isValid(String subdomain) {
        return subdomain != null && VALID.matcher(subdomain).matches();
    }

    public static boolean isReserved(String subdomain) {
        return subdomain != null && (RESERVED.contains(subdomain) || subdomain.endsWith("-api"));
    }
}
