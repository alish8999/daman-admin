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
