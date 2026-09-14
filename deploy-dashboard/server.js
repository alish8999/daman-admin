const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const { spawnSync } = require('child_process');
const { SLOTS } = require('./lib/slots');
const { RunLock } = require('./lib/run-lock');
const { findLatestGenericVersion } = require('./lib/version-scanner');
const { runScript } = require('./lib/script-runner');

const PORT = 5500;
const HOST = '127.0.0.1';
const PUBLIC_DIR = path.join(__dirname, 'public');
const GENERIC_BUILD_DIR = path.join(__dirname, '..', '..', 'clients-build', 'generic');

// Same shape as lib/version-scanner.js's VERSION_PATTERN, for the bare version
// string the generic slot takes as a query parameter.
const VERSION_QUERY_PATTERN = /^\d+\.\d+\.\d+$/;

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

  const args = SLOTS[slot].requiresVersion ? ['-Version', query.version] : [];
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

  if (req.method === 'GET' && pathname === '/api/generic/latest-version') {
    const version = findLatestGenericVersion(GENERIC_BUILD_DIR);
    sendJson(res, 200, { version });
    return;
  }

  const streamMatch = /^\/api\/deploy\/([a-z]+)\/stream$/.exec(pathname);
  if (req.method === 'GET' && streamMatch) {
    handleDeployStream(req, res, streamMatch[1], parsed.query);
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
