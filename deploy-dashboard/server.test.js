const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const { EventEmitter } = require('events');

const { SLOTS } = require('./lib/slots');
const scriptRunner = require('./lib/script-runner');

function writeTempScript(content) {
  const file = path.join(
    os.tmpdir(),
    `server-test-${Date.now()}-${Math.random().toString(16).slice(2)}.ps1`
  );
  fs.writeFileSync(file, content);
  return file;
}

// Point every slot at a fast, controllable throwaway script before server.js
// reads SLOTS at module-load time.
const fastOkScript = writeTempScript('Write-Host "line1"\nWrite-Host "line2"\nexit 0\n');
const slowScript = writeTempScript(
  'Write-Host "start"\nStart-Sleep -Seconds 3\nWrite-Host "end"\nexit 0\n'
);
SLOTS.backend.scriptPath = fastOkScript;
SLOTS.frontend.scriptPath = slowScript;
SLOTS.generic.requiresVersion = true;
SLOTS.generic.scriptPath = fastOkScript;

// The hosted-store slots pass FIXED arguments (declared in lib/slots.js). Point them at
// throwaway scripts that echo back exactly what they were called with, so the tests can
// prove those arguments really reach the script under their real parameter names.
const storeBackendEchoScript = writeTempScript(
  'param([string]$ClientCode, [int]$Port)\nWrite-Host "ARGS:${ClientCode}:${Port}"\nexit 0\n'
);
const storeFrontendEchoScript = writeTempScript(
  'param([string]$PagesProject, [string]$ExpectedApiHost)\nWrite-Host "ARGS:${PagesProject}:${ExpectedApiHost}"\nexit 0\n'
);
SLOTS['store-backend'].scriptPath = storeBackendEchoScript;
SLOTS['store-frontend'].scriptPath = storeFrontendEchoScript;

// Regression test setup for the double-fire ('error' then 'exit') spawn-failure
// race: add a synthetic slot whose runScript is monkey-patched to emit both
// events in quick succession, exactly like a real Windows spawn failure can.
// This is done here (before server.js is required) because server.js captures
// `runScript` via destructuring at require-time, the same way it captures SLOTS.
const SPAWN_FAIL_SENTINEL = '__spawn_fail_sentinel__';
const originalRunScript = scriptRunner.runScript;
scriptRunner.runScript = function (scriptPath, args) {
  if (scriptPath === SPAWN_FAIL_SENTINEL) {
    const emitter = new EventEmitter();
    setImmediate(() => {
      emitter.emit('error', new Error('spawn powershell.exe ENOENT'));
      emitter.emit('exit', null);
    });
    return emitter;
  }
  return originalRunScript(scriptPath, args);
};
SLOTS.spawnfail = { scriptPath: SPAWN_FAIL_SENTINEL, label: 'Spawn Fail Test' };

const { server } = require('./server');

// Assigned in test.before - the server binds an ephemeral port so `npm test`
// never collides with a dashboard the developer already has running on 5500.
let port;

function get(pathAndQuery, headers) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port, path: pathAndQuery, headers }, (res) => {
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => resolve({ statusCode: res.statusCode, body }));
    }).on('error', reject);
  });
}

test.before(async () => {
  await new Promise((resolve) => {
    server.listen(0, '127.0.0.1', resolve);
  });
  port = server.address().port;
});

test.after(() => {
  server.close();
  fs.unlinkSync(fastOkScript);
  fs.unlinkSync(slowScript);
  fs.unlinkSync(storeBackendEchoScript);
  fs.unlinkSync(storeFrontendEchoScript);
});

test('a cross-origin request to a deploy endpoint is rejected with 403', async () => {
  // A GET triggers a real production deploy, so any page the developer happens
  // to be visiting could fire one with <img src="http://127.0.0.1:5500/...">.
  // CORS would block reading the response, but the deploy would already have
  // happened - hence the server-side origin check.
  const res = await get('/api/deploy/backend/stream', { Origin: 'http://evil.example' });
  assert.strictEqual(res.statusCode, 403);

  // And the lock must not have been taken by the rejected request.
  const status = await get('/api/status');
  assert.strictEqual(JSON.parse(status.body).backend, false);
});

test('a no-cors style request (Sec-Fetch-Site: cross-site, no Origin) is rejected with 403', async () => {
  const res = await get('/api/deploy/backend/stream', { 'Sec-Fetch-Site': 'cross-site' });
  assert.strictEqual(res.statusCode, 403);
});

test('a same-origin request is allowed through the origin check', async () => {
  const res = await get('/api/status', {
    Origin: `http://127.0.0.1:${port}`,
    'Sec-Fetch-Site': 'same-origin',
  });
  assert.strictEqual(res.statusCode, 200);
});

test('generic slot with a malformed version returns 400', async () => {
  const res = await get('/api/deploy/generic/stream?version=not-a-version');
  assert.strictEqual(res.statusCode, 400);
});

test('unknown slot returns 404', async () => {
  const res = await get('/api/deploy/nope/stream');
  assert.strictEqual(res.statusCode, 404);
});

test('generic slot without version returns 400', async () => {
  const res = await get('/api/deploy/generic/stream');
  assert.strictEqual(res.statusCode, 400);
});

test('a slot streams log and done events and reaches exit code 0', async () => {
  const res = await get('/api/deploy/backend/stream');
  assert.strictEqual(res.statusCode, 200);
  assert.ok(res.body.includes('event: log'));
  assert.ok(res.body.includes('line1'));
  assert.ok(res.body.includes('line2'));
  assert.ok(res.body.includes('event: done'));
  assert.ok(res.body.includes('"code":0'));
});

test('a second concurrent run of the same slot is rejected with 409', async () => {
  const first = get('/api/deploy/frontend/stream'); // slow script, still running
  await new Promise((resolve) => setTimeout(resolve, 500));
  const second = await get('/api/deploy/frontend/stream');
  assert.strictEqual(second.statusCode, 409);
  await first; // let the slow one finish so it doesn't leak into later tests
});

test('a different slot is unaffected while frontend is busy', async () => {
  const busy = get('/api/deploy/frontend/stream');
  await new Promise((resolve) => setTimeout(resolve, 500));
  const res = await get('/api/deploy/backend/stream');
  assert.strictEqual(res.statusCode, 200);
  await busy;
});

test('a spawn failure that fires both error and exit does not crash the process', async () => {
  // Regression test for the finding on Task 5: a genuine spawn failure can
  // cause the underlying child process to emit both 'error' and 'exit'
  // ('close') in quick succession. Before the `finished` guard, the second
  // handler to run would call res.write() (via send()) after the first
  // handler had already called res.end(), throwing an uncaught
  // ERR_STREAM_WRITE_AFTER_END and crashing the whole process. This test
  // proves only one handler's effects land, and the process survives.
  const res = await get('/api/deploy/spawnfail/stream');
  assert.strictEqual(res.statusCode, 200);
  const doneCount = (res.body.match(/event: done/g) || []).length;
  assert.strictEqual(doneCount, 1, `expected exactly one done event, got: ${res.body}`);
  assert.ok(res.body.includes('Failed to start'));
  assert.ok(res.body.includes('"code":null'));

  // The lock for this slot must have been released exactly once too (the
  // guard also protects runLock.release() from being called twice).
  const status = await get('/api/status');
  const parsed = JSON.parse(status.body);
  assert.strictEqual(parsed.spawnfail, false);
});

test('store-backend slot passes the fixed viora -ClientCode/-Port arguments to its script', async () => {
  const res = await get('/api/deploy/store-backend/stream');
  assert.strictEqual(res.statusCode, 200);
  assert.ok(res.body.includes('ARGS:viora:8090'), `script did not receive the fixed args: ${res.body}`);
  assert.ok(res.body.includes('"code":0'));
});

test('store-frontend slot passes the fixed -PagesProject/-ExpectedApiHost arguments to its script', async () => {
  const res = await get('/api/deploy/store-frontend/stream');
  assert.strictEqual(res.statusCode, 200);
  assert.ok(
    res.body.includes('ARGS:daman-store-viora:viora-api.damansoft.com'),
    `script did not receive the fixed args: ${res.body}`
  );
  assert.ok(res.body.includes('"code":0'));
});

test('the fixed slot arguments cannot be overridden from the query string', async () => {
  // A GET triggers a real production deploy - user input must never reach the script's
  // parameters. Extra query params on a slot without requiresVersion are simply ignored.
  const res = await get('/api/deploy/store-backend/stream?ClientCode=evil&Port=1&version=9.9.9');
  assert.ok(res.body.includes('ARGS:viora:8090'));
  assert.ok(!res.body.includes('evil'));
});

test('the new store slots appear in /api/status with their own independent locks', async () => {
  const status = JSON.parse((await get('/api/status')).body);
  assert.strictEqual(status['store-backend'], false);
  assert.strictEqual(status['store-frontend'], false);
});

test('/api/health reports the hosted store frontend and API alongside the admin ones', async () => {
  const health = JSON.parse((await get('/api/health')).body);
  for (const key of ['backend', 'frontend', 'store-backend', 'store-frontend']) {
    assert.ok(key in health, `health is missing "${key}": ${JSON.stringify(health)}`);
    assert.strictEqual(typeof health[key], 'boolean');
  }
});
