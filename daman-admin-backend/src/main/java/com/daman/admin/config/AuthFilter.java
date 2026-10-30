package com.daman.admin.config;

import com.daman.admin.service.SessionService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

/**
 * Guards every /api/** endpoint behind the admin session token, except the
 * paths listed in PUBLIC_PATHS below. That sentence used to end "except the
 * handful of endpoints the shipped desktop POS apps call directly in the
 * field" — no shipped desktop build has ever called any of them.
 */
public class AuthFilter implements Filter {

    /**
     * The ONLY unauthenticated surface on this backend. admin-api.damansoft.com
     * is directly reachable from the public internet with no Cloudflare Access
     * layer in front of it (verified 2026-10-19: GET /api/clients answers 401
     * from outside, so this filter is the whole gate), which makes every entry
     * here world-callable. Treat adding one as a security change, not a routing
     * tweak: it has to be safe to invoke anonymously, at any rate, by anyone.
     *
     * {@code /api/licenses/activate} was removed on 2026-10-19 — it minted a
     * signed, never-expiring licence for any caller-supplied machineId off a
     * guessable clientCode, and returned that client's seeded admin credentials
     * inside the base64 payload. See the note in LicenseController where it used
     * to live.
     *
     * {@code /api/licenses/public-key} stays: it serves the licence PUBLIC key,
     * which ships inside every desktop build anyway, so it discloses nothing.
     *
     * {@code /api/trial/register} (added 2026-10-20) creates rows and mints a licence
     * anonymously, and is safe only because the caller supplies no client code, the
     * licence is bound to the caller's own machine id with a server-set expiry, and the
     * response is the licence key alone. See TrialRegistrationService.
     *
     * {@code /api/license/renewal} (added 2026-10-21) mints nothing and writes nothing. The
     * caller must PRESENT a validly signed licence, and machineId/clientCode are read from
     * that signed payload rather than from the body, so the only thing obtainable is the
     * current key for the machine the presented licence already names — no new disclosure to
     * anyone able to call it at all. Note the path is SINGULAR: /api/licenses/** (plural) is
     * the admin-token-gated licence API and must never be opened. See
     * LicenseRenewalController.
     *
     * {@code /api/public/feature-catalog} (added 2026-10-30) serves the add-on price list. It
     * reads no request input, mints nothing, writes nothing, and touches no row: the response is a
     * single constant built at class-init, so a flood costs one serialisation per call. What it
     * discloses — what each add-on costs — is already shown to any shop owner who opens the POS, so
     * publishing it to the internet reveals nothing that was not already customer-facing. Note the
     * path is under {@code /api/public/}: the admin portal reads the same body from the SESSION-
     * GATED {@code /api/feature-catalog}, which must never be opened here.
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/licenses/public-key",
            "/api/trial/register",
            "/api/license/renewal",
            "/api/public/feature-catalog"
    );

    // Mirrors CorsConfig's allowedOrigins. This filter runs before Spring MVC's
    // CORS handling (order 1, ahead of the DispatcherServlet), so a 401 it
    // writes directly never picks up Spring's CORS headers — without this,
    // the browser blocks the response as a CORS violation and the frontend
    // sees a generic network error instead of a 401, so it never redirects
    // to login on an expired/invalid session.
    private static final Set<String> ALLOWED_ORIGINS = Set.of(
            "http://localhost:4201",
            "http://localhost:4200",
            "http://localhost:3000",
            "http://localhost:5173",
            "https://admin.damansoft.com"
    );

    private final SessionService sessionService;

    public AuthFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if ("OPTIONS".equalsIgnoreCase(req.getMethod()) || PUBLIC_PATHS.contains(req.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = req.getHeader("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;

        if (!sessionService.isValid(token)) {
            String origin = req.getHeader("Origin");
            if (origin != null && ALLOWED_ORIGINS.contains(origin)) {
                res.setHeader("Access-Control-Allow-Origin", origin);
                res.setHeader("Vary", "Origin");
            }
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
