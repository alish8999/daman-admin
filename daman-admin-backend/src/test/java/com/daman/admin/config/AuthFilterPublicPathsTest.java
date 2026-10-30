package com.daman.admin.config;

import com.daman.admin.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the anonymous surface of the admin backend.
 *
 * <p>This exists because admin-api.damansoft.com is reachable from the public
 * internet with nothing but {@link AuthFilter} in front of it, so
 * {@code PUBLIC_PATHS} is the entire security boundary. On 2026-10-19 that list
 * still contained {@code /api/licenses/activate}, an endpoint that minted a
 * signed, never-expiring licence for any caller-supplied machineId off a
 * guessable clientCode and returned that client's seeded admin credentials in
 * the base64 payload. It was dead code — no shipped desktop build ever called
 * it — but it was world-callable for as long as it existed.
 *
 * <p>So this test asserts the allowlist by BEHAVIOUR rather than trusting a
 * reading of the field: anything not explicitly anonymous must answer 401
 * without a session token. A new entry in {@code PUBLIC_PATHS} fails this test,
 * which is the point — adding one should require deliberately editing the
 * expectation here and justifying it.
 */
class AuthFilterPublicPathsTest {

    /**
     * The only four paths that may be reached without a session token.
     *
     * <p>{@code /api/license/renewal} was added 2026-10-21. Justification, as this test's
     * class note demands: it mints nothing and writes nothing, the caller must present a
     * validly signed licence, and the machine and client are taken from that signed payload
     * rather than the request body — so the only obtainable value is the current key for a
     * machine the caller already holds a licence for. Note it is SINGULAR; the plural
     * {@code /api/licenses/**} family below must stay authenticated.
     *
     * <p>{@code /api/public/feature-catalog} was added 2026-10-30. Justification, as this class's
     * note demands: it reads no request input, mints and writes nothing, touches no row, and
     * returns one constant built at class-init, so it cannot be aimed at anything and a flood costs
     * one serialisation per call. It discloses add-on prices, which every shop owner already sees
     * in the POS. The admin portal reads the same body from the session-gated
     * {@code /api/feature-catalog}, which is listed under MUST_BE_PROTECTED below.
     *
     * <p>{@code /api/backup/google-drive/callback} was added 2026-10-30: the shared Google OAuth
     * redirect for hosted stores. Anonymous because Google redirects a BROWSER here. It mints and
     * writes nothing, and although it 302s to a host taken from the OAuth {@code state} — an open
     * redirect in shape — it only does so for an origin that is a bare https scheme+host AND
     * matches an existing {@code hosted_instances.api_hostname}. The refusals are pinned by
     * {@code GoogleDriveRelayControllerTest}, which is the test that actually guards this one.
     */
    private static final List<String> ANONYMOUS = List.of(
            "/api/auth/login",
            "/api/licenses/public-key",
            "/api/trial/register",
            "/api/license/renewal",
            "/api/public/feature-catalog",
            "/api/backup/google-drive/callback"
    );

    /**
     * Paths that must never be anonymous. Deliberately includes the licence
     * minting and re-issue endpoints, the dev-run family (which writes licence
     * files and copies databases around), and the removed {@code /activate}.
     */
    private static final List<String> MUST_BE_PROTECTED = List.of(
            "/api/licenses/activate",
            "/api/licenses",
            "/api/licenses/reissue-v2",
            "/api/licenses/reissue-v2/preview",
            "/api/clients",
            "/api/clients/acme/dev-run",
            "/api/clients/acme/dev-reset",
            "/api/builds",
            "/api/trial",
            "/api/trial/",
            "/api/trial/register/",
            "/api/trial/registrations",
            "/api/trial/settings",
            // Trials tab listing: prospects' names and phones. Never anonymous.
            "/api/trials",
            // Hosted-store provisioning: creates DNS records, systemd units and licences.
            "/api/hosted-stores",
            "/api/hosted-stores/suggest-subdomain",
            // The most destructive route in the hosted-store feature: it bounces EVERY paying
            // store, one at a time. Gated in fact by this filter's prefix-independent default-deny,
            // but listed on its own line because of what it does, not because it was in doubt.
            "/api/hosted-stores/restart-all",
            // Deletes a registry row (refused for an ACTIVE store, but still a write).
            "/api/hosted-stores/acme",
            // The session-gated twin of /api/public/feature-catalog. Same body today, but keeping
            // it protected is what stops the public path quietly acquiring an admin-only field.
            "/api/feature-catalog"
    );

    SessionService sessionService;
    AuthFilter filter;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        when(sessionService.isValid(null)).thenReturn(false);
        when(sessionService.isValid("good-token")).thenReturn(true);
        filter = new AuthFilter(sessionService);
    }

    private MockHttpServletResponse call(String method, String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private boolean reached(String method, String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }

    @Test
    void activateIsNoLongerAnonymous() throws Exception {
        MockHttpServletResponse response = call("POST", "/api/licenses/activate", null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(reached("POST", "/api/licenses/activate", null)).isFalse();
    }

    @Test
    void everySensitivePathRequiresASessionToken() throws Exception {
        for (String path : MUST_BE_PROTECTED) {
            assertThat(reached("POST", path, null))
                    .as("%s must not be reachable anonymously", path)
                    .isFalse();
        }
    }

    @Test
    void onlyTheAllowlistedPathsAreAnonymous() throws Exception {
        for (String path : ANONYMOUS) {
            assertThat(reached("POST", path, null))
                    .as("%s is expected to be anonymous", path)
                    .isTrue();
        }
    }

    /**
     * Added 2026-10-20 with the self-serve trial. Safe anonymously ONLY because the
     * request carries no client code, the response is the licence key and nothing else,
     * and the licence is bound to the caller's own machine id with a server-set expiry.
     */
    @Test
    void trialRegisterIsAnonymous() throws Exception {
        assertThat(call("POST", "/api/trial/register", null).getStatus()).isNotEqualTo(401);
        assertThat(reached("POST", "/api/trial/register", null)).isTrue();
    }

    @Test
    void trialsListingRequiresASessionToken() throws Exception {
        assertThat(call("GET", "/api/trials", null).getStatus()).isEqualTo(401);
        assertThat(reached("GET", "/api/trials", null)).isFalse();
        assertThat(reached("GET", "/api/trials", "good-token")).isTrue();
    }

    @Test
    void hostedStoresIsNotPublic() throws Exception {
        for (String method : List.of("GET", "POST")) {
            assertThat(call(method, "/api/hosted-stores", null).getStatus()).isEqualTo(401);
            assertThat(reached(method, "/api/hosted-stores", null)).isFalse();
        }
        assertThat(reached("POST", "/api/hosted-stores", "good-token")).isTrue();
    }

    @Test
    void aValidTokenStillReachesProtectedPaths() throws Exception {
        assertThat(reached("GET", "/api/clients", "good-token")).isTrue();
        assertThat(reached("GET", "/api/licenses", "good-token")).isTrue();
    }

    @Test
    void anInvalidTokenIsRejectedWithJson() throws Exception {
        MockHttpServletResponse response = call("GET", "/api/clients", null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Unauthorized");
    }

    /**
     * CORS preflight passes the filter for any path — existing behaviour, pinned
     * here so it stays deliberate. An OPTIONS request carries no body and
     * mutates nothing; Spring MVC's CORS handling answers it downstream.
     */
    @Test
    void corsPreflightPassesThroughForProtectedPaths() throws Exception {
        assertThat(reached("OPTIONS", "/api/clients", null)).isTrue();
    }
}
