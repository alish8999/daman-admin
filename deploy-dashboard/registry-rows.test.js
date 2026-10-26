const test = require('node:test');
const assert = require('node:assert');
const registryRows = require('./public/registry-rows');

test('reads the bare array an older backend answers with', () => {
  const rows = [{ clientCode: 'acme' }];
  assert.strictEqual(registryRows(rows), rows);
});

test('reads the stores array of the newer fleet object', () => {
  const rows = [{ clientCode: 'acme' }];
  assert.strictEqual(registryRows({ stores: rows, committedStores: 1 }), rows);
});

test('returns null for anything that is neither shape', () => {
  assert.strictEqual(registryRows(null), null);
  assert.strictEqual(registryRows({ error: 'nope' }), null);
  assert.strictEqual(registryRows({ stores: 'x' }), null);
  assert.strictEqual(registryRows('text'), null);
});
