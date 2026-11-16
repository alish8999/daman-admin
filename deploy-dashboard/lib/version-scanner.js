const fs = require('fs');

// The installer was renamed Daman -> Daman-Accounting on 2026-11-17. Both are
// matched because clients-build/ keeps every build ever made in one folder, so a
// scan that only knew the new name would report the latest release as whatever
// was built after the rename - and for a while that is older than what shipped.
// The 32-bit build is deliberately NOT matched, under either name: it is half a
// release, and counting it would announce a version with no 64-bit installer.
const VERSION_PATTERN = /^Daman(?:-Accounting)?_(\d+\.\d+\.\d+)_generic\.exe$/;

function compareVersions(a, b) {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if (pa[i] !== pb[i]) return pa[i] - pb[i];
  }
  return 0;
}

function findLatestGenericVersion(dir, fsImpl = fs) {
  let entries;
  try {
    entries = fsImpl.readdirSync(dir);
  } catch (err) {
    return null;
  }

  let latest = null;
  for (const name of entries) {
    const match = VERSION_PATTERN.exec(name);
    if (!match) continue;
    if (!latest || compareVersions(match[1], latest) > 0) {
      latest = match[1];
    }
  }
  return latest;
}

module.exports = { findLatestGenericVersion, compareVersions };
