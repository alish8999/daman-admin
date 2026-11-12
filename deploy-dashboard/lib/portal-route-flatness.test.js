const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

// The portal (daman-admin-frontend) has no test runner, so this guard is a source scan living
// beside the only working suite — and it is the asset mount in THIS project that breaks.
function read(rel) {
  const file = path.join(__dirname, '..', '..', 'daman-admin-frontend', 'src', 'app', ...rel.split('/'));
  assert.ok(fs.existsSync(file), `portal source moved or renamed, update this test: ${file}`);
  return fs.readFileSync(file, 'utf8');
}

const ROUTES = read('app.routes.ts');
const FLEET  = read('pages/fleet/fleet.component.ts');

test('every FleetComponent route is ONE flat segment', () => {
  // The landmine: a nested path or a trailing slash makes the relative
  // 'fleet-page/index.html' resolve under it, miss the asset, hit the SPA fallback,
  // and render the whole Angular portal inside its own iframe.
  const paths = [...ROUTES.matchAll(/path:\s*'([^']*)'[^}]*FleetComponent/g)].map((m) => m[1]);
  assert.deepStrictEqual(paths, ['fleet', 'deployment']);
  paths.forEach((p) => assert.ok(!p.includes('/'), `route '${p}' is not a flat segment`));
});

test('both FleetComponent routes declare a view', () => {
  assert.match(ROUTES, /path:\s*'fleet'[^}]*view:\s*'stores'/);
  assert.match(ROUTES, /path:\s*'deployment'[^}]*view:\s*'deploy'/);
});

test('the iframe src stays a relative, literal-built path', () => {
  assert.match(FLEET, /'fleet-page\/index\.html\?api='/);
  assert.match(FLEET, /&view=/);
  assert.ok(!/'\/fleet-page/.test(FLEET), 'a leading slash breaks the asset mount');
});
