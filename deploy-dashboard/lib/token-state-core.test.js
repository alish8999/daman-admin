const test = require('node:test');
const assert = require('node:assert');
const { nextTokenState, tokenMessage } = require('./token-state-core');

test('a 401 flips an active token to expired', () => {
  assert.strictEqual(nextTokenState('active', { hasValue: true, lastStatus: 401 }), 'expired');
});

test('a 2xx flips an expired token back to active', () => {
  assert.strictEqual(nextTokenState('expired', { hasValue: true, lastStatus: 200 }), 'active');
  assert.strictEqual(nextTokenState('expired', { hasValue: true, lastStatus: 204 }), 'active');
});

test('no value pasted reads missing whatever the last status was', () => {
  for (const lastStatus of [200, 401, 503, null]) {
    assert.strictEqual(nextTokenState('active', { hasValue: false, lastStatus }), 'missing');
    assert.strictEqual(nextTokenState('expired', { hasValue: false, lastStatus }), 'missing');
  }
});

test('a 5xx or a network failure does NOT read as expired — the token may be fine', () => {
  for (const lastStatus of [500, 502, 503, 524, null, undefined, 0]) {
    assert.strictEqual(nextTokenState('active', { hasValue: true, lastStatus }), 'active');
  }
  // ...and it does not rescue an already-expired token either: no evidence either way.
  assert.strictEqual(nextTokenState('expired', { hasValue: true, lastStatus: 503 }), 'expired');
  assert.strictEqual(nextTokenState('expired', { hasValue: true, lastStatus: null }), 'expired');
});

test('a 403 or 404 is not an expiry either', () => {
  assert.strictEqual(nextTokenState('active', { hasValue: true, lastStatus: 403 }), 'active');
  assert.strictEqual(nextTokenState('active', { hasValue: true, lastStatus: 404 }), 'active');
});

test('a freshly pasted value from a missing state reads active until something rejects it', () => {
  assert.strictEqual(nextTokenState('missing', { hasValue: true, lastStatus: null }), 'active');
});

test('the expired message names the restart as the cause', () => {
  assert.match(tokenMessage('portal', 'expired'), /the backend restarted/);
  assert.match(tokenMessage('local', 'expired'), /the backend restarted/);
});

test('each kind names where to get a fresh token and which backend it belongs to', () => {
  assert.match(tokenMessage('portal', 'expired'), /admin\.damansoft\.com/);
  assert.match(tokenMessage('local', 'expired'), /8083/);
  assert.match(tokenMessage('portal', 'missing'), /admin\.damansoft\.com/);
  assert.match(tokenMessage('local', 'missing'), /8083/);
});

test('every kind and state has a non-empty message; unknown input throws', () => {
  for (const kind of ['portal', 'local']) {
    for (const state of ['active', 'expired', 'missing']) {
      assert.ok(tokenMessage(kind, state).length > 0);
    }
  }
  assert.throws(() => tokenMessage('other', 'active'));
  assert.throws(() => tokenMessage('portal', 'weird'));
});
