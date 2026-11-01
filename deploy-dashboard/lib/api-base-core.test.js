const test = require('node:test');
const assert = require('node:assert');
const { mode, apiUrl, tokenSource, agentReachable, isAgentOnlySlot, agentUrl, AGENT_ORIGIN, resolveApiBase, ADMIN_API, PORTAL_ORIGIN } = require('./api-base-core');

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

test('the portal dev server on localhost is portal mode, not agent mode', () => {
  // The discriminator is the PATH, not the origin. Keying on origin alone made ng serve on
  // localhost:4201 look like the agent, so the page called the dashboard's proxy routes against
  // the dev server where they do not exist — and the feature could not be seen locally at all.
  assert.strictEqual(mode('http://localhost:4201', '/fleet-page/index.html'), 'portal');
  assert.strictEqual(mode('http://localhost:4201', '/fleet-page/'), 'portal');
});

test('the agent own page is still agent mode', () => {
  assert.strictEqual(mode('http://localhost:5500', '/'), 'agent');
  assert.strictEqual(mode('http://127.0.0.1:5500', '/index.html'), 'agent');
});

test('the deployed portal is portal mode however it is reached', () => {
  assert.strictEqual(mode(PORTAL_ORIGIN, '/fleet-page/index.html'), 'portal');
  assert.strictEqual(mode(PORTAL_ORIGIN, '/'), 'portal');
});

test('the API base comes from the portal, not a hardcoded production URL', () => {
  // The portal talks to localhost:8083 in dev and admin-api in production. Hardcoding the
  // production host meant a dev session called a backend it was never issued for, got a 401,
  // and the 401 handler then wiped the local token and bounced the user to /login -- a logout
  // loop caused entirely by calling the wrong server.
  assert.strictEqual(resolveApiBase('?api=' + encodeURIComponent('http://localhost:8083')),
    'http://localhost:8083');
  assert.strictEqual(resolveApiBase('?api=' + encodeURIComponent('https://admin-api.damansoft.com')),
    'https://admin-api.damansoft.com');
});

test('a missing or unusable api parameter falls back to production', () => {
  assert.strictEqual(resolveApiBase(''), ADMIN_API);
  assert.strictEqual(resolveApiBase('?other=1'), ADMIN_API);
});

test('an api parameter that is not an http(s) origin is refused', () => {
  // It reaches a fetch URL, so it is whitelisted by shape rather than trusted.
  assert.strictEqual(resolveApiBase('?api=' + encodeURIComponent('javascript:alert(1)')), ADMIN_API);
  assert.strictEqual(resolveApiBase('?api=' + encodeURIComponent('//evil.example.com')), ADMIN_API);
});

test('apiUrl builds against the resolved base', () => {
  assert.strictEqual(apiUrl('portal', '/api/remote/hosted-stores', 'http://localhost:8083'),
    'http://localhost:8083/api/hosted-stores');
});

test('the local token is the session too when the portal talks to the local backend', () => {
  // In development the portal's own backend IS localhost:8083 -- the same process the local
  // token authorises -- so the session already in storage is valid for both. Asking the user to
  // paste a token they are already logged in with is busywork.
  assert.strictEqual(tokenSource('portal', 'local', 'http://localhost:8083'), 'storage');
  assert.strictEqual(tokenSource('portal', 'local', 'http://127.0.0.1:8083'), 'storage');
});

test('the local token stays a paste when the portal talks to the real backend', () => {
  // In production they are different processes with separate session stores, and the portal
  // session is simply not valid on :8083.
  assert.strictEqual(tokenSource('portal', 'local', ADMIN_API), 'strip');
  assert.strictEqual(tokenSource('portal', 'local', 'https://admin-api.damansoft.com'), 'strip');
});

test('on the agent own page both tokens still come from the strip', () => {
  // There is no portal session to borrow: the page was not served by the portal.
  assert.strictEqual(tokenSource('agent', 'portal'), 'strip');
  assert.strictEqual(tokenSource('agent', 'local', 'http://localhost:8083'), 'strip');
});
