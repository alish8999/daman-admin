// Decides where the page is running and therefore who it talks to. Loaded two ways, like
// store-row-core.js: require()d by the node tests, and as a plain <script> in the page.
//
// The same page is served from two places. At http://localhost:5500 the Node agent serves it and
// proxies every remote call through /api/remote/*. At https://admin.damansoft.com the Angular
// portal serves it as static files, there is no proxy, and the page calls admin-api directly --
// which works because CorsConfig and AuthFilter.ALLOWED_ORIGINS already list that origin.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.apiBaseCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {

  const PORTAL_ORIGIN = 'https://admin.damansoft.com';
  const ADMIN_API = 'https://admin-api.damansoft.com';

  /**
   * 'agent' only for a loopback origin; everything else is 'portal'.
   *
   * Erring toward portal is the safe default. Guessing 'agent' for an unknown origin would have
   * the page try to drive builds through a server that is not there and fail silently; guessing
   * 'portal' greys those cards, which is both honest and recoverable.
   */
  function mode(origin) {
    const o = String(origin || '');
    if (/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(o)) return 'agent';
    return 'portal';
  }

  // The agent's proxy prefix, and what it stands for on the real backend.
  const REMOTE_PREFIX = '/api/remote/';

  /**
   * The URL to call for a path the page was written against.
   *
   * Returns the path unchanged in agent mode. In portal mode a /api/remote/* path becomes its
   * admin-api equivalent, and anything else returns null — a build, a slot status or a local
   * version listing has no hosted equivalent, so asking for one is a programming error rather
   * than a URL to invent.
   *
   * Whole URLs, not a prefix swap: the page's fetches are root-absolute, so under the portal they
   * would otherwise resolve against the Pages host.
   */
  function apiUrl(currentMode, path) {
    const p = String(path || '');
    if (currentMode === 'agent') return p;
    if (p.indexOf(REMOTE_PREFIX) !== 0) return null;
    return ADMIN_API + '/api/' + p.slice(REMOTE_PREFIX.length);
  }

  /**
   * Where a token comes from: the portal session in storage, or the page's own token strip.
   *
   * The 'local' token is never storage, in either mode. It authorises the LOCAL admin backend on
   * :8083 -- a different process with its own in-memory sessions -- so the portal session simply
   * is not valid there. See token-state-core.js, which documents that the two are not
   * interchangeable.
   */
  function tokenSource(currentMode, kind) {
    if (kind === 'portal' && currentMode === 'portal') return 'storage';
    return 'strip';
  }

  /** Where the Angular portal keeps its session. Must match AuthService.storageKey. */
  const PORTAL_TOKEN_STORAGE_KEY = 'admin-auth-token';

  /**
   * Whether the local build agent can be reached, resolving false for every way it cannot.
   *
   * A stopped agent, a declined Private Network Access prompt and a browser that blocks the
   * localhost call as mixed content are three different causes with one effect: the build cards
   * cannot be used. They are deliberately not distinguished, because the remedy is the same and a
   * page that guessed would guess wrong.
   *
   * Never rejects. The fleet is the half that works from anywhere, and a broken agent must not
   * take it down.
   */
  function agentReachable(probe, timeoutMs) {
    const limit = typeof timeoutMs === 'number' ? timeoutMs : 1500;
    return new Promise(function (resolve) {
      let settled = false;
      const finish = function (value) {
        if (!settled) { settled = true; resolve(value); }
      };
      const timer = setTimeout(function () { finish(false); }, limit);
      try {
        Promise.resolve(probe()).then(
          function (res) { clearTimeout(timer); finish(!!(res && res.ok)); },
          function () { clearTimeout(timer); finish(false); });
      } catch (err) {
        clearTimeout(timer);
        finish(false);
      }
    });
  }

  return { mode, apiUrl, tokenSource, agentReachable,
           ADMIN_API, PORTAL_ORIGIN, PORTAL_TOKEN_STORAGE_KEY };
});
