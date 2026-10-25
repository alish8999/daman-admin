package com.daman.admin.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure rules for what a hosted store's subdomain may be. */
public final class SubdomainRules {

    /** Names that already serve live infrastructure. Any label ending "-api" is reserved too. */
    public static final Set<String> RESERVED = Set.of("admin", "admin-api", "dl", "www", "viora");

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
