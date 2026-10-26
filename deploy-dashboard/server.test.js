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
// Echoes its argv AND the admin token it was handed, so the tests can prove the token reaches
// the script's ENVIRONMENT and never its command line.
const storeBackendEchoScript = writeTempScript(
  'Write-Host "ARGS:[$($args -join \',\')]"\nWrite-Host "ENVTOKEN:$($env:DAMAN_ADMIN_TOKEN)"\nexit 0\n'
);
const storeFrontendEchoScript = writeTempScript(
  'param([string]$PagesProject)\nWrite-Host "ARGS:${PagesProject}"\nexit 0\n'
);
SLOTS['store-backend'].scriptPath = storeBackendEchoScript;
// The two split-out multi-store slots. Deploy-only is deliberately SLOW so a test can hold its
// lock while it starts the others; restart-only is the plain echo.
const storeDeployOnlySlowEchoScript = writeTempScript(
  'Write-Host "ARGS:[$($args -join \',\')]"\nWrite-Host "ENVTOKEN:$($env:DAMAN_ADMIN_TOKEN)"\nStart-Sleep -Seconds 3\nexit 0\n'
);
SLOTS['store-deploy-only'].scriptPath = storeDeployOnlySlowEchoScript;
SLOTS['store-restart-all'].scriptPath = storeBackendEchoScript;
SLOTS['store-frontend'].scriptPath = storeFrontendEchoScript;

// Regression test setup for the double-fire ('error' then 'exit') spawn-failure
// race: add a synthetic slot whose runScript is monkey-patched to emit both
// events in quick succession, exactly like a real Windows spawn failure can.
// This is done here (before server.js is required) because server.js captures
// `runScript` via destructuring at require-time, the same way it captures SLOTS.
const SPAWN_FAIL_SENTINEL = '__spawn_fail_sentinel__';
const originalRunScript = scriptRunner.runScript;
// Forwards EVERY argument, including the environment overlay - a stub that quietly drops one
// makes the server look like it never passed it.
scriptRunner.runScript = function (scriptPath, args, extraEnv) {
  if (scriptPath === SPAWN_FAIL_SENTINEL) {
    const emitter = new EventEmitter();
    setImmediate(() => {
      emitter.emit('error', new Error('spawn powershell.exe ENOENT'));
      emitter.emit('exit', null);
    });
    return emitter;
  }
  return originalRunScript(scriptPath, args, extraEnv);
};
SLOTS.spawnfail = { scriptPath: SPAWN_FAIL_SENTINEL, label: 'Spawn Fail Test' };

const { server, REMOTE_ADMIN_BACKEND, LOCAL_TARGET, adminTokens } = require('./server');
const PRODUCTION_REMOTE = { ...REMOTE_ADMIN_BACKEND };

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

// The admin token is posted ahead of the stream for every store-backend run. Each test that
// needs one stores it itself rather than leaning on a previous test having run. The value is a
// made-up UUID shaped like a real session token - no real token belongs in a repository.
const FAKE_ADMIN_TOKEN = '00000000-dead-4000-beef-000000000000';

test('store-backend without an admin token is refused before the slot is locked', async () => {
  adminTokens.delete('store-backend');
  const inherited = process.env.DAMAN_ADMIN_TOKEN;
  delete process.env.DAMAN_ADMIN_TOKEN; // a developer's own shell must not decide this test
  try {
    const res = await get('/api/deploy/store-backend/stream');
    assert.strictEqual(res.statusCode, 400);
    assert.ok(/token/i.test(res.body), `unhelpful refusal: ${res.body}`);
    // The run was never started, so the slot must still be free - a refusal that leaked the
    // lock would wedge the card at "already running" until the dashboard was restarted.
    const status = JSON.parse((await get('/api/status')).body);
    assert.strictEqual(status['store-backend'], false);
  } finally {
    if (inherited !== undefined) process.env.DAMAN_ADMIN_TOKEN = inherited;
  }
});

test('a posted admin token reaches the script in its environment, never on its command line', async () => {
  const stored = await post('/api/deploy/store-backend/token', { token: FAKE_ADMIN_TOKEN });
  assert.strictEqual(stored.statusCode, 200);
  // The store confirms without echoing the token back.
  assert.ok(!stored.body.includes(FAKE_ADMIN_TOKEN), `token echoed in the response: ${stored.body}`);

  const res = await get('/api/deploy/store-backend/stream');
  assert.strictEqual(res.statusCode, 200);
  assert.ok(res.body.includes(`ENVTOKEN:${FAKE_ADMIN_TOKEN}`), `token did not reach the script: ${res.body}`);
  assert.ok(res.body.includes('ARGS:[]'), `token (or anything else) leaked into argv: ${res.body}`);
});

test('a token containing shell metacharacters is rejected and never stored', async () => {
  // deploy-store-backend.ps1 interpolates the token into a single-quoted sh string on the
  // remote, so a straight quote in it would break out of that string.
  adminTokens.delete('store-backend');
  for (const bad of ["abc'; rm -rf /; echo '", 'has space', 'tiny', '']) {
    const res = await post('/api/deploy/store-backend/token', { token: bad });
    assert.strictEqual(res.statusCode, 400, `accepted a bad token: ${bad}`);
  }
  assert.strictEqual(adminTokens.has('store-backend'), false);
});

test('a slot that takes no admin token has no token endpoint', async () => {
  assert.strictEqual((await post('/api/deploy/store-frontend/token', { token: FAKE_ADMIN_TOKEN })).statusCode, 404);
  assert.strictEqual((await post('/api/deploy/nosuchslot/token', { token: FAKE_ADMIN_TOKEN })).statusCode, 404);
});

test('the token endpoint is covered by the same-origin guard', async () => {
  // It is an endpoint that takes a secret; a page the developer happens to be visiting must
  // not be able to plant one for the next deploy.
  const res = await post('/api/deploy/store-backend/token', { token: FAKE_ADMIN_TOKEN },
    { Origin: 'http://evil.example' });
  assert.strictEqual(res.statusCode, 403);
});

test('store-backend slot passes NO per-store arguments (one shared jar for every store)', async () => {
  await post('/api/deploy/store-backend/token', { token: FAKE_ADMIN_TOKEN });
  const res = await get('/api/deploy/store-backend/stream');
  assert.strictEqual(res.statusCode, 200);
  assert.ok(res.body.includes('ARGS:[]'), `script was handed arguments: ${res.body}`);
  assert.ok(res.body.includes('"code":0'));
});

test('store-frontend slot passes the fixed -PagesProject argument to its script', async () => {
  const res = await get('/api/deploy/store-frontend/stream');
  assert.strictEqual(res.statusCode, 200);
  assert.ok(
    // The log line is JSON-encoded in the SSE data, so the closing quote pins the exact end of the
    // name: a prefix match would also pass for the old per-tenant ARGS:daman-store-viora.
    res.body.includes('ARGS:daman-store"'),
    `script did not receive the fixed args: ${res.body}`
  );
  assert.ok(res.body.includes('"code":0'));
});

test('the fixed slot arguments cannot be overridden from the query string', async () => {
  // A GET triggers a real production deploy - user input must never reach the script's
  // parameters. Extra query params on a slot without requiresVersion are simply ignored.
  await post('/api/deploy/store-backend/token', { token: FAKE_ADMIN_TOKEN });
  const res = await get('/api/deploy/store-backend/stream?ClientCode=evil&Port=1&version=9.9.9');
  assert.ok(res.body.includes('ARGS:[]'));
  assert.ok(!res.body.includes('evil'));
});

test('the deploy-only slot passes -NoRestart and nothing else', async () => {
  await post('/api/deploy/store-deploy-only/token', { token: FAKE_ADMIN_TOKEN });
  const res = await get('/api/deploy/store-deploy-only/stream');
  assert.strictEqual(res.statusCode, 200);
  assert.ok(res.body.includes('ARGS:[-NoRestart]'), `wrong arguments: ${res.body}`);
  assert.ok(res.body.includes(`ENVTOKEN:${FAKE_ADMIN_TOKEN}`));
  assert.ok(res.body.includes('"code":0'));
});

test('the restart-only slot passes -RestartOnly and nothing else', async () => {
  await post('/api/deploy/store-restart-all/token', { token: FAKE_ADMIN_TOKEN });
  const res = await get('/api/deploy/store-restart-all/stream');
  assert.strictEqual(res.statusCode, 200);
  assert.ok(res.body.includes('ARGS:[-RestartOnly]'), `wrong arguments: ${res.body}`);
  assert.ok(res.body.includes(`ENVTOKEN:${FAKE_ADMIN_TOKEN}`));
  assert.ok(res.body.includes('"code":0'));
});

test('all three store slots require an admin token', () => {
  for (const name of ['store-backend', 'store-deploy-only', 'store-restart-all']) {
    assert.strictEqual(SLOTS[name].requiresAdminToken, true, `${name} must require a token`);
  }
});

test('the three store slots hold independent run locks', async () => {
  for (const name of ['store-backend', 'store-deploy-only', 'store-restart-all']) {
    await post(`/api/deploy/${name}/token`, { token: FAKE_ADMIN_TOKEN });
  }
  const slow = get('/api/deploy/store-deploy-only/stream'); // holds its lock ~3 s
  await new Promise((resolve) => setTimeout(resolve, 500));
  const status = JSON.parse((await get('/api/status')).body);
  assert.strictEqual(status['store-deploy-only'], true);
  assert.strictEqual(status['store-backend'], false);
  assert.strictEqual(status['store-restart-all'], false);
  // The same slot is locked...
  assert.strictEqual((await get('/api/deploy/store-deploy-only/stream')).statusCode, 409);
  // ...but the other two are not blocked by it.
  assert.strictEqual((await get('/api/deploy/store-restart-all/stream')).statusCode, 200);
  assert.strictEqual((await get('/api/deploy/store-backend/stream')).statusCode, 200);
  await slow;
});

test('the new store slots appear in /api/status with their own independent locks', async () => {
  const status = JSON.parse((await get('/api/status')).body);
  assert.strictEqual(status['store-backend'], false);
  assert.strictEqual(status['store-frontend'], false);
});

test('/api/health reports the shared store frontend alongside the admin ones, with no per-tenant check', async () => {
  const health = JSON.parse((await get('/api/health')).body);
  assert.ok(!('store-backend' in health), "a single tenant's API must not be reported as store health");
  for (const key of ['backend', 'frontend', 'store-frontend']) {
    assert.ok(key in health, `health is missing "${key}": ${JSON.stringify(health)}`);
    assert.strictEqual(typeof health[key], 'boolean');
  }
});

// ---- Remote portal proxy (Create online client) ------------------------------------------

// A local fake standing in for https://admin-api.damansoft.com. It records every request so the
// tests can prove exactly what was relayed. NOTHING here ever contacts the real portal.
function startFakeRemote(statusCode = 201, responseBody) {
  const seen = [];
  const fake = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      seen.push({ method: req.method, url: req.url, headers: req.headers, body });
      res.writeHead(statusCode, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(responseBody || { via: 'fake-remote', url: req.url }));
    });
  });
  return new Promise((resolve) => {
    fake.listen(0, '127.0.0.1', () => resolve({ fake, seen, port: fake.address().port }));
  });
}

function aimRemoteAt(fakePort) {
  Object.assign(REMOTE_ADMIN_BACKEND, { secure: false, host: '127.0.0.1', port: fakePort });
}

function restoreRemote() {
  Object.assign(REMOTE_ADMIN_BACKEND, PRODUCTION_REMOTE);
}

function post(pathAndQuery, bodyObj, headers) {
  const payload = typeof bodyObj === 'string' ? bodyObj : JSON.stringify(bodyObj);
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1', port, path: pathAndQuery, method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload), ...headers },
      },
      (res) => {
        let body = '';
        res.on('data', (c) => { body += c; });
        res.on('end', () => resolve({ statusCode: res.statusCode, body }));
      }
    );
    req.on('error', reject);
    req.end(payload);
  });
}

test('remote proxy target is the hosted admin API, not localhost', () => {
  assert.strictEqual(PRODUCTION_REMOTE.host, 'admin-api.damansoft.com');
  assert.strictEqual(PRODUCTION_REMOTE.secure, true);
  assert.strictEqual(PRODUCTION_REMOTE.port, 443);
});

test('POST /api/remote/hosted-stores rejects a cross-origin request and never reaches the remote', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    const body = { clientCode: 'acme', subdomain: 'acme', expiresAt: '2027-01-01' };
    const evil = await post('/api/remote/hosted-stores', body, { Origin: 'http://evil.example' });
    assert.strictEqual(evil.statusCode, 403);
    const noCors = await post('/api/remote/hosted-stores', body, { 'Sec-Fetch-Site': 'cross-site' });
    assert.strictEqual(noCors.statusCode, 403);
    // The read routes sit behind the same guard.
    const read = await get('/api/remote/clients', { Origin: 'http://evil.example' });
    assert.strictEqual(read.statusCode, 403);
    assert.strictEqual(seen.length, 0, 'a rejected request must not have been relayed');
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('a plain GET can never trigger a provision', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    const res = await get('/api/remote/hosted-stores?clientCode=acme&subdomain=acme&expiresAt=2027-01-01');
    assert.strictEqual(res.statusCode, 201); // the fake answers everything with 201
    assert.deepStrictEqual(seen.map((s) => [s.method, s.url]), [['GET', '/api/hosted-stores']]);
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('remote routes relay the Authorization header unmodified', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    const auth = 'Bearer remote-token-xyz';
    await get('/api/remote/clients', { Authorization: auth });
    await get('/api/remote/hosted-stores', { Authorization: auth });
    await get('/api/remote/hosted-stores/suggest-subdomain?clientCode=al%20karam', { Authorization: auth });
    const created = await post(
      '/api/remote/hosted-stores',
      { clientCode: 'acme', subdomain: 'acme', expiresAt: '2027-01-01', jwtSecret: 'smuggled', port: 1 },
      { Authorization: auth }
    );
    assert.strictEqual(created.statusCode, 201);
    assert.deepStrictEqual(seen.map((s) => `${s.method} ${s.url}`), [
      'GET /api/clients',
      'GET /api/hosted-stores',
      'GET /api/hosted-stores/suggest-subdomain?clientCode=al%20karam',
      'POST /api/hosted-stores',
    ]);
    for (const s of seen) assert.strictEqual(s.headers.authorization, auth);
    // Only the three owner-chosen fields are forwarded; anything else is dropped.
    assert.deepStrictEqual(JSON.parse(seen[3].body), { clientCode: 'acme', subdomain: 'acme', expiresAt: '2027-01-01' });
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('POST /api/remote/hosted-stores rejects a malformed body with 400 before relaying', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    assert.strictEqual((await post('/api/remote/hosted-stores', 'not json')).statusCode, 400);
    assert.strictEqual((await post('/api/remote/hosted-stores', { clientCode: 'acme' })).statusCode, 400);
    assert.strictEqual(seen.length, 0);
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('remote routes do not fall back to the local backend when the remote is unreachable', async () => {
  // Deliberately binds NOTHING on 8083: that is the owner's own local admin-backend port, and a
  // test that grabs it (or silently skips when it is in use) is worse than useless. The two
  // targets are separate exported objects with their own unreachable messages, and the test
  // immediately below pins LOCAL_TARGET, so "no fallback" is provable from the message alone.
  const closed = http.createServer();
  await new Promise((r) => closed.listen(0, '127.0.0.1', r));
  const deadPort = closed.address().port;
  await new Promise((r) => closed.close(r));
  aimRemoteAt(deadPort);
  try {
    const res = await get('/api/remote/clients', { Authorization: 'Bearer t' });
    assert.strictEqual(res.statusCode, 503);
    const msg = JSON.parse(res.body).error;
    assert.ok(msg.includes('admin-api.damansoft.com'), msg);
    assert.ok(!msg.includes('localhost:8083'), msg);
    const created = await post('/api/remote/hosted-stores', { clientCode: 'a', subdomain: 'a', expiresAt: 'x' });
    assert.strictEqual(created.statusCode, 503);
    assert.ok(JSON.parse(created.body).error.includes('admin-api.damansoft.com'));
  } finally {
    restoreRemote();
  }
});

test('the local proxy target is localhost:8083 over plain http, with its own unreachable message', () => {
  // Was a test that could not fail: it asserted the 503 message only when nothing happened to be
  // listening on 8083, and its else branch accepted any status from 200 to 599. Deterministic now,
  // exactly like the remote-target test above.
  assert.strictEqual(LOCAL_TARGET.host, 'localhost');
  assert.strictEqual(LOCAL_TARGET.port, 8083);
  assert.strictEqual(LOCAL_TARGET.secure, false);
  assert.ok(LOCAL_TARGET.unreachableMessage.includes('localhost:8083'));
  // The two targets cannot be confused for one another, in either direction.
  assert.notStrictEqual(LOCAL_TARGET.host, PRODUCTION_REMOTE.host);
  assert.ok(!LOCAL_TARGET.unreachableMessage.includes('admin-api.damansoft.com'));
  assert.ok(!PRODUCTION_REMOTE.unreachableMessage.includes('localhost:8083'));
});

test("the remote's 202 and its PROVISIONING row reach the page unchanged", async () => {
  // The whole point of the Critical fix: the remote accepts the provision and answers 202 with a
  // PROVISIONING row in well under Cloudflare's 100 s, and the page then polls the registry. If
  // this proxy rewrote the status (it used to send a 180 s timeout at a 2-minute blocking call,
  // which the edge turned into a 524 the page could only read as failure), the card would be back
  // to reporting failure for a provision that succeeded.
  const row = { clientCode: 'acme', status: 'PROVISIONING', failedStage: 'ALLOCATE' };
  const { fake, seen, port: fakePort } = await startFakeRemote(202, row);
  aimRemoteAt(fakePort);
  try {
    const res = await post('/api/remote/hosted-stores', { clientCode: 'acme', subdomain: 'acme' });
    assert.strictEqual(res.statusCode, 202);
    assert.deepStrictEqual(JSON.parse(res.body), row);
    assert.strictEqual(seen.length, 1);
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('a provision POST is no longer given a multi-minute timeout', async () => {
  // The server's own worst case already exceeded the old 180 s, and the edge gives up at 100 s
  // regardless; the long wait belongs in the page's registry poll, not in this request.
  const { PROVISION_TIMEOUT_MS } = require('./server');
  assert.ok(PROVISION_TIMEOUT_MS <= 60000,
    `a provision POST should now be a short write, got ${PROVISION_TIMEOUT_MS}ms`);
  assert.ok(PROVISION_TIMEOUT_MS >= 5000, 'but not so short that a slow link fails the hand-off');
});

test('a blank expiry is forwarded as an empty string (licence without an expiry)', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    const res = await post('/api/remote/hosted-stores', { clientCode: 'acme', subdomain: 'acme' });
    assert.strictEqual(res.statusCode, 201);
    assert.deepStrictEqual(JSON.parse(seen[0].body), { clientCode: 'acme', subdomain: 'acme', expiresAt: '' });
  } finally {
    restoreRemote();
    fake.close();
  }
});

// ---- Hosted-store lifecycle proxy (suspend / resume / restart / remove) ------------------
// Every test aims the remote at a local fake. Nothing here may reach admin-api.damansoft.com:
// these routes suspend, restart and permanently delete real customers' stores.

const { LIFECYCLE_PROXY_TIMEOUT_MS } = require('./server');

test('each lifecycle action proxies to the remote admin backend, not localhost', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote(200, { ok: true });
  aimRemoteAt(fakePort);
  try {
    for (const action of ['suspend', 'resume', 'restart', 'remove']) {
      const res = await post(`/api/remote/hosted-stores/acme-1/${action}`, '');
      assert.strictEqual(res.statusCode, 200, action);
    }
    assert.deepStrictEqual(seen.map((s) => `${s.method} ${s.url}`), [
      'POST /api/hosted-stores/acme-1/suspend',
      'POST /api/hosted-stores/acme-1/resume',
      'POST /api/hosted-stores/acme-1/restart',
      'POST /api/hosted-stores/acme-1/remove',
    ]);
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('a client code outside the allowlist is rejected with 400 before any proxying', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    const bad = ['..', '%2e%2e', 'a%20b', 'a%2Fb', '', '-lead', '_lead', 'a'.repeat(65), 'a.b', 'a%00b'];
    for (const code of bad) {
      const res = await post(`/api/remote/hosted-stores/${code}/remove`, '');
      assert.strictEqual(res.statusCode, 400, `code "${code}"`);
    }
    // 64 chars is the longest allowed.
    const ok = await post(`/api/remote/hosted-stores/${'a'.repeat(64)}/suspend`, '');
    assert.strictEqual(ok.statusCode, 201);
    assert.strictEqual(seen.length, 1, 'only the valid code may have been relayed');
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('an unknown action is 404 and never reaches the remote', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    for (const action of ['delete', 'start', 'Remove', '']) {
      const res = await post(`/api/remote/hosted-stores/acme/${action}`, '');
      assert.strictEqual(res.statusCode, 404, `action "${action}"`);
    }
    // A GET can never trigger a lifecycle action.
    const viaGet = await get('/api/remote/hosted-stores/acme/remove');
    assert.strictEqual(viaGet.statusCode, 404);
    assert.strictEqual(seen.length, 0);
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('lifecycle routes reject a cross-origin request', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    const evil = await post('/api/remote/hosted-stores/acme/remove', '', { Origin: 'http://evil.example' });
    assert.strictEqual(evil.statusCode, 403);
    const noCors = await post('/api/remote/hosted-stores/acme/remove', '', { 'Sec-Fetch-Site': 'cross-site' });
    assert.strictEqual(noCors.statusCode, 403);
    assert.strictEqual(seen.length, 0);
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('lifecycle routes relay the Authorization header unmodified', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    const auth = 'Bearer lifecycle-token-xyz';
    for (const action of ['suspend', 'resume', 'restart', 'remove']) {
      await post(`/api/remote/hosted-stores/acme/${action}`, '', { Authorization: auth });
    }
    assert.strictEqual(seen.length, 4);
    for (const s of seen) assert.strictEqual(s.headers.authorization, auth);
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('lifecycle timeout outlasts the backend 75 s health wait but stays under the 100 s edge limit', () => {
  assert.ok(LIFECYCLE_PROXY_TIMEOUT_MS > 75000);
  assert.ok(LIFECYCLE_PROXY_TIMEOUT_MS < 100000);
});

test('an unreachable remote on a lifecycle route is reported as 503, never answered locally', async () => {
  restoreRemote();
  Object.assign(REMOTE_ADMIN_BACKEND, { secure: false, host: '127.0.0.1', port: 1 });
  try {
    const res = await post('/api/remote/hosted-stores/acme/suspend', '');
    assert.strictEqual(res.statusCode, 503);
  } finally {
    restoreRemote();
  }
});

test('the scripts index.html loads are actually served (registry-rows.js used to 404)', async () => {
  for (const name of ['main.js', 'registry-rows.js', 'token-state-core.js', 'store-row-core.js']) {
    const res = await get('/' + name);
    assert.strictEqual(res.statusCode, 200, name);
    assert.ok(res.body.length > 0, name);
  }
});

// ---- Forget row: DELETE /api/remote/hosted-stores/{code} -------------------------------------
// Registry-only on the remote. Not Remove: that is POST .../remove and destroys the store's data.

function del(pathAndQuery, headers) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path: pathAndQuery, method: 'DELETE', headers }, (res) => {
      let body = '';
      res.on('data', (c) => { body += c; });
      res.on('end', () => resolve({ statusCode: res.statusCode, body }));
    });
    req.on('error', reject);
    req.end();
  });
}

test('forget-row proxies DELETE to the remote registry route, relaying the Authorization header', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote(200, { ok: true });
  aimRemoteAt(fakePort);
  try {
    const res = await del('/api/remote/hosted-stores/acme-1', { Authorization: 'Bearer t' });
    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(`${seen[0].method} ${seen[0].url}`, 'DELETE /api/hosted-stores/acme-1');
    assert.strictEqual(seen[0].headers.authorization, 'Bearer t');
  } finally {
    restoreRemote();
    fake.close();
  }
});

test('forget-row rejects a bad code and a cross-origin request before any proxying', async () => {
  const { fake, seen, port: fakePort } = await startFakeRemote();
  aimRemoteAt(fakePort);
  try {
    assert.strictEqual((await del('/api/remote/hosted-stores/a%2Fb')).statusCode, 400);
    assert.strictEqual((await del('/api/remote/hosted-stores/-lead')).statusCode, 400);
    assert.strictEqual((await del('/api/remote/hosted-stores/acme', { Origin: 'http://evil.example' })).statusCode, 403);
    assert.strictEqual(seen.length, 0);
  } finally {
    restoreRemote();
    fake.close();
  }
});
