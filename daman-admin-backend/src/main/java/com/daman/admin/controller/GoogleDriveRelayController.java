package com.daman.admin.controller;

import com.daman.admin.repository.HostedInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The one Google OAuth redirect URI registered for every hosted store.
 *
 * <p><b>Why.</b> Google has no API for adding an authorized redirect URI to a standard OAuth
 * client — {@code projects.locations.oauthClients} is Workforce Identity Federation only, and
 * standard client IDs are managed in the Cloud console by hand. So a store provisioned by one
 * click still needed a console visit before its Drive backup could work. Registering
 * {@code https://admin-api.damansoft.com/api/backup/google-drive/callback} once and relaying from
 * here removes that step permanently.
 *
 * <p>The path deliberately matches the store's own callback path exactly, because the POS's
 * {@code GoogleDriveBackupService.validateRedirectUri} requires that path — so this URI passes the
 * store-side validator with no change to it.
 *
 * <p><b>ANONYMOUS</b> (listed in {@code AuthFilter.PUBLIC_PATHS}): Google redirects the shop
 * owner's browser here and a browser cannot carry an admin token.
 *
 * <p><b>This endpoint 302s to a host named in a query parameter, which is the exact shape of an
 * open redirect.</b> Someone who could steer it at a host they control would be handed a Google
 * authorization code meant for a shop's Drive. Two things prevent that, and neither is optional:
 * the origin must parse to a bare {@code https} scheme + host with no path, port, credentials,
 * query or fragment, and that host must match the {@code api_hostname} of a row in
 * {@code hosted_instances}. Anything else gets a plain page and no {@code Location} header.
 *
 * <p>The {@code code} is never logged and never rendered into a page body: a code sitting in a log
 * file on a shared host is a credential at rest.
 */
@RestController
@RequiredArgsConstructor
public class GoogleDriveRelayController {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveRelayController.class);

    /** Must stay identical to the stores' own callback path — see the class note. */
    private static final String CALLBACK_PATH = "/api/backup/google-drive/callback";

    /** Parameters worth passing on. Anything else Google invents is dropped rather than relayed. */
    private static final java.util.List<String> FORWARDED =
            java.util.List.of("code", "state", "error", "error_description", "scope");

    private final HostedInstanceRepository hostedInstances;

    @GetMapping(value = CALLBACK_PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam Map<String, String> params) {
        String state = params.get("state");
        String origin = returnOriginOf(state);
        if (origin == null) {
            // Includes a desktop install's bare-random state arriving here by mistake, which
            // cannot be routed anywhere because it names no store.
            log.warn("Google Drive relay refused a callback whose state named no store");
            return page("Google Drive was not connected",
                    "This sign-in could not be matched to a store. Close this tab and try again from Daman.");
        }

        String host = bareHttpsHost(origin);
        if (host == null) {
            log.warn("Google Drive relay refused a malformed return origin");
            return page("Google Drive was not connected",
                    "This sign-in could not be matched to a store. Close this tab and try again from Daman.");
        }

        // Any status: the allowlist exists to stop redirects to hosts that are not ours, not to
        // enforce billing. The store's own backend still answers 403 when the feature is off.
        if (hostedInstances.findByApiHostname(host).isEmpty()) {
            log.warn("Google Drive relay refused an unknown store host: {}", host);
            return page("Google Drive was not connected",
                    "This sign-in could not be matched to a store. Close this tab and try again from Daman.");
        }

        log.info("Google Drive relay forwarding a callback to {}", host);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "https://" + host + CALLBACK_PATH + query(params))
                .build();
    }

    /**
     * Mirrors {@code OAuthStateCodec.returnOriginOf} in {@code daman-backend}. The two must agree;
     * they are separate codebases, which is why the format is documented in both.
     */
    static String returnOriginOf(String state) {
        if (state == null) return null;
        int sep = state.indexOf('.');
        if (sep <= 0) return null;
        try {
            String origin = new String(Base64.getUrlDecoder().decode(state.substring(0, sep)),
                    StandardCharsets.UTF_8);
            return origin.isBlank() ? null : origin;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The host of a bare {@code https://host} origin, or null for anything else.
     *
     * <p>Strict on purpose. {@code https://good.example.com@evil.example.com} is a valid URI whose
     * host is {@code evil.example.com}; a userinfo section, a path, a port, a query or a fragment
     * all signal an attempt to smuggle a different destination past a lenient check, so every one
     * of them is a refusal rather than something to normalise away.
     */
    static String bareHttpsHost(String origin) {
        try {
            URI uri = new URI(origin);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            if (uri.getHost() == null) return null;
            if (uri.getUserInfo() != null) return null;
            if (uri.getPort() != -1) return null;
            if (uri.getQuery() != null || uri.getFragment() != null) return null;
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) return null;
            // getHost() returns null for anything it cannot parse as a hostname, so reaching here
            // means a well-formed one; lower-case it to match the stored value.
            return uri.getHost().toLowerCase(java.util.Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Re-encodes the forwarded parameters. `state` must survive byte-for-byte. */
    private static String query(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        for (String key : FORWARDED) {
            String value = params.get(key);
            if (value != null) {
                joiner.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        return joiner.length() == 0 ? "" : "?" + joiner;
    }

    /** Deliberately echoes nothing from the request — see the class note on the code. */
    private static ResponseEntity<String> page(String title, String message) {
        String html = """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>%s</title></head>
                <body style="font-family:system-ui,sans-serif;text-align:center;padding:3rem">
                <h2>%s</h2><p>%s</p></body></html>
                """.formatted(title, title, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
