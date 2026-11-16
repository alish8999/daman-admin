const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const { SLOTS } = require('./slots');

const HTML = fs.readFileSync(path.join(__dirname, '..', 'public', 'index.html'), 'utf8');

/**
 * The phone till's release lives on the Deployment page, beside the desktop one.
 *
 * It was first built as a button on the Clients page with its own Java service
 * driving the same script - a second implementation of a deploy, which is the
 * thing the slot table exists to prevent. These tests pin it to one door.
 */

test('the Android release is a slot, pointing at the script', () => {
  const slot = SLOTS['mobile'];
  assert.ok(slot, 'no "mobile" slot - the Android release has nowhere to run from');
  assert.match(slot.scriptPath, /deploy-mobile-release\.ps1$/);
  assert.ok(slot.label);
});

test('the Android release asks for a version, like the desktop one', () => {
  // Without it the script is run with no -Version and fails inside PowerShell,
  // where the message reaches nobody looking at the card.
  assert.strictEqual(SLOTS['mobile'].requiresVersion, true);
  assert.strictEqual(SLOTS['generic'].requiresVersion, true);
});

test('the Android release needs no admin token, because nothing remote is called', () => {
  // It uploads to R2 with the local wrangler login and pushes a git remote. A
  // token field on the card would be a thing to paste that changes nothing.
  assert.ok(!SLOTS['mobile'].requiresAdminToken);
});

test('the page has a card bound to that slot, inside the deploy view', () => {
  assert.match(HTML, /data-slot="mobile"/,
    'no card bound to the mobile slot - the slot exists but nothing on the page runs it');

  // The card must sit inside the Desktop-client release section, which is the
  // only part of the page the deploy view shows.
  const deployStart = HTML.indexOf('data-view="deploy" aria-label="Desktop client releases"');
  assert.ok(deployStart > 0, 'the Desktop client releases section moved - this check is reading the wrong page');
  assert.ok(HTML.indexOf('data-slot="mobile"') > deployStart,
    'the mobile card is outside the Desktop client releases section');
});

test('the build card offers android alongside the two Windows builds', () => {
  const checks = [...HTML.matchAll(/class="build-platform-check" value="([a-z0-9]+)"/g)].map((m) => m[1]);
  assert.deepStrictEqual(checks, ['win', 'winx86', 'android']);
});

test('android is not checked by default', () => {
  // Every release builds the two Windows installers; the APK is rebuilt only
  // when the phone till changed, and an hour of Gradle nobody asked for is a
  // bad default.
  const android = /value="android"([^>]*)>/.exec(HTML);
  assert.ok(android, 'no android platform checkbox');
  assert.ok(!android[1].includes('checked'), 'android must not be pre-checked');
});

// ── The version query ───────────────────────────────────────────────────────
// The server rejects a run of a requiresVersion slot with no ?version, and the
// page's own refusal ("Enter a version first.") never fires, because the check
// that appends it was written for 'generic' by name. The mobile card therefore
// showed a version box, sent nothing, and the run died on
// "version query parameter is required for this slot". The list of slots that
// need one belongs to the slot table; the page must read it as a list, not
// name a single slot.

const MAIN_JS = fs.readFileSync(path.join(__dirname, '..', 'public', 'main.js'), 'utf8');

test('the page knows every slot whose script needs a version', () => {
  const declared = /const VERSION_SLOTS = new Set\(\[([^\]]*)\]\)/.exec(MAIN_JS);
  assert.ok(declared, 'main.js has no VERSION_SLOTS list - the version query is gated on a slot name');

  const onThePage = [...declared[1].matchAll(/'([a-z0-9-]+)'/g)].map((m) => m[1]).sort();
  const inTheTable = Object.keys(SLOTS).filter((s) => SLOTS[s].requiresVersion).sort();
  assert.deepStrictEqual(onThePage, inTheTable,
    'a slot requires a version server-side but the page does not send one (or the reverse)');
});

test('the version query is appended from that list, not from a slot name', () => {
  const appended = /if \(VERSION_SLOTS\.has\(slot\)\) \{/.test(MAIN_JS);
  assert.ok(appended, 'the ?version= block is not guarded by VERSION_SLOTS');
});
