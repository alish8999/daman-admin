const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

/**
 * Anything built on CDK Overlay — `cdkMenuTriggerFor`, `CdkDialog`, a connected popover —
 * renders into a container appended to <body> and positioned by CDK's own stylesheet. Without
 * `@angular/cdk/overlay-prebuilt.css` in the build's styles there is no positioning at all, so
 * the panel lands in normal document flow AT THE BOTTOM OF THE PAGE.
 *
 * It compiles, type-checks and builds perfectly — the failure is purely visual, which is why it
 * reached the owner rather than a test. Reported 2026-11-18, a few minutes after the Clients
 * row's ⋯ menu shipped: "القائمة عم تطلع تحت بأسفل الصفحة".
 *
 * So: if the source uses CDK overlay, the stylesheet must be in angular.json.
 */
const FRONTEND = path.join(__dirname, '..', '..', 'daman-admin-frontend');
const OVERLAY_CSS = 'node_modules/@angular/cdk/overlay-prebuilt.css';

function walk(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full));
    else if (entry.name.endsWith('.ts') || entry.name.endsWith('.html')) out.push(full);
  }
  return out;
}

function usesCdkOverlay() {
  const src = path.join(FRONTEND, 'src', 'app');
  return walk(src).some(f => /cdkMenuTriggerFor|cdkConnectedOverlay|CdkMenuTrigger|CdkDialog|OverlayModule/
    .test(fs.readFileSync(f, 'utf8')));
}

test('a build that uses CDK overlay also ships CDK overlay styles', () => {
  if (!usesCdkOverlay()) return;   // nothing to guard yet
  const angularJson = fs.readFileSync(path.join(FRONTEND, 'angular.json'), 'utf8');
  assert.ok(
    angularJson.includes(OVERLAY_CSS),
    `the portal uses CDK overlay but angular.json does not include ${OVERLAY_CSS} — without it `
    + 'every overlay renders unpositioned at the bottom of the page'
  );
});
