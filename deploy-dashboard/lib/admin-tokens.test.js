const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

// The portal (daman-admin-frontend) has no test runner, so this guard is a source scan living
// beside the only working suite — same arrangement as portal-route-flatness.test.js.
//
// What it protects: the portal's surface is defined by a handful of CSS custom properties in
// ONE :root block. Every page styles against those tokens rather than hardcoding colours, so a
// change here is a change everywhere — which is the point, and also why it is worth pinning.
function readStyles() {
  const file = path.join(__dirname, '..', '..', 'daman-admin-frontend', 'src', 'styles.scss');
  assert.ok(fs.existsSync(file), `portal stylesheet moved or renamed, update this test: ${file}`);
  return fs.readFileSync(file, 'utf8');
}

test('the surface tokens match the agreed palette', () => {
  const css = readStyles();
  assert.match(css, /--admin-bg:\s*#f8fafc/i, 'canvas must be the light-gray #F8FAFC');
  assert.match(css, /--admin-surface:\s*#ffffff/i, 'cards must be pure white');
  assert.match(css, /--admin-border:\s*#e2e8f0/i, 'borders must be the subtle #E2E8F0');
});

test('a soft pill token pair exists for every status tone', () => {
  const css = readStyles();
  for (const tone of ['success', 'warning', 'neutral', 'danger']) {
    assert.match(css, new RegExp(`--admin-pill-bg-${tone}:`), `missing --admin-pill-bg-${tone}`);
    assert.match(css, new RegExp(`--admin-pill-fg-${tone}:`), `missing --admin-pill-fg-${tone}`);
  }
});

test('a status pill class exists and is fully rounded', () => {
  const css = readStyles();
  assert.match(css, /\.status-pill\b/, 'no .status-pill class');
});

// The specific thing the owner asked to be rid of: a whole table row washed green because its
// client is active. Status belongs in a pill, not in the row's background.
test('no row-level status tint survives', () => {
  const css = readStyles();
  assert.doesNotMatch(css, /\.row--active\b/, '.row--active is the green row wash — use .status-pill');
});

// The accent is NOT part of this change. Pinned so a future "modernisation" does not quietly
// rebrand the portal while retokenising its surface.
test('the brand accent is unchanged', () => {
  const css = readStyles();
  assert.match(css, /--admin-accent:\s*#0a4d68/i, 'the brand accent is not in scope for this work');
});
