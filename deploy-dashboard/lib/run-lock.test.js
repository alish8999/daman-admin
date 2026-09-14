const test = require('node:test');
const assert = require('node:assert');
const { RunLock } = require('./run-lock');

test('tryAcquire succeeds once then blocks until release', () => {
  const lock = new RunLock(['backend', 'frontend', 'generic']);
  assert.strictEqual(lock.tryAcquire('backend'), true);
  assert.strictEqual(lock.tryAcquire('backend'), false);
  lock.release('backend');
  assert.strictEqual(lock.tryAcquire('backend'), true);
});

test('slots are independent of each other', () => {
  const lock = new RunLock(['backend', 'frontend']);
  assert.strictEqual(lock.tryAcquire('backend'), true);
  assert.strictEqual(lock.tryAcquire('frontend'), true);
  assert.strictEqual(lock.isRunning('backend'), true);
  assert.strictEqual(lock.isRunning('frontend'), true);
});

test('tryAcquire throws for an unknown slot', () => {
  const lock = new RunLock(['backend']);
  assert.throws(() => lock.tryAcquire('nonexistent'));
});

test('statusAll reports running state for every slot', () => {
  const lock = new RunLock(['backend', 'frontend']);
  lock.tryAcquire('backend');
  assert.deepStrictEqual(lock.statusAll(), { backend: true, frontend: false });
});
