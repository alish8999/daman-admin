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
const GENERIC_BUILD_DIR = path.join(__dirname, '..', '..', 'clients-build', 'generic');

// What "/api/health" reports on. Read-only, best-effort - purely informational,
// so a failure here never blocks or affects any deploy action.
const HEALTH_TARGETS = {
  backend: 'https://admin-api.damansoft.com/api/licenses/public-key',
  frontend: 'https://admin.damansoft.com/',
  'store-backend': `https://${HOSTED_STORE.apiHost}/api/health`,
  'store-frontend': HOSTED_STORE.siteUrl,
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
const BUILD_PLATFORMS = new Set(['win', 'winx86']);

const runLock = new RunLock(Object.keys(SLOTS));

// PIDs of every child process currently running for a slot, so a Ctrl+C on the
// dashboard server doesn't orphan a half-finished deploy (Windows does not
// clean up a spawned process tree on parent exit).
const runningPids = new Set();

const STATIC_FILES = {
  '/': { file: 'index.html', type: 'text/html; charset=utf-8' },
  '/main.js': { file: 'main.js', type: 'application/javascript; charset=utf-8' },
  '/style.css': { file: 'style.css', type: 'text/css; charset=utf-8' },
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
  const filePath = path.join(PUBLIC_DIR, entry.file);
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
function proxyToLocalAdminBackend(req, method, targetPath, res) {
  let settled = false;
  const respondUnreachable = () => {
    if (settled) return;
    settled = true;
    sendJson(res, 503, {
      error: 'Local admin-backend is not reachable on localhost:8083 - start it first.',
    });
  };

  const headers = {};
  if (req.headers.authorization) headers.authorization = req.headers.authorization;

  const upstreamReq = http.request(
    { ...LOCAL_ADMIN_BACKEND, method, path: targetPath, timeout: BUILD_PROXY_TIMEOUT_MS, headers },
    (upstreamRes) => {
      let body = '';
      upstreamRes.on('data', (chunk) => { body += chunk; });
      upstreamRes.on('end', () => {
        if (settled) return;
        settled = true;
        res.writeHead(upstreamRes.statusCode || 502, { 'Content-Type': 'application/json' });
        res.end(body || '{}');
      });
    }
  );
  upstreamReq.on('timeout', () => { upstreamReq.destroy(); respondUnreachable(); });
  upstreamReq.on('error', respondUnreachable);
  upstreamReq.end();
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
  const child = runScript(SLOTS[slot].scriptPath, args);
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

const server = http.createServer((req, res) => {
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

  if (req.method === 'POST' && pathname === '/api/build/generic') {
    handleBuildGenericStart(req, res, parsed.query);
    return;
  }

  if (req.method === 'GET' && pathname === '/api/build/generic/status') {
    handleBuildGenericStatus(req, res);
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

module.exports = { server, runLock, runningPids, killTrackedChildren, isSameOrigin };
