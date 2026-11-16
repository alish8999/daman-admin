const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const { BUILD_PLATFORMS, resolveBuildPlatform } = require('../server');

const HTML = fs.readFileSync(path.join(__dirname, '..', 'public', 'index.html'), 'utf8');

/**
 * The build card's platform checkboxes and the server's allow-list have to be the
 * same list.
 *
 * They were not: the card offered 'android' while the server knew only 'win' and
 * 'winx86', and an unrecognised platform fell back to 'win' SILENTLY. Checking
 * "Android (.apk)" therefore built the 64-bit Windows installer, reported success,
 * and left no APK anywhere - and the only visible symptom was the Android deploy
 * failing later with "Not found: ...apk", which points at the wrong step entirely.
 */

test('every platform the build card offers is one the server accepts', () => {
  const offered = [...HTML.matchAll(/class="build-platform-check" value="([a-z0-9]+)"/g)].map((m) => m[1]);
  assert.ok(offered.length > 0, 'no platform checkboxes found - this check is reading the wrong page');
  for (const platform of offered) {
    assert.ok(BUILD_PLATFORMS.has(platform),
      `the card offers "${platform}" but the server does not accept it - the build would run as something else`);
  }
});

test('an unknown platform is refused, not quietly turned into Windows', () => {
  assert.strictEqual(resolveBuildPlatform('android'), 'android');
  assert.strictEqual(resolveBuildPlatform('win'), 'win');
  assert.strictEqual(resolveBuildPlatform('winx86'), 'winx86');
  // A caller that sends nothing still gets the historical default: the page always
  // sends a platform, so this only covers an older or hand-made request.
  assert.strictEqual(resolveBuildPlatform(''), 'win');
  assert.strictEqual(resolveBuildPlatform(undefined), 'win');
  // Anything else is a request for something this dashboard cannot build. Building
  // a different platform instead is worse than refusing.
  assert.strictEqual(resolveBuildPlatform('ios'), null);
  assert.strictEqual(resolveBuildPlatform('ANDROID'), null);
});
