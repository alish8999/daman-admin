const test = require('node:test');
const assert = require('node:assert');
const { VIEW_STORES, VIEW_DEPLOY, resolveView, viewForSlot, isBlockVisible } = require('./page-view-core');

test('no view parameter means show everything', () => {
  assert.strictEqual(resolveView(''), null);
  assert.strictEqual(resolveView('?api=https%3A%2F%2Fx'), null);
});

test('an unrecognised view falls back to showing everything, never hiding everything', () => {
  assert.strictEqual(resolveView('?view=deployment'), null);   // typo
  assert.strictEqual(resolveView('?view='), null);
});

test('a recognised view resolves, alongside other parameters', () => {
  assert.strictEqual(resolveView('?api=https%3A%2F%2Fx&view=deploy'), VIEW_DEPLOY);
  assert.strictEqual(resolveView('?view=stores'), VIEW_STORES);
});

test('fleet slots belong to stores, everything else to deploy', () => {
  assert.strictEqual(viewForSlot('create-store'), VIEW_STORES);
  assert.strictEqual(viewForSlot('backend'), VIEW_DEPLOY);
});

test('a slot in neither list stays visible on Deployment rather than vanishing', () => {
  assert.strictEqual(viewForSlot('some-slot-added-next-year'), VIEW_DEPLOY);
});

test('a shared block with no data-view shows in both views', () => {
  assert.strictEqual(isBlockVisible('', VIEW_STORES), true);
  assert.strictEqual(isBlockVisible(undefined, VIEW_DEPLOY), true);
});

test('with no resolved view every block shows', () => {
  assert.strictEqual(isBlockVisible('deploy', null), true);
  assert.strictEqual(isBlockVisible('stores', null), true);
});

test('a block shows only in its own view', () => {
  assert.strictEqual(isBlockVisible('deploy', VIEW_STORES), false);
  assert.strictEqual(isBlockVisible('stores', VIEW_STORES), true);
});
