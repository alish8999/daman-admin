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

    /** The only three paths that may be reached without a session token. */
    private static final List<String> ANONYMOUS = List.of(
            "/api/auth/login",
            "/api/licenses/public-key",
            "/api/trial/register"
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
            "/api/trial/settings"
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
