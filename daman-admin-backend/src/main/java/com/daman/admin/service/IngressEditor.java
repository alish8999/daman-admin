package com.daman.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure line-based editor for cloudflared's shared {@code config.yml}. It deliberately avoids a
 * YAML object model so comments and formatting of the hand-maintained file survive untouched.
 * The file also routes the admin API and viora, so the trailing catch-all is never dropped:
 * {@link #addRule} throws if there is none rather than risk writing a file that 404s everything.
 */
public final class IngressEditor {

    private static final Pattern CATCH_ALL = Pattern.compile("^(\\s*)-\\s+service:\\s*http_status:\\d+\\s*$");
    private static final Pattern HOSTNAME_LINE =
            Pattern.compile("^(\\s*)-\\s+hostname:\\s*[\"']?([^\"'\\s]+)[\"']?\\s*$");
    private static final Pattern SERVICE_LINE = Pattern.compile("^\\s*service:\\s*(\\S+)\\s*$");
    private static final Pattern VALID_HOSTNAME = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$");

    private IngressEditor() {}

    /**
     * Inserts a hostname -> http://localhost:port rule immediately before the catch-all.
     * Idempotent: an identical existing rule leaves the content unchanged. The same hostname
     * mapped to a different service is refused, never silently rewritten. Output uses LF only.
     */
    public static String addRule(String yaml, String hostname, int port) {
        requireValidHostname(hostname);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        List<String> lines = split(yaml);
        String service = "http://localhost:" + port;

        int existing = indexOfHostname(lines, hostname);
        if (existing >= 0) {
            Matcher m = existing + 1 < lines.size() ? SERVICE_LINE.matcher(lines.get(existing + 1)) : null;
            if (m != null && m.matches() && m.group(1).equals(service)) {
                return join(lines);
            }
            throw new IllegalStateException(
                    "Ingress rule for " + hostname + " already exists with a different service");
        }

        int catchAll = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (CATCH_ALL.matcher(lines.get(i)).matches()) {
                catchAll = i;
                break;
            }
        }
        if (catchAll < 0) {
            throw new IllegalStateException(
                    "cloudflared config has no catch-all (- service: http_status:404) rule; refusing to edit");
        }
        String indent = CATCH_ALL.matcher(lines.get(catchAll)).replaceFirst("$1");
        lines.add(catchAll, indent + "  service: " + service);
        lines.add(catchAll, indent + "- hostname: " + hostname);
        return join(lines);
    }

    /**
     * Removes the rule (hostname line plus its indented continuation lines). No-op if absent.
     *
     * <p><b>Dangerous in the wrong hands:</b> removing a rule is one typo away from dropping
     * {@code admin-api} (the tunnel the provisioning request itself arrives through) or
     * {@code viora-api} from the shared root-owned config. The one sanctioned production caller is
     * {@code HostedStoreLifecycleService.dropIngressRule} (store removal and forget), which is only
     * ever invoked behind {@code HostedStoreLifecycleService.hasOwnApiHostname}: the hostname must
     * be exactly {@code <subdomain>-api} of a registry row we own and not reserved. That guard
     * belongs at the call site, not in here; any new caller needs the same one.
     */
    public static String removeRule(String yaml, String hostname) {
        requireValidHostname(hostname);
        List<String> lines = split(yaml);
        int at = indexOfHostname(lines, hostname);
        if (at < 0) {
            return join(lines);
        }
        int dashIndent = indentOf(lines.get(at));
        int end = at + 1;
        while (end < lines.size() && !lines.get(end).isBlank() && indentOf(lines.get(end)) > dashIndent) {
            end++;
        }
        lines.subList(at, end).clear();
        return join(lines);
    }

    private static int indexOfHostname(List<String> lines, String hostname) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = HOSTNAME_LINE.matcher(lines.get(i));
            if (m.matches() && m.group(2).equalsIgnoreCase(hostname)) {
                return i;
            }
        }
        return -1;
    }

    private static int indentOf(String line) {
        return line.length() - line.stripLeading().length();
    }

    private static void requireValidHostname(String hostname) {
        if (hostname == null || !VALID_HOSTNAME.matcher(hostname).matches()) {
            throw new IllegalArgumentException("Invalid hostname: " + hostname);
        }
    }

    /** Splits on any line ending; a trailing newline yields a final empty element so it round-trips. */
    private static List<String> split(String yaml) {
        return new ArrayList<>(List.of(yaml.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }
}
