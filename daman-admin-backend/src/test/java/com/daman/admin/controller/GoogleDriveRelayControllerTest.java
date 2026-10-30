package com.daman.admin.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The relay is the ONE anonymous, world-callable endpoint added by the shared-callback work, and
 * it 302s to a host named in a query parameter. That is the exact shape of an open redirect, so
 * the refusals below are the point of this class — not the happy path.
 *
 * <p>An attacker who could make this redirect to a host they control would receive a Google
 * authorization code intended for a shop's Drive. The allowlist against {@code hosted_instances}
 * is what prevents it.
 */
class GoogleDriveRelayControllerTest {

    private static final String KNOWN_HOST = "viora-api.damansoft.com";
    private static final String NONCE = "Ag7X2k9QwR4mN1pLzT6vB8sY0cH3jD5fK7gE9aU2iO4";

    private HostedInstanceRepository repository;
    private GoogleDriveRelayController controller;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        repository = mock(HostedInstanceRepository.class);
        when(repository.findByApiHostname(anyString())).thenReturn(Optional.empty());
        HostedInstance known = new HostedInstance();
        known.setClientCode("viora");
        known.setApiHostname(KNOWN_HOST);
        when(repository.findByApiHostname(KNOWN_HOST)).thenReturn(Optional.of(known));
        controller = new GoogleDriveRelayController(repository);

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(GoogleDriveRelayController.class)).addAppender(logs);
        ((Logger) LoggerFactory.getLogger(GoogleDriveRelayController.class)).setLevel(Level.TRACE);
    }

    private static String stateFor(String origin) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(origin.getBytes(StandardCharsets.UTF_8)) + "." + NONCE;
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String location(ResponseEntity<String> response) {
        return response.getHeaders().getFirst("Location");
    }

    @Test
    void redirectsToTheStoreNamedInState() {
        String state = stateFor("https://" + KNOWN_HOST);
        ResponseEntity<String> response = controller.callback(params("code", "abc123", "state", state));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(location(response))
                .startsWith("https://" + KNOWN_HOST + "/api/backup/google-drive/callback")
                .contains("code=abc123");
    }

    @Test
    void forwardsTheStateVerbatimSoTheStoreCanFindItsVerifier() {
        // The store looks the WHOLE state string up in its in-memory pendingVerifiers map. Alter
        // one character here and every hosted-store connect fails with "sign-in expired".
        String state = stateFor("https://" + KNOWN_HOST);
        assertThat(location(controller.callback(params("code", "abc", "state", state))))
                .contains("state=" + state);
    }

    @Test
    void forwardsGooglesErrorParameterToTheStore() {
        // The shop owner pressed Cancel. Forwarding puts them back on their own app's message
        // rather than an admin-api page they have never heard of.
        String state = stateFor("https://" + KNOWN_HOST);
        ResponseEntity<String> response =
                controller.callback(params("error", "access_denied", "state", state));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(location(response)).contains("error=access_denied");
    }

    @Test
    void refusesAHostThatIsNotAHostedInstance() {
        // The open-redirect case. An attacker-controlled host must never appear in Location.
        ResponseEntity<String> response = controller.callback(
                params("code", "abc", "state", stateFor("https://evil.example.com")));

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FOUND);
        assertThat(location(response)).isNull();
        assertThat(response.getBody()).doesNotContain("evil.example.com");
    }

    @Test
    void refusesAnOriginThatIsNotHttps() {
        ResponseEntity<String> response = controller.callback(
                params("code", "abc", "state", stateFor("http://" + KNOWN_HOST)));
        assertThat(location(response)).isNull();
    }

    @Test
    void refusesAStateWithNoOrigin() {
        ResponseEntity<String> response = controller.callback(params("code", "abc", "state", NONCE));
        assertThat(location(response)).isNull();
    }

    @Test
    void refusesAMalformedState() {
        assertThat(location(controller.callback(params("code", "abc", "state", "!!!not base64!!!.x")))).isNull();
        assertThat(location(controller.callback(params("code", "abc", "state", ".x")))).isNull();
    }

    @Test
    void refusesAnAbsentState() {
        assertThat(location(controller.callback(params("code", "abc")))).isNull();
        assertThat(location(controller.callback(params()))).isNull();
    }

    @Test
    void refusesAnOriginCarryingAPathOrCredentials() {
        // https://viora-api.damansoft.com@evil.example.com parses with host evil.example.com in
        // some parsers and viora-api... in others. Anything but a bare scheme+host is refused.
        assertThat(location(controller.callback(
                params("code", "abc", "state", stateFor("https://" + KNOWN_HOST + "/evil"))))).isNull();
        assertThat(location(controller.callback(
                params("code", "abc", "state", stateFor("https://" + KNOWN_HOST + "@evil.example.com"))))).isNull();
    }

    @Test
    void acceptsAStoreInAnyStatus() {
        // The allowlist exists to stop redirects to hosts that are not ours, not to enforce
        // billing. A suspended store's owner may legitimately be reconnecting; the store's own
        // backend still answers 403 if the feature is off.
        HostedInstance suspended = new HostedInstance();
        suspended.setClientCode("paused");
        suspended.setApiHostname("paused-api.damansoft.com");
        when(repository.findByApiHostname("paused-api.damansoft.com")).thenReturn(Optional.of(suspended));

        assertThat(location(controller.callback(
                params("code", "abc", "state", stateFor("https://paused-api.damansoft.com")))))
                .startsWith("https://paused-api.damansoft.com/");
    }

    @Test
    void neverLogsTheAuthorizationCode() {
        // A code in a log file on a shared host is a credential at rest.
        String secret = "4/0AeanS0buperSecretAuthCode";
        controller.callback(params("code", secret, "state", stateFor("https://" + KNOWN_HOST)));
        controller.callback(params("code", secret, "state", stateFor("https://evil.example.com")));
        controller.callback(params("code", secret, "state", "garbage"));

        assertThat(logs.list)
                .withFailMessage("the authorization code must never reach a log line")
                .noneMatch(e -> e.getFormattedMessage().contains(secret));
    }

    @Test
    void neverPutsTheCodeInAnErrorPageBody() {
        String secret = "4/0AeanS0buperSecretAuthCode";
        ResponseEntity<String> response =
                controller.callback(params("code", secret, "state", stateFor("https://evil.example.com")));
        assertThat(response.getBody()).doesNotContain(secret);
    }
}
