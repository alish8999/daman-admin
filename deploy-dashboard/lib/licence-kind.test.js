const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

/**
 * `licenceKind()` and `isLicenceLive()` decide what the Clients list counts and what the licence
 * cards badge. Both answers are easy to get wrong in a way nobody notices:
 *
 *  - counting the 29 dev-machine licences as branches makes the fleet look twice its size;
 *  - treating `status === 'ACTIVE'` as "working" counts 43 expired, locked installs as live.
 *
 * The portal has no Angular test harness, so this is a source guard: it pins the RULES the
 * functions must encode, which is the part that would silently drift.
 */
function readSource() {
  const file = path.join(__dirname, '..', '..', 'daman-admin-frontend', 'src', 'app', 'models', 'licence-kind.ts');
  assert.ok(fs.existsSync(file), `licence-kind.ts moved or renamed, update this test: ${file}`);
  return fs.readFileSync(file, 'utf8');
}

test('licenceKind is a shared module, not a component method', () => {
  const src = readSource();
  assert.match(src, /export function licenceKind/,
    'it must be exported — the Clients list and the licence card both need it, and a component '
    + 'method cannot be imported by another component');
});

test('a non-ACTIVE licence is reported as revoked before anything else is considered', () => {
  const src = readSource();
  const body = src.slice(src.indexOf('export function licenceKind'));
  const revokedAt = body.indexOf("'revoked'");
  const devAt = body.indexOf("'dev'");
  assert.ok(revokedAt !== -1 && devAt !== -1, 'both branches must exist');
  assert.ok(revokedAt < devAt,
    'status is checked first: a revoked dev licence is revoked, not dev');
});

test('a shared machine is identified from the data, not from the backend or a label', () => {
  const src = readSource();
  assert.match(src, /export function sharedMachineIds/,
    'the shared-machine set must be derived here');
  assert.match(src, /clients\.size\s*>\s*1/,
    'the rule is "this machine serves more than one client", which is structural: a real till '
    + 'belongs to one business');
  // Comments stripped: the file EXPLAINS why dev-current is unusable, so a naive scan of the
  // whole source would match its own rationale.
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  assert.doesNotMatch(code, /devCurrent|dev_machine_id/,
    'dev-current reads the dev CHECKOUT, so it is always null on the hosted portal — the one '
    + 'place this page is actually used');
});

test('without a shared set nothing is guessed as dev', () => {
  const src = readSource();
  const body = src.slice(src.indexOf('export function licenceKind'));
  assert.match(body, /shared\s*&&/,
    'the dev branch must require the set — a missing badge beats a wrong one');
});

test('isLicenceLive prefers the server field and falls back to the expiry', () => {
  const src = readSource();
  const body = src.slice(src.indexOf('export function isLicenceLive'));
  assert.match(body, /typeof\s+licence\.live\s*===\s*'boolean'/,
    'the server computes this; trust it when present');
  assert.match(body, /expiresAt/, 'and fall back to the expiry for older payloads');
});
