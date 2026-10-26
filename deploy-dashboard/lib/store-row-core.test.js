const test = require('node:test');
const assert = require('node:assert');
const { actionsFor, rowLabel, capacityLabel, backupLabel, formatJarTime } = require('./store-row-core');

test('an ACTIVE store offers restart and suspend, never remove', () => {
  assert.deepStrictEqual(actionsFor('ACTIVE'), ['restart', 'suspend']);
  assert.ok(!actionsFor('ACTIVE').includes('remove'));
});

test('a SUSPENDED store offers resume and remove', () => {
  assert.deepStrictEqual(actionsFor('SUSPENDED'), ['resume', 'remove']);
});

test('a FAILED store offers resume and forget-row, never remove', () => {
  assert.deepStrictEqual(actionsFor('FAILED'), ['resume', 'forget-row']);
  assert.ok(!actionsFor('FAILED').includes('remove'));
});

test('a PROVISIONING store offers no actions', () => {
  assert.deepStrictEqual(actionsFor('PROVISIONING'), []);
});

test('an unrecognised or missing status offers no actions rather than guessing', () => {
  assert.deepStrictEqual(actionsFor('SOMETHING_NEW'), []);
  assert.deepStrictEqual(actionsFor(undefined), []);
});

test('remove is never offered together with forget-row, on any status', () => {
  for (const s of ['ACTIVE', 'SUSPENDED', 'FAILED', 'PROVISIONING']) {
    const a = actionsFor(s);
    assert.ok(!(a.includes('remove') && a.includes('forget-row')), s);
  }
});

test('a running store with staleness "stale" explains itself rather than showing a version number', () => {
  const l = rowLabel({ status: 'ACTIVE', liveStatus: 'running', staleness: 'stale' });
  assert.strictEqual(l.statusText, 'running');
  assert.match(l.detail, /older|restart/i);
  assert.doesNotMatch(l.detail, /\bv?\d+\.\d+/, 'no version number');
  assert.strictEqual(l.stalenessClass, 'stale');
});

test('a running current store says it runs the current jar', () => {
  const l = rowLabel({ status: 'ACTIVE', liveStatus: 'running', staleness: 'current' });
  assert.match(l.detail, /current/i);
});

test('staleness "unknown" reads as unknown, never as up to date', () => {
  const l = rowLabel({ status: 'ACTIVE', liveStatus: 'running', staleness: 'unknown' });
  assert.match(l.detail, /unknown|cannot tell|could not/i);
  assert.doesNotMatch(l.detail, /up to date|current/i);
  assert.strictEqual(l.stalenessClass, 'unknown');
});

test('staleness is only ever described for a running row', () => {
  const l = rowLabel({ status: 'SUSPENDED', liveStatus: 'suspended', staleness: 'stale' });
  assert.doesNotMatch(l.detail, /older|stale/i);
});

test('an older backend with no live fields still renders sensibly, with no "undefined"', () => {
  for (const status of ['ACTIVE', 'SUSPENDED', 'FAILED', 'PROVISIONING']) {
    const l = rowLabel({ status, failedStage: status === 'FAILED' ? 'dns' : null });
    for (const v of Object.values(l)) assert.doesNotMatch(String(v), /undefined|null/, status);
    assert.ok(l.statusText.length > 0, status);
  }
  // ...and it must not claim a staleness verdict it was never given.
  const l = rowLabel({ status: 'ACTIVE' });
  assert.doesNotMatch(l.detail, /current|up to date/i);
});

test('a FAILED row names the stage it failed at', () => {
  const l = rowLabel({ status: 'FAILED', liveStatus: 'failed', failedStage: 'DNS' });
  assert.match(l.detail, /DNS/);
});

test('each live status gets its own pill class', () => {
  const classes = ['running', 'starting', 'stopped', 'suspended', 'failed']
    .map((liveStatus) => rowLabel({ status: 'ACTIVE', liveStatus }).statusClass);
  assert.strictEqual(classes[0], 'ok');
  assert.ok(classes.every((c) => typeof c === 'string' && c));
});

test('capacity counts every row including suspended ones', () => {
  const fleet = {
    stores: [{ status: 'ACTIVE' }, { status: 'SUSPENDED' }],
    committedStores: 2, totalMemoryMb: 3900, heapMb: 768,
  };
  // (3900 - 1024 reserve - 300 margin) / 768 = 3.35 -> 3 slots in total
  assert.strictEqual(capacityLabel(fleet), '2 of ~3 slots');
});

test('capacity falls back to the row count when the backend sends no committedStores', () => {
  const fleet = { stores: [{}, {}, {}], totalMemoryMb: 3900, heapMb: 768 };
  assert.strictEqual(capacityLabel(fleet), '3 of ~3 slots');
});

test('capacity is honest when it cannot be computed', () => {
  assert.strictEqual(capacityLabel(null), '');
  assert.strictEqual(capacityLabel({ stores: [{}] }), '1 store (capacity unknown)');
  assert.strictEqual(capacityLabel({ stores: [{}, {}] }), '2 stores (capacity unknown)');
});

test('capacity never goes below zero slots', () => {
  assert.strictEqual(capacityLabel({ committedStores: 1, totalMemoryMb: 500, heapMb: 768 }), '1 of ~0 slots');
});

const NOW = Date.parse('2026-10-26T12:00:00Z');

test('a backup time is formatted, not shown raw', () => {
  const s = backupLabel({ backupAt: '2026-10-26T09:00:00Z', backupSizeBytes: 5 * 1024 * 1024 }, NOW);
  assert.match(s, /3 h ago/);
  assert.match(s, /5\.0 MB/);
  assert.doesNotMatch(s, /T09:00:00Z/);
});

test('backupAt "unknown" is said plainly', () => {
  assert.match(backupLabel({ backupAt: 'unknown' }, NOW), /unknown/i);
});

test('no backup vs a backend that does not report one are different things', () => {
  assert.match(backupLabel({ backupAt: null }, NOW), /no backup/i);
  assert.strictEqual(backupLabel({}, NOW), '—');
});

test('an unparseable backupAt is reported as unknown, not as NaN', () => {
  const s = backupLabel({ backupAt: 'garbage' }, NOW);
  assert.doesNotMatch(s, /NaN|undefined/);
  assert.match(s, /unknown/i);
});

test('relative ages cover minutes, hours and days', () => {
  assert.match(backupLabel({ backupAt: '2026-10-26T11:59:30Z' }, NOW), /just now/);
  assert.match(backupLabel({ backupAt: '2026-10-26T11:30:00Z' }, NOW), /30 min ago/);
  assert.match(backupLabel({ backupAt: '2026-10-23T12:00:00Z' }, NOW), /3 d ago/);
});

test('jar time is formatted, and a missing one is said plainly', () => {
  assert.match(formatJarTime('2026-10-26T09:00:00Z', NOW), /3 h ago/);
  assert.match(formatJarTime(null, NOW), /unknown/i);
  assert.match(formatJarTime(undefined, NOW), /unknown/i);
});
