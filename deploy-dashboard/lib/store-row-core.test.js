const test = require('node:test');
const assert = require('node:assert');
const { boxLabel, memoryLabel, actionsFor, rowLabel, capacityLabel, backupLabel, formatJarTime, readAge, lifecycleRouteFor } = require('./store-row-core');

test('an ACTIVE store offers restart and suspend, never remove', () => {
  assert.deepStrictEqual(actionsFor('ACTIVE'), ['restart', 'suspend']);
  assert.ok(!actionsFor('ACTIVE').includes('remove'));
});

test('a SUSPENDED store offers resume and remove', () => {
  assert.deepStrictEqual(actionsFor('SUSPENDED'), ['resume', 'remove']);
});

test('a FAILED store offers retry-provisioning and forget-row, never remove or resume', () => {
  assert.deepStrictEqual(actionsFor('FAILED'), ['retry-provisioning', 'forget-row']);
  assert.ok(!actionsFor('FAILED').includes('resume'), 'the lifecycle /resume 409s on anything but SUSPENDED');
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

test('capacity uses the backend maxStores verbatim, with no tilde', () => {
  const fleet = { stores: [{}, {}], committedStores: 2, maxStores: 5, totalMemoryMb: 3900, heapMb: 768 };
  // 5, not the 3 the local estimate would give: the backend is the authority.
  assertLabel(capacityLabel(fleet), '2 of 5 slots');
});

test('capacity trusts maxStores of 0 (a full box), not the fallback', () => {
  assertLabel(capacityLabel({ committedStores: 1, maxStores: 0, totalMemoryMb: 9000, heapMb: 768 }), '1 of 0 slots');
});

test('capacity ignores a malformed maxStores and falls back to the estimate, hedged', () => {
  assertLabel(capacityLabel({ committedStores: 2, maxStores: 'x', totalMemoryMb: 3900, heapMb: 768 }), '2 of ~3 slots');
  assertLabel(capacityLabel({ committedStores: 2, maxStores: -1, totalMemoryMb: 3900, heapMb: 768 }), '2 of ~3 slots');
});

function assertLabel(actual, expected) { assert.strictEqual(actual, expected); }

test('(older backend, no maxStores) capacity counts every row including suspended ones', () => {
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

test('an unrecognised liveStatus renders as unknown, never as the registry status', () => {
  const l = rowLabel({ status: 'ACTIVE', liveStatus: 'degraded' });
  assert.strictEqual(l.statusText, 'unknown');
  assert.doesNotMatch(l.statusText, /active/i);
  assert.match(l.detail, /degraded/);
  assert.strictEqual(l.stalenessClass, '');
});

test('an unrecognised liveStatus never gets a staleness verdict either', () => {
  const l = rowLabel({ status: 'ACTIVE', liveStatus: 'degraded', staleness: 'current' });
  assert.doesNotMatch(l.detail, /current jar/i);
});

test('readAge says how old the last good read is', () => {
  assert.strictEqual(readAge(NOW - 10000, NOW), '10 s old');
  assert.strictEqual(readAge(NOW - 5 * 60000, NOW), '5 min old');
  assert.strictEqual(readAge(NOW - 2 * 3600000, NOW), '2 h old');
  assert.strictEqual(readAge(NOW + 5000, NOW), '0 s old');
  assert.strictEqual(readAge(null, NOW), 'age unknown');
});

test('no row ever offers an action mapped to the lifecycle /resume route unless it is SUSPENDED', () => {
  // The regression guard for the whole class of mistake: "resume" meant two different things
  // (un-suspend vs re-run a half-finished provision) and the FAILED row was wired to the wrong one.
  for (const status of ['ACTIVE', 'SUSPENDED', 'FAILED', 'PROVISIONING', 'SOMETHING_NEW', undefined]) {
    const hitsResume = actionsFor(status).filter((a) => lifecycleRouteFor(a) === 'resume');
    if (status === 'SUSPENDED') assert.deepStrictEqual(hitsResume, ['resume']);
    else assert.deepStrictEqual(hitsResume, [], String(status));
  }
});

test('only the real lifecycle actions map to a route; retry-provisioning and forget-row do not', () => {
  assert.strictEqual(lifecycleRouteFor('suspend'), 'suspend');
  assert.strictEqual(lifecycleRouteFor('restart'), 'restart');
  assert.strictEqual(lifecycleRouteFor('remove'), 'remove');
  assert.strictEqual(lifecycleRouteFor('retry-provisioning'), null);
  assert.strictEqual(lifecycleRouteFor('forget-row'), null);
  assert.strictEqual(lifecycleRouteFor('nonsense'), null);
});

// Real readings from the box (decimal units on purpose: they must match the Hetzner console's 4 GB / 40 GB).
const BOX = { ramTotalBytes: 3906292 * 1024, ramAvailableBytes: 1596532 * 1024, load1: 0.01, cores: 2,
  diskTotalBytes: 39956590592, diskUsableBytes: 39956590592 - 3871309824, uptimeSeconds: 3687582.7 };

test('boxLabel renders the real box in decimal units', () => {
  assert.strictEqual(boxLabel({ boxStats: BOX }), 'box · RAM 2.4 / 4.0 GB · load 0.01 (2 cores) · disk 3.9 / 40 GB · up 42d');
});

test('boxLabel RAM used is total minus AVAILABLE (not free)', () => {
  const l = boxLabel({ boxStats: Object.assign({}, BOX, { ramAvailableBytes: 3000e6, ramTotalBytes: 4000e6 }) });
  assert.match(l, /RAM 1\.0 \/ 4\.0 GB/);
});

test('boxLabel is empty, not a skeleton, when boxStats is absent or null', () => {
  assert.strictEqual(boxLabel({ stores: [] }), '');
  assert.strictEqual(boxLabel({ boxStats: null }), '');
  assert.strictEqual(boxLabel(null), '');
  assert.strictEqual(boxLabel(undefined), '');
  assert.strictEqual(boxLabel({ boxStats: {} }), '');
});

test('boxLabel omits an unreadable field instead of showing 0 or undefined', () => {
  const l = boxLabel({ boxStats: { load1: 0.5, cores: 1, uptimeSeconds: 7200, ramTotalBytes: 'x' } });
  assert.strictEqual(l, 'box · load 0.50 (1 core) · up 2h');
  assert.doesNotMatch(l, /undefined|NaN|RAM|disk/);
});

test('boxLabel rejects impossible pairs (available > total) rather than render a negative', () => {
  const l = boxLabel({ boxStats: Object.assign({}, BOX, { ramAvailableBytes: BOX.ramTotalBytes + 1 }) });
  assert.doesNotMatch(l, /RAM/);
});

test('boxLabel uptime scales d / h / m', () => {
  const up = (s) => boxLabel({ boxStats: { uptimeSeconds: s } });
  assert.strictEqual(up(86400 * 3 + 5), 'box · up 3d');
  assert.strictEqual(up(5 * 3600 + 59), 'box · up 5h');
  assert.strictEqual(up(125), 'box · up 2m');
});

test('memoryLabel is decimal MB / GB', () => {
  assert.strictEqual(memoryLabel(682250240, 'running'), '682 MB');
  assert.strictEqual(memoryLabel(759160832, 'running'), '759 MB');
  assert.strictEqual(memoryLabel(1500000000, 'running'), '1.5 GB');
});

test('memoryLabel: absent field is blank, null is unknown, never 0', () => {
  assert.strictEqual(memoryLabel(undefined, 'running'), '');
  assert.strictEqual(memoryLabel(null, 'running'), 'unknown');
  assert.strictEqual(memoryLabel(NaN, 'running'), 'unknown');
  assert.strictEqual(memoryLabel(-1, 'running'), 'unknown');
  assert.strictEqual(memoryLabel(null, 'suspended'), 'not running');
  assert.strictEqual(memoryLabel(null, 'stopped'), 'not running');
});
