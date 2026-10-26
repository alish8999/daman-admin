const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const url = require('url');
const { spawnSync } = require('child_process');
const { SLOTS, HOSTED_STORE } = require('./lib/slots');
const { RunLock } = require('./lib/run-lock');
const { findLatestGenericVersion } = require('./lib/version-scanner');
const { runScript } = require('./lib/script-runner');

const PORT = 5500;
const HOST = '127.0.0.1';
const PUBLIC_DIR = path.join(__dirname, 'public');
const LIB_DIR = path.join(__dirname, 'lib');
const GENERIC_BUILD_DIR = path.join(__dirname, '..', '..', 'clients-build', 'generic');

// What "/api/health" reports on. Read-only, best-effort - purely informational,
// so a failure here never blocks or affects any deploy action.
const HEALTH_TARGETS = {
  backend: 'https://admin-api.damansoft.com/api/licenses/public-key',
  frontend: 'https://admin.damansoft.com/',
  // One shared Pages project serves every tenant, so this reports the health of the shared
  // deployment - not of any single store's hostname. There is deliberately no per-store
  // backend check here: that is a property of each hosted store, not of the dashboard.
  'store-frontend': HOSTED_STORE.sharedSiteUrl,
};
const HEALTH_TIMEOUT_MS = 6000;

// Same shape as lib/version-scanner.js's VERSION_PATTERN, for the bare version
// string the generic slot takes as a query parameter.
const VERSION_QUERY_PATTERN = /^\d+\.\d+\.\d+$/;

// "Build Generic" is a real feature of the LOCAL admin-backend (BuildService /
// ClientController), not one of this dashboard's own scripts. These requests
// are proxied server-side (browser -> :5500 -> :8083) rather than called
// directly from the page, so the browser only ever talks same-origin and this
// never depends on daman-admin-backend's own CORS config.
const LOCAL_ADMIN_BACKEND = { host: 'localhost', port: 8083 };
const BUILD_PROXY_TIMEOUT_MS = 8000;

// The REAL client records and licences live in the server's H2 on the Hetzner box, not in the
// local H2 behind :8083, so provisioning has to run on the remote portal. Exported (and
// deliberately plain mutable objects) so tests can aim them at a local fake - production code
// never reassigns them. There is NO fallback between the two targets: an unreachable remote is
// reported as such, never silently answered by the local backend's different data.
const LOCAL_TARGET = {
  secure: false,
  ...LOCAL_ADMIN_BACKEND,
  timeoutMs: BUILD_PROXY_TIMEOUT_MS,
  unreachableMessage: 'Local admin-backend is not reachable on localhost:8083 - start it first.',
};
const REMOTE_ADMIN_BACKEND = {
  secure: true,
  host: 'admin-api.damansoft.com',
  port: 443,
  timeoutMs: BUILD_PROXY_TIMEOUT_MS,
  unreachableMessage:
    'Remote admin API (admin-api.damansoft.com) is not reachable or timed out. Nothing fell back to the local backend. If you were creating a store, check the registry before retrying.',
};
// POST /api/hosted-stores now answers 202 as soon as the registry row exists and runs its twelve
// stages afterwards, server-side, so this request is a short write and not a 2-minute wait.
//
// It used to be 180000, and that was the wrong shape twice over: the server's own worst case
// (200 s of Pages polling + 60 s of health waiting + command timeouts) already exceeded it, so the
// dashboard could time out a provision that was going fine; and the request crosses Cloudflare's
// edge, which answers 524 after 100 s of origin silence regardless of what we set here. The long
// wait lives in the page's registry poll instead (public/main.js, pollUntilTerminal).
const PROVISION_TIMEOUT_MS = 30000;
// Lifecycle actions on a hosted store (suspend/resume/restart/remove). resume and restart wait on
// the store's health server-side for up to 75 s, and remove also reloads cloudflared (which briefly
// interrupts every hostname on the tunnel), so the 8 s default would give up on - and report as
// failed - an operation that is actually succeeding, on routes that restart live customers' stores.
// 90 s clears the backend's 75 s wait yet stays under Cloudflare's 100 s edge limit, after which the
// edge answers 524 regardless of what is set here.
const LIFECYCLE_PROXY_TIMEOUT_MS = 90000;
// The code is interpolated into a URL path, so it is checked against this allowlist BEFORE
// encodeURIComponent; anything else is a 400 that never reaches the remote.
const STORE_CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;
const MAX_JSON_BODY_BYTES = 16 * 1024;
const BUILD_PLATFORMS = new Set(['win', 'winx86']);

const runLock = new RunLock(Object.keys(SLOTS));

// Admin Bearer tokens for the slots whose script needs one (SLOTS[...].requiresAdminToken),
// posted by the page just before it opens that slot's stream. In memory only: never written to
// disk, never logged, and gone when the dashboard process stops. It is kept after a run rather
// than consumed, so a retry after a failed build does not need a re-paste - a token that has
// since gone stale is caught by the script's own preflight, which reports a clean 401 before
// anything is built or uploaded.
const adminTokens = new Map();

// What a posted token is allowed to contain. Admin tokens are UUIDs today (SessionService) and
// a JWT would also pass. The point is not to validate the token - the server cannot - but to
// keep shell metacharacters out of it: deploy-store-backend.ps1 interpolates it into a
// single-quoted sh string on the remote, so a straight quote in it would break out of that
// string and mangle the command.
const ADMIN_TOKEN_PATTERN = /^[A-Za-z0-9._~+/=-]{8,4096}$/;

// PIDs of every child process currently running for a slot, so a Ctrl+C on the
// dashboard server doesn't orphan a half-finished deploy (Windows does not
// clean up a spawned process tree on parent exit).
const runningPids = new Set();

const STATIC_FILES = {
  '/': { file: 'index.html', type: 'text/html; charset=utf-8' },
  '/main.js': { file: 'main.js', type: 'application/javascript; charset=utf-8' },
  '/style.css': { file: 'style.css', type: 'text/css; charset=utf-8' },
  // Loaded by index.html BEFORE main.js. registry-rows.js was referenced there but never listed
  // here, so it 404ed and registryRows was undefined at runtime.
  '/registry-rows.js': { file: 'registry-rows.js', type: 'application/javascript; charset=utf-8' },
  // Lives in lib/ so node's tests can require it; served from there rather than copied.
  '/token-state-core.js': { dir: LIB_DIR, file: 'token-state-core.js', type: 'application/javascript; charset=utf-8' },
};

// CSRF guard: every endpoint here is side-effecting or local-only, and the
// deploy-stream endpoint triggers a real production deploy off a plain GET, so
// a page the developer happens to be visiting must not be able to fire it with
// an <img>/<script>/fetch. Requests with a cross-origin `Origin` are rejected;
// so are ones whose `Sec-Fetch-Site` says they came from anywhere but this
// page itself (that header covers no-cors requests, which omit `Origin`).
function isSameOrigin(req) {
  const origin = req.headers.origin;
  if (origin) {
    // Port comes from the connection itself, not the PORT constant, so the
    // check stays correct when the server is bound somewhere else (tests).
    const port = (req.socket && req.socket.localPort) || PORT;
    const allowed = [`http://127.0.0.1:${port}`, `http://localhost:${port}`];
    if (!allowed.includes(origin)) return false;
  }
  const fetchSite = req.headers['sec-fetch-site'];
  if (fetchSite && fetchSite !== 'same-origin' && fetchSite !== 'none') return false;
  return true;
}

function serveStatic(res, entry) {
  const filePath = path.join(entry.dir || PUBLIC_DIR, entry.file);
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Failed to read ' + entry.file);
      return;
    }
    res.writeHead(200, { 'Content-Type': entry.type });
    res.end(data);
  });
}

function sendJson(res, statusCode, body) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

// Resolves true/false, never rejects - a network hiccup or a 5xx just reads
// as "down" on the dashboard, it never surfaces as a server error.
function checkUrlUp(targetUrl) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (up) => {
      if (settled) return;
      settled = true;
      resolve(up);
    };
    const req = https.get(targetUrl, { timeout: HEALTH_TIMEOUT_MS }, (res) => {
      res.resume(); // drain the body, we only care about the status code
      finish(res.statusCode >= 200 && res.statusCode < 400);
    });
    req.on('timeout', () => { req.destroy(); finish(false); });
    req.on('error', () => finish(false));
  });
}

async function handleHealth(req, res) {
  const entries = Object.entries(HEALTH_TARGETS);
  const results = await Promise.all(entries.map(([, target]) => checkUrlUp(target)));
  const body = {};
  entries.forEach(([name], i) => { body[name] = results[i]; });
  sendJson(res, 200, body);
}

// Forwards one request to the local admin-backend and relays its JSON
// response verbatim. A connection failure (backend not running) or a slow
// response both resolve to a plain-language 503 instead of a raw ECONNREFUSED
// stack, since "start the local admin-backend first" is the actual fix either way.
//
// The admin-backend guards every /api/** route behind a Bearer session token
// (AuthFilter.java) that only its own login flow can mint - this dashboard
// never holds or requests a password, it just relays whatever token the
// browser already sent (pasted once from a real admin-portal login) straight
// through, unmodified, exactly like it arrived.
//
// One helper, parameterised by target (local :8083 or the remote portal). The local routes
// keep calling proxyToLocalAdminBackend with its original signature and behaviour.
function proxyToAdminBackend(target, req, method, targetPath, res, body, timeoutMs) {
  let settled = false;
  const respondUnreachable = () => {
    if (settled) return;
    settled = true;
    sendJson(res, 503, { error: target.unreachableMessage });
  };

  const headers = {};
  if (req.headers.authorization) headers.authorization = req.headers.authorization;
  const payload = body != null ? (typeof body === 'string' ? body : JSON.stringify(body)) : null;
  if (payload != null) {
    headers['content-type'] = 'application/json';
    headers['content-length'] = Buffer.byteLength(payload);
  }

  const transport = target.secure ? https : http;
  const upstreamReq = transport.request(
    {
      host: target.host,
      port: target.port,
      method,
      path: targetPath,
      timeout: timeoutMs || target.timeoutMs,
      headers,
    },
    (upstreamRes) => {
      let respBody = '';
      upstreamRes.on('data', (chunk) => { respBody += chunk; });
      upstreamRes.on('end', () => {
        if (settled) return;
        settled = true;
        res.writeHead(upstreamRes.statusCode || 502, { 'Content-Type': 'application/json' });
        res.end(respBody || '{}');
      });
    }
  );
  upstreamReq.on('timeout', () => { upstreamReq.destroy(); respondUnreachable(); });
  upstreamReq.on('error', respondUnreachable);
  if (payload != null) upstreamReq.write(payload);
  upstreamReq.end();
}

function proxyToLocalAdminBackend(req, method, targetPath, res, body) {
  proxyToAdminBackend(LOCAL_TARGET, req, method, targetPath, res, body);
}

function proxyToRemoteAdminBackend(req, method, targetPath, res, body, timeoutMs) {
  proxyToAdminBackend(REMOTE_ADMIN_BACKEND, req, method, targetPath, res, body, timeoutMs);
}

// Collects a JSON request body from the browser before relaying it onward -
// only the two proxied write endpoints below (versions, changelog-library)
// ever send one; every other route here takes its input from the query
// string instead.
function readRequestBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    let data = '';
    let tooLarge = false;
    req.on('data', (chunk) => {
      if (tooLarge) return; // keep draining so the 413 can still be written on the open socket
      data += chunk;
      if (maxBytes && data.length > maxBytes) {
        tooLarge = true;
        data = '';
        reject(new Error('Request body too large'));
      }
    });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

// Create online client. A provision is a POST (never a GET) behind the same-origin guard in the
// request handler. The ONLY request-derived values are the three the owner chose on the page;
// everything else (port, hostnames, paths, unit template) is decided by the remote endpoint.
// The body is rebuilt from those three fields so nothing else can ride along.
async function handleRemoteProvision(req, res) {
  let raw;
  try {
    raw = await readRequestBody(req, MAX_JSON_BODY_BYTES);
  } catch (err) {
    sendJson(res, 413, { error: 'Request body too large' });
    return;
  }
  let parsedBody;
  try {
    parsedBody = JSON.parse(raw);
  } catch (err) {
    sendJson(res, 400, { error: 'Body must be JSON {clientCode, subdomain, expiresAt}' });
    return;
  }
  const { clientCode, subdomain } = parsedBody || {};
  // expiresAt is optional (blank = no expiry on the licence); the remote validates its format.
  const expiresAt = (parsedBody && parsedBody.expiresAt) == null ? '' : parsedBody.expiresAt;
  if (typeof clientCode !== 'string' || !clientCode.trim()
      || typeof subdomain !== 'string' || typeof expiresAt !== 'string') {
    sendJson(res, 400, { error: 'clientCode and subdomain are required strings; expiresAt must be a string' });
    return;
  }
  proxyToRemoteAdminBackend(
    req, 'POST', '/api/hosted-stores', res,
    { clientCode, subdomain, expiresAt }, PROVISION_TIMEOUT_MS
  );
}

function handleBuildGenericStart(req, res, query) {
  const version = query.version || '';
  if (version && !VERSION_QUERY_PATTERN.test(version)) {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('version must look like 1.2.3');
    return;
  }
  const platform = BUILD_PLATFORMS.has(query.platform) ? query.platform : 'win';
  const targetPath = `/api/clients/generic-build?platform=${encodeURIComponent(platform)}&version=${encodeURIComponent(version)}`;
  proxyToLocalAdminBackend(req, 'POST', targetPath, res);
}

function handleBuildGenericStatus(req, res) {
  proxyToLocalAdminBackend(req, 'GET', '/api/clients/generic-build/status', res);
}

// Best-effort termination of anything still running when the dashboard itself
// is interrupted. Each kill is independent and guarded: a child may already
// have exited between the SIGINT and the taskkill.
function killTrackedChildren() {
  for (const pid of runningPids) {
    try {
      if (process.platform === 'win32') {
        spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' });
      } else {
        process.kill(pid, 'SIGTERM');
      }
    } catch (err) {
      // Already gone, or not ours any more - nothing useful to do.
    }
  }
  runningPids.clear();
}

function handleDeployStream(req, res, slot, query) {
  if (!SLOTS[slot]) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(`Unknown slot: ${slot}`);
    return;
  }
  if (SLOTS[slot].requiresVersion) {
    if (!query.version) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('version query parameter is required for this slot');
      return;
    }
    if (!VERSION_QUERY_PATTERN.test(query.version)) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('version must look like 1.2.3');
      return;
    }
  }
  // Resolved BEFORE the lock is taken, so a missing token leaves the slot free rather than
  // holding it for a run that was never started.
  let childEnv = null;
  if (SLOTS[slot].requiresAdminToken) {
    // The environment is still honoured as a fallback for running the script by hand outside
    // the dashboard; the page's posted token wins when there is one, because it is the fresher.
    const token = adminTokens.get(slot) || process.env.DAMAN_ADMIN_TOKEN || '';
    if (!token) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(
        'No admin token for this deploy. Paste a Bearer token from a login at admin.damansoft.com '
        + 'into the "Create online client" card - this deploy reuses the same one.'
      );
      return;
    }
    childEnv = { DAMAN_ADMIN_TOKEN: token };
  }
  if (!runLock.tryAcquire(slot)) {
    res.writeHead(409, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(`${slot} deploy is already running`);
    return;
  }

  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
  });

  let closed = false;
  res.on('close', () => { closed = true; });
  res.on('error', () => { closed = true; });

  const send = (event, data) => {
    if (closed) return;
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  };

  let finished = false;

  const args = [
    ...(SLOTS[slot].args || []),
    ...(SLOTS[slot].requiresVersion ? ['-Version', query.version] : []),
  ];
  const child = runScript(SLOTS[slot].scriptPath, args, childEnv);
  const pid = child.pid;
  if (pid) runningPids.add(pid);
  const untrack = () => { if (pid) runningPids.delete(pid); };

  child.on('line', (line) => send('log', { line }));
  child.on('exit', (code) => {
    untrack();
    if (finished) return;
    finished = true;
    send('done', { code });
    if (!closed) res.end();
    runLock.release(slot);
  });
  child.on('error', (err) => {
    untrack();
    if (finished) return;
    finished = true;
    send('log', { line: `Failed to start: ${err.message}` });
    send('done', { code: null });
    if (!closed) res.end();
    runLock.release(slot);
  });
}

// Receives the Bearer token for one token-requiring slot and holds it for that slot's next run.
// A POST, not a query parameter on the stream GET, so the token never lands in a URL - URLs are
// the one part of a request that gets logged, kept in history and pasted into bug reports.
async function handleDeployToken(req, res, slot) {
  if (!SLOTS[slot] || !SLOTS[slot].requiresAdminToken) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(`${slot} does not take an admin token`);
    return;
  }
  let raw;
  try {
    raw = await readRequestBody(req, MAX_JSON_BODY_BYTES);
  } catch (err) {
    sendJson(res, 413, { error: 'Request body too large' });
    return;
  }
  let token;
  try {
    token = (JSON.parse(raw) || {}).token;
  } catch (err) {
    sendJson(res, 400, { error: 'Body must be JSON {token}' });
    return;
  }
  if (typeof token !== 'string' || !ADMIN_TOKEN_PATTERN.test(token)) {
    // The token itself is never echoed back, here or anywhere else.
    sendJson(res, 400, { error: 'token must be a plain Bearer token (no spaces or quotes)' });
    return;
  }
  adminTokens.set(slot, token);
  sendJson(res, 200, { stored: true });
}

const server = http.createServer(async (req, res) => {
  const parsed = url.parse(req.url, true);
  const pathname = parsed.pathname;

  if (req.method === 'GET' && STATIC_FILES[pathname]) {
    serveStatic(res, STATIC_FILES[pathname]);
    return;
  }

  // `pathname` is null for an asterisk-form request line (e.g. `OPTIONS *`).
  if (pathname && pathname.startsWith('/api/') && !isSameOrigin(req)) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Cross-origin requests are not allowed');
    return;
  }

  if (req.method === 'GET' && pathname === '/api/status') {
    sendJson(res, 200, runLock.statusAll());
    return;
  }

  if (req.method === 'GET' && pathname === '/api/health') {
    handleHealth(req, res);
    return;
  }

  if (req.method === 'GET' && pathname === '/api/generic/latest-version') {
    const version = findLatestGenericVersion(GENERIC_BUILD_DIR);
    sendJson(res, 200, { version });
    return;
  }

  const streamMatch = /^\/api\/deploy\/([a-z]+(?:-[a-z]+)*)\/stream$/.exec(pathname);
  if (req.method === 'GET' && streamMatch) {
    handleDeployStream(req, res, streamMatch[1], parsed.query);
    return;
  }

  const tokenMatch = /^\/api\/deploy\/([a-z]+(?:-[a-z]+)*)\/token$/.exec(pathname);
  if (req.method === 'POST' && tokenMatch) {
    await handleDeployToken(req, res, tokenMatch[1]);
    return;
  }

  if (req.method === 'POST' && pathname === '/api/build/generic') {
    handleBuildGenericStart(req, res, parsed.query);
    return;
  }

  if (req.method === 'GET' && pathname === '/api/build/generic/status') {
    handleBuildGenericStatus(req, res);
    return;
  }

  // Version + changelog picker (Build Generic Installer card) - same
  // proxy-to-:8083 pattern as build/generic above, extended to forward a
  // JSON body for the two writes.
  if (req.method === 'GET' && pathname === '/api/versions') {
    proxyToLocalAdminBackend(req, 'GET', '/api/versions', res);
    return;
  }
  if (req.method === 'POST' && pathname === '/api/versions') {
    const body = await readRequestBody(req);
    proxyToLocalAdminBackend(req, 'POST', '/api/versions', res, body);
    return;
  }
  const versionUpdateMatch = /^\/api\/versions\/(\d+)$/.exec(pathname);
  if (req.method === 'PUT' && versionUpdateMatch) {
    const body = await readRequestBody(req);
    proxyToLocalAdminBackend(req, 'PUT', `/api/versions/${versionUpdateMatch[1]}`, res, body);
    return;
  }
  if (req.method === 'GET' && pathname === '/api/changelog-library') {
    proxyToLocalAdminBackend(req, 'GET', '/api/changelog-library', res);
    return;
  }
  if (req.method === 'PUT' && pathname === '/api/changelog-library') {
    const body = await readRequestBody(req);
    proxyToLocalAdminBackend(req, 'PUT', '/api/changelog-library', res, body);
    return;
  }

  // Remote portal (hosted-store provisioning). The same-origin guard above covers these.
  if (req.method === 'GET' && pathname === '/api/remote/clients') {
    proxyToRemoteAdminBackend(req, 'GET', '/api/clients', res);
    return;
  }
  if (req.method === 'GET' && pathname === '/api/remote/hosted-stores') {
    proxyToRemoteAdminBackend(req, 'GET', '/api/hosted-stores', res);
    return;
  }
  if (req.method === 'GET' && pathname === '/api/remote/hosted-stores/suggest-subdomain') {
    const clientCode = typeof parsed.query.clientCode === 'string' ? parsed.query.clientCode : '';
    proxyToRemoteAdminBackend(
      req, 'GET',
      `/api/hosted-stores/suggest-subdomain?clientCode=${encodeURIComponent(clientCode)}`, res
    );
    return;
  }
  if (req.method === 'POST' && pathname === '/api/remote/hosted-stores') {
    await handleRemoteProvision(req, res);
    return;
  }

  const lifecycleMatch = /^[/]api[/]remote[/]hosted-stores[/]([^/]*)[/](suspend|resume|restart|remove)$/.exec(pathname);
  if (req.method === 'POST' && lifecycleMatch) {
    const code = lifecycleMatch[1];
    if (!STORE_CODE_PATTERN.test(code)) {
      sendJson(res, 400, { error: 'Invalid client code.' });
      return;
    }
    proxyToRemoteAdminBackend(
      req, 'POST',
      `/api/hosted-stores/${encodeURIComponent(code)}/${lifecycleMatch[2]}`, res,
      undefined, LIFECYCLE_PROXY_TIMEOUT_MS
    );
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not found');
});

if (require.main === module) {
  // Only when actually run as the dashboard - a test harness that requires
  // this module must keep its own Ctrl+C behaviour (and never process.exit()).
  process.on('SIGINT', () => {
    killTrackedChildren();
    process.exit(0);
  });

  server.listen(PORT, HOST, () => {
    console.log(`Deploy dashboard running at http://${HOST}:${PORT}`);
  });
}

module.exports = { server, runLock, runningPids, killTrackedChildren, isSameOrigin,
  REMOTE_ADMIN_BACKEND, LOCAL_TARGET, PROVISION_TIMEOUT_MS, LIFECYCLE_PROXY_TIMEOUT_MS, adminTokens };
