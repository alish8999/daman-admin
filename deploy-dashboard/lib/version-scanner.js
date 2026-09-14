const fs = require('fs');

const VERSION_PATTERN = /^Daman_(\d+\.\d+\.\d+)_generic\.exe$/;

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
