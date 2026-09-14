const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const { SLOTS } = require('./lib/slots');
const { RunLock } = require('./lib/run-lock');
const { findLatestGenericVersion } = require('./lib/version-scanner');

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

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`Deploy dashboard running at http://${HOST}:${PORT}`);
  });
}

module.exports = { server, runLock };
