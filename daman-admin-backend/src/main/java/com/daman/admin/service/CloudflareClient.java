package com.daman.admin.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal Cloudflare API client: DNS records in one zone + custom domains on a Pages project.
 *
 * The API token lives only inside the default {@link Http} transport (read from CLOUDFLARE_API_TOKEN).
 * It is never put in a log line or exception message, and transport failures are deliberately NOT chained
 * as a cause: a leaked token would let anyone repoint DNS and a redeploy cannot undo that.
 */
@Service
public class CloudflareClient {

    private static final String BASE = "https://api.cloudflare.com/client/v4";

    /** The HTTP seam: returns the response body (Cloudflare returns its JSON envelope on 4xx too). */
    @FunctionalInterface
    public interface Http {
        String send(String method, String path, String jsonBodyOrNull) throws Exception;
    }

    public static class CloudflareException extends RuntimeException {
        public CloudflareException(String message) {
            super(message);
        }
    }

    private final ObjectMapper mapper;
    private final String accountId;
    private final String zoneId;
    private final Http http;

    @Autowired
    public CloudflareClient(ObjectMapper mapper,
                            @Value("${daman.cloudflare.account-id:}") String accountId,
                            @Value("${daman.cloudflare.zone-id:}") String zoneId,
                            @Value("${CLOUDFLARE_API_TOKEN:}") String apiToken) {
        this(mapper, accountId, zoneId, new JdkHttp(apiToken));
    }

    public CloudflareClient(ObjectMapper mapper, String accountId, String zoneId, Http http) {
        this.mapper = mapper;
        this.accountId = accountId;
        this.zoneId = zoneId;
        this.http = http;
    }

    /** Creates a proxied CNAME {@code subdomain -> target} and returns the new record id. */
    public String createProxiedCname(String subdomain, String target) {
        String body = json(Map.of("type", "CNAME", "name", subdomain, "content", target,
                "proxied", true, "ttl", 1));
        JsonNode result = call("POST", "/zones/" + zoneId + "/dns_records", body);
        return requireText(result, "id");
    }

    public void deleteDnsRecord(String recordId) {
        call("DELETE", "/zones/" + zoneId + "/dns_records/" + recordId, null);
    }

    public Optional<String> findDnsRecordId(String hostname) {
        JsonNode result = call("GET", "/zones/" + zoneId + "/dns_records?name="
                + URLEncoder.encode(hostname, StandardCharsets.UTF_8), null);
        if (result == null || !result.isArray() || result.isEmpty()) {
            return Optional.empty();
        }
        JsonNode id = result.get(0).get("id");
        return id == null || id.isNull() ? Optional.empty() : Optional.of(id.asString());
    }

    public void attachPagesDomain(String project, String hostname) {
        call("POST", pagesDomains(project), json(Map.of("name", hostname)));
    }

    /** One of initializing / pending / active / deactivated. */
    public String pagesDomainStatus(String project, String hostname) {
        return requireText(call("GET", pagesDomains(project) + "/" + hostname, null), "status");
    }

    public void detachPagesDomain(String project, String hostname) {
        call("DELETE", pagesDomains(project) + "/" + hostname, null);
    }

    private String pagesDomains(String project) {
        return "/accounts/" + accountId + "/pages/projects/" + project + "/domains";
    }

    private String json(Object o) {
        return mapper.writeValueAsString(o);
    }

    private static String requireText(JsonNode result, String field) {
        JsonNode n = result == null ? null : result.get(field);
        if (n == null || n.isNull()) {
            throw new CloudflareException("Cloudflare response missing '" + field + "'");
        }
        return n.asString();
    }

    /** Sends the request, validates the envelope, and returns {@code result} (may be null). */
    private JsonNode call(String method, String path, String body) {
        String raw;
        try {
            raw = http.send(method, path, body);
        } catch (Exception e) {
            // Only the exception type: its message/cause could embed request headers.
            throw new CloudflareException("Cloudflare request failed: " + method + " " + path
                    + " (" + e.getClass().getSimpleName() + ")");
        }
        JsonNode env;
        try {
            env = mapper.readTree(raw);
        } catch (Exception e) {
            // Never echo the body: it is not ours to log.
            throw new CloudflareException("Cloudflare returned an unparseable response for "
                    + method + " " + path);
        }
        if (env == null || !env.path("success").asBoolean(false)) {
            StringBuilder sb = new StringBuilder("Cloudflare error on " + method + " " + path + ":");
            JsonNode errors = env == null ? null : env.get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                for (JsonNode err : errors) {
                    sb.append(" [").append(err.path("code").asInt(0)).append("] ")
                      .append(err.path("message").asString(""));
                }
            } else {
                sb.append(" unknown error");
            }
            throw new CloudflareException(sb.toString());
        }
        return env.get("result");
    }

    /** Default transport over java.net.http; the only place the token is held. */
    private static final class JdkHttp implements Http {
        private final String token;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();

        JdkHttp(String token) {
            this.token = token;
        }

        @Override
        public String send(String method, String path, String body) throws Exception {
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("CLOUDFLARE_API_TOKEN is not configured");
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
        }

        @Override
        public String toString() {
            return "JdkHttp[token=***]";
        }
    }
}
