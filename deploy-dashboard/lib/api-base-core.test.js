const test = require('node:test');
const assert = require('node:assert');
const { mode, apiUrl, tokenSource, agentReachable, isAgentOnlySlot, agentUrl, AGENT_ORIGIN, ADMIN_API, PORTAL_ORIGIN } = require('./api-base-core');

test('a localhost origin is agent mode; the portal origin is portal mode', () => {
  assert.strictEqual(mode('http://localhost:5500'), 'agent');
  assert.strictEqual(mode('http://127.0.0.1:5500'), 'agent');
  assert.strictEqual(mode(PORTAL_ORIGIN), 'portal');
});

test('an unrecognised origin is treated as portal, not agent', () => {
  // Erring toward portal is the safe default: agent mode would have the page try to drive builds
  // through a server that is not there, and quietly fail. Portal mode greys them instead.
  assert.strictEqual(mode('https://example.com'), 'portal');
  assert.strictEqual(mode(''), 'portal');
  assert.strictEqual(mode(undefined), 'portal');
});

test('remote paths map to the backend in portal mode', () => {
  assert.strictEqual(apiUrl('portal', '/api/remote/hosted-stores'),
    ADMIN_API + '/api/hosted-stores');
  assert.strictEqual(apiUrl('portal', '/api/remote/hosted-stores/demo1/suspend'),
    ADMIN_API + '/api/hosted-stores/demo1/suspend');
  assert.strictEqual(apiUrl('portal', '/api/remote/hosted-stores/demo1/reset-demo'),
    ADMIN_API + '/api/hosted-stores/demo1/reset-demo');
  assert.strictEqual(apiUrl('portal', '/api/remote/clients'), ADMIN_API + '/api/clients');
});

test('a query string survives the rewrite', () => {
  assert.strictEqual(apiUrl('portal', '/api/remote/clients?x=1'), ADMIN_API + '/api/clients?x=1');
});

test('agent mode leaves every path exactly as it is today', () => {
  for (const p of ['/api/remote/hosted-stores', '/api/status', '/api/deploy/backend/stream',
                   '/api/remote/clients']) {
    assert.strictEqual(apiUrl('agent', p), p);
  }
});

test('a build path has no portal equivalent and is reported as agent-only', () => {
  // Builds compile from the local repo and upload from the local disk. There is no hosted
  // equivalent, so asking for one is a programming error rather than a URL to guess at.
  for (const p of ['/api/deploy/backend/stream', '/api/build/generic', '/api/versions',
                   '/api/status']) {
    assert.strictEqual(apiUrl('portal', p), null, p);
  }
});

test('the portal token comes from storage in portal mode and the strip in agent mode', () => {
  assert.strictEqual(tokenSource('portal', 'portal'), 'storage');
  assert.strictEqual(tokenSource('agent', 'portal'), 'strip');
});

test('the local token always comes from the strip', () => {
  // It authorises the LOCAL admin backend on :8083, a different process with its own sessions.
  // The portal session is not valid there, so no mode can supply it from storage.
  assert.strictEqual(tokenSource('portal', 'local'), 'strip');
  assert.strictEqual(tokenSource('agent', 'local'), 'strip');
});

test('a probe that rejects, times out, or is refused all report the agent as unreachable', async () => {
  // To the user these are one thing: the build cards cannot be used. A declined Private Network
  // Access prompt, a blocked mixed-content request and a stopped agent are indistinguishable,
  // and should be — the remedy is the same.
  assert.strictEqual(await agentReachable(() => Promise.reject(new Error('blocked'))), false);
  assert.strictEqual(await agentReachable(() => Promise.resolve({ ok: false })), false);
  assert.strictEqual(await agentReachable(() => new Promise(() => {}), 20), false, 'timeout');
});

test('a probe that answers reports the agent as reachable', async () => {
  assert.strictEqual(await agentReachable(() => Promise.resolve({ ok: true })), true);
});

test('the probe never rejects', async () => {
  // A broken agent must not break the fleet, which is the half that works from anywhere.
  await assert.doesNotReject(() => agentReachable(() => { throw new Error('sync throw'); }));
});

test('create-store is NOT agent-only: it is a pure fleet action', () => {
  // Deriving the set from "/api/deploy/<slot>/stream has no portal equivalent" marked EVERY slot
  // agent-only, including the Create Hosted Store card — which is a plain /api/remote POST that
  // works perfectly from the portal. Greying it made the fleet depend on the owner's machine,
  // which the spec names as a failure condition.
  assert.strictEqual(isAgentOnlySlot('create-store'), false);
});

test('the build and release slots ARE agent-only', () => {
  for (const slot of ['store-backend', 'store-deploy-only', 'store-restart-all', 'backend',
                      'frontend', 'store-frontend', 'build-generic', 'generic']) {
    assert.strictEqual(isAgentOnlySlot(slot), true, slot);
  }
});

test('an unknown slot is treated as agent-only', () => {
  // A new build card added later is greyed until someone decides otherwise, rather than silently
  // enabled in a place it cannot work.
  assert.strictEqual(isAgentOnlySlot('something-new'), true);
});

test('agentUrl points at the local agent from the portal, and stays relative on the agent', () => {
  // Without this the build cards resolved root-absolute against the Pages host: the deploy stream
  // got index.html from the SPA fallback, and the /token POST sent the portal session to
  // Cloudflare. It also meant nothing ever called the agent, so its whole CORS + PNA surface --
  // which the spec called a requirement, not a nicety -- was never exercised.
  assert.strictEqual(agentUrl('portal', '/api/deploy/backend/stream'),
    AGENT_ORIGIN + '/api/deploy/backend/stream');
  assert.strictEqual(agentUrl('portal', '/api/versions'), AGENT_ORIGIN + '/api/versions');
  assert.strictEqual(agentUrl('agent', '/api/deploy/backend/stream'), '/api/deploy/backend/stream');
});
