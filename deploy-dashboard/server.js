const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const { SLOTS } = require('./lib/slots');
const { RunLock } = require('./lib/run-lock');
const { findLatestGenericVersion } = require('./lib/version-scanner');
const { runScript } = require('./lib/script-runner');

const PORT = 5500;
const HOST = '127.0.0.1';
const PUBLIC_DIR = path.join(__dirname, 'public');
const GENERIC_BUILD_DIR = path.join(__dirname, '..', '..', 'clients-build', 'generic');

const runLock = new RunLock(Object.keys(SLOTS));

const STATIC_FILES = {
  '/': { file: 'index.html', type: 'text/html' },
  '/main.js': { file: 'main.js', type: 'application/javascript' },
  '/style.css': { file: 'style.css', type: 'text/css' },
};

function serveStatic(res, entry) {
  const filePath = path.join(PUBLIC_DIR, entry.file);
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
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

function handleDeployStream(req, res, slot, query) {
  if (!SLOTS[slot]) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end(`Unknown slot: ${slot}`);
    return;
  }
  if (SLOTS[slot].requiresVersion && !query.version) {
    res.writeHead(400, { 'Content-Type': 'text/plain' });
    res.end('version query parameter is required for this slot');
    return;
  }
  if (!runLock.tryAcquire(slot)) {
    res.writeHead(409, { 'Content-Type': 'text/plain' });
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

  const send = (event, data) => {
    if (closed) return;
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  };

  let finished = false;

  const args = SLOTS[slot].requiresVersion ? ['-Version', query.version] : [];
  const child = runScript(SLOTS[slot].scriptPath, args);

  child.on('line', (line) => send('log', { line }));
  child.on('exit', (code) => {
    if (finished) return;
    finished = true;
    send('done', { code });
    if (!closed) res.end();
    runLock.release(slot);
  });
  child.on('error', (err) => {
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

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`Deploy dashboard running at http://${HOST}:${PORT}`);
  });
}

module.exports = { server, runLock };
