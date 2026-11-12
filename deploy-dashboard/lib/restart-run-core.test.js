const test = require('node:test');
const assert = require('node:assert');
const { describeRestartRun } = require('./restart-run-core');

const ok = (c) => ({ clientCode: c, restarted: true, healthy: true, error: null });

test('DONE counts the stores actually restarted and mentions skipped ones', () => {
  const out = describeRestartRun({ state: 'DONE', walked: [ok('a'), ok('b'), ok('c')], skipped: [{ clientCode: 'z', reason: 'SUSPENDED' }] });
  assert.deepStrictEqual([out.done, out.kind], [true, 'success']);
  assert.match(out.text, /3 stores restarted, 1 skipped/);
});

test('DONE with one store uses the singular and no skipped clause', () => {
  const out = describeRestartRun({ state: 'DONE', walked: [ok('a')], skipped: [] });
  assert.match(out.text, /1 store restarted\./);
});

test('DONE only counts entries whose restarted flag is true (pins the field name)', () => {
  const out = describeRestartRun({ state: 'DONE', walked: [ok('a'), { clientCode: 'b', restarted: false, healthy: true }] });
  assert.match(out.text, /1 store restarted/);
});

test('HALTED is a failure naming the unhealthy store and its error', () => {
  const out = describeRestartRun({ state: 'HALTED', walked: [ok('viora'), { clientCode: 'acme', restarted: true, healthy: false, error: 'no health in 120s' }] });
  assert.deepStrictEqual([out.done, out.kind], [true, 'failure']);
  assert.match(out.text, /HALTED at acme \(no health in 120s\)/);
  assert.match(out.text, /2 stores were reached/);
  assert.match(out.text, /NOT restarted/);
});

test('HALTED with no unhealthy entry still fails and does not throw', () => {
  const out = describeRestartRun({ state: 'HALTED', walked: [] });
  assert.strictEqual(out.kind, 'failure');
  assert.match(out.text, /unknown store/);
});

test('NOOP is a failure, never a success', () => {
  const out = describeRestartRun({ state: 'NOOP', walked: [], skipped: [] });
  assert.deepStrictEqual([out.done, out.kind], [true, 'failure']);
  assert.match(out.text, /NOTHING/);
  assert.match(out.text, /not a success/);
});

test('IDLE is a terminal failure, not a success', () => {
  const out = describeRestartRun({ state: 'IDLE', walked: [], skipped: [] });
  assert.deepStrictEqual([out.done, out.kind], [true, 'failure']);
});

test('an unknown or garbage state is a terminal failure', () => {
  for (const run of [{ state: 'WEIRD' }, {}, null, undefined, 'x']) {
    const out = describeRestartRun(run);
    assert.deepStrictEqual([out.done, out.kind], [true, 'failure']);
  }
});

test('RUNNING keeps polling and names the current store', () => {
  const out = describeRestartRun({ state: 'RUNNING', currentClientCode: 'acme', walked: [ok('viora')] });
  assert.deepStrictEqual([out.done, out.kind], [false, '']);
  assert.match(out.text, /acme now/);
  assert.match(out.text, /1 done so far/);
});

test('walked and skipped absent or empty do not throw', () => {
  assert.match(describeRestartRun({ state: 'DONE' }).text, /0 stores restarted/);
  assert.strictEqual(describeRestartRun({ state: 'RUNNING' }).done, false);
  assert.strictEqual(describeRestartRun({ state: 'DONE', walked: null, skipped: null }).kind, 'success');
});
