package com.daman.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The newest version a shop could actually download right now, read from the update feed the
 * shops themselves poll.
 *
 * <p><b>Why not {@code app_versions}.</b> That table is authoritative on the machine that runs
 * the build — {@code daman.workspace} is a Windows path and the server has no checkout — so the
 * LIVE copy stopped in September with three rows, newest 1.0.3. The portal's "which shops are
 * behind" column measured against it and painted the entire fleet green, including shops four
 * releases back. See CLAUDE.md's per-table authority split.
 *
 * <p><b>Why not the fleet's own highest report either.</b> That was the first correction and it
 * works, but it is a LAGGING signal: it can only say what some shop already installed. On the
 * day a release ships and nobody has taken it yet, it names the release before it, and every
 * shop looks current. {@code latest.yml} is what electron-updater fetches, so it names the
 * version a shop would get if it updated this minute — which is exactly the question.
 *
 * <p>Read server-side rather than from the page: the download host sends no
 * {@code Access-Control-Allow-Origin}, so a browser on admin.damansoft.com cannot read it.
 *
 * <p><b>Never throws and never blocks.</b> A missing or unreachable feed returns null and the
 * caller falls back to what it already knew. The fleet view is not worth an error page.
 */
@Slf4j
@Service
public class PublishedReleaseService {

    /** Both Windows channels: 64-bit and 32-bit can be a release apart. */
    private static final List<String> FEEDS = List.of("latest.yml", "latest-x86.yml");

    /** Only the TOP-LEVEL key. The file also names the version in a filename, a path and the
     *  release notes — free text a human wrote, which a looser search would eventually mine a
     *  version number out of. */
    private static final Pattern VERSION_LINE =
            Pattern.compile("^version:\\s*v?([0-9]+(?:\\.[0-9]+)*)\\s*$", Pattern.MULTILINE);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${daman.release-feed-base:https://dl.damansoft.com}")
    private String feedBase;

    /** Cached: the portal asks on every Clients page load, the feed changes a few times a week. */
    private static final Duration CACHE_FOR = Duration.ofMinutes(10);
    private volatile String cachedVersion;
    private volatile Instant cachedAt;

    /** The newest published version, or null when no feed could be read. */
    public String newestPublishedVersion() {
        Instant at = cachedAt;
        if (at != null && at.isAfter(Instant.now().minus(CACHE_FOR))) {
            return cachedVersion;
        }
        List<String> found = new ArrayList<>();
        for (String feed : FEEDS) {
            found.add(parseVersion(fetch(feed)));
        }
        String newest = highest(found);
        // A failed round of fetches must not erase a version we already knew: a brief outage of
        // the download host would otherwise turn every badge grey.
        if (newest != null || cachedVersion == null) {
            cachedVersion = newest;
        }
        cachedAt = Instant.now();
        return cachedVersion;
    }

    private String fetch(String fileName) {
        String base = feedBase == null ? "" : feedBase.replaceAll("/+$", "");
        String url = base + "/" + fileName;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "text/yaml, text/plain, */*")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Release feed {} returned {}", url, response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            // Includes the interrupt case; restoring the flag matters because this runs on a
            // request thread.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("Release feed {} unreachable: {}", url, e.toString());
            return null;
        }
    }

    /** The {@code version:} value of an electron-updater feed, or null if there is not one. */
    static String parseVersion(String yaml) {
        if (yaml == null || yaml.isBlank()) return null;
        Matcher m = VERSION_LINE.matcher(yaml);
        return m.find() ? m.group(1) : null;
    }

    /** The highest of several versions, ignoring nulls. Null when nothing usable was given. */
    static String highest(List<String> versions) {
        String best = null;
        for (String v : versions) {
            if (v == null || v.isBlank()) continue;
            if (best == null || compare(v, best) > 0) best = v;
        }
        return best;
    }

    /** Part by part as NUMBERS: 1.5.10 is newer than 1.5.9, which a string compare reverses. */
    private static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int d = part(pa, i) - part(pb, i);
            if (d != 0) return d;
        }
        return 0;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
