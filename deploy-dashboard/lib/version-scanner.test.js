const test = require('node:test');
const assert = require('node:assert');
const { findLatestGenericVersion, compareVersions } = require('./version-scanner');

test('compareVersions orders semver correctly', () => {
  assert.ok(compareVersions('1.0.4', '1.0.3') > 0);
  assert.ok(compareVersions('1.0.3', '1.0.4') < 0);
  assert.strictEqual(compareVersions('1.0.4', '1.0.4'), 0);
  assert.ok(compareVersions('1.2.0', '1.10.0') < 0);
});

test('findLatestGenericVersion picks the highest version among matching files', () => {
  const fakeFs = {
    readdirSync: () => [
      'Daman_1.0.2_generic.exe',
      '32-Daman_1.0.2_generic.exe',
      'Daman_1.0.4_generic.exe',
      '32-Daman_1.0.4_generic.exe',
      'Daman_1.0.3_generic.exe',
      'readme.txt',
    ],
  };
  assert.strictEqual(findLatestGenericVersion('/fake/dir', fakeFs), '1.0.4');
});

test('findLatestGenericVersion returns null when the directory has no matches', () => {
  const fakeFs = { readdirSync: () => ['readme.txt'] };
  assert.strictEqual(findLatestGenericVersion('/fake/dir', fakeFs), null);
});

test('findLatestGenericVersion returns null when the directory does not exist', () => {
  const fakeFs = {
    readdirSync: () => { throw new Error('ENOENT'); },
  };
  assert.strictEqual(findLatestGenericVersion('/missing/dir', fakeFs), null);
});

/** A stand-in for fs, listing exactly these names. */
function fakeDir(names) {
  return { readdirSync: () => names };
}

// ── The 2026-11-17 rename ───────────────────────────────────────────────────
// The installer became Daman-Accounting_<ver>_generic.exe. clients-build/ holds
// both names for as long as the older builds sit there, and the scanner has to
// read a folder with a mixture without picking the wrong latest.

test('finds the latest under the new name', () => {
  const dir = fakeDir([
    'Daman-Accounting_1.5.1_generic.exe',
    '32-Daman-Accounting_1.5.1_generic.exe',
  ]);
  assert.strictEqual(findLatestGenericVersion('x', dir), '1.5.1');
});

test('reads a folder holding both the old and the new name', () => {
  const dir = fakeDir([
    'Daman_1.5.0_generic.exe',
    'Daman-Accounting_1.5.1_generic.exe',
  ]);
  assert.strictEqual(findLatestGenericVersion('x', dir), '1.5.1');
});

test('an older new-name build does not beat a newer old-name one', () => {
  const dir = fakeDir([
    'Daman_1.6.0_generic.exe',
    'Daman-Accounting_1.5.1_generic.exe',
  ]);
  assert.strictEqual(findLatestGenericVersion('x', dir), '1.6.0');
});

test('the 32-bit build is still not counted, under either name', () => {
  // Only the 64-bit filename is the signal; counting both would make the card
  // report a version that has only half a release behind it.
  const dir = fakeDir(['32-Daman-Accounting_9.9.9_generic.exe']);
  assert.strictEqual(findLatestGenericVersion('x', dir), null);
});

test('the APK is not a desktop release', () => {
  const dir = fakeDir(['Daman-Accounting_1.5.1_generic.apk']);
  assert.strictEqual(findLatestGenericVersion('x', dir), null);
});
