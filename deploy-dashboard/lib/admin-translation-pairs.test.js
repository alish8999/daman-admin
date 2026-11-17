const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

/**
 * `translation.service.ts` is one file with a flat `en: { … }` block and a flat `ar: { … }`
 * block, several hundred lines apart. Adding a UI string means adding it TWICE, and the two
 * ways that goes wrong are both silent:
 *
 *  - **Only the English half gets written.** Nothing fails; the Arabic UI renders the raw key
 *    (`featuresSaved`) in place of a sentence, and nobody notices until the owner — who reads
 *    this portal in Arabic — opens the screen. CLAUDE.md warns about exactly this for the POS's
 *    own translation file; the admin portal has the same shape and the same hazard.
 *
 *  - **A key is written twice in the same block.** TypeScript does catch that one (TS1117), but
 *    only at build time and only for an exact duplicate in one object literal — and it already
 *    happened once this cycle, on `lapsed`. A test that names the key beats a build error that
 *    names a line number.
 *
 * So: the two blocks must hold the same key set, and neither may repeat a key.
 */
const SERVICE = path.join(__dirname, '..', '..', 'daman-admin-frontend',
  'src', 'app', 'services', 'translation.service.ts');

/**
 * Keys of one top-level language block, in file order, duplicates included.
 *
 * <p>Deliberately a line scan rather than a parse: the blocks are flat, one key per line, and a
 * scanner cannot be fooled into "fixing" a file it misread. A key is `name:` or `'dotted.name':`
 * at exactly six spaces of indent — the nesting the blocks actually use — so a `{` inside a
 * translated sentence, an apostrophe, or a commented-out line cannot register as one.
 */
function keysOf(source, lang) {
  const start = source.indexOf(`\n    ${lang}: {`);
  assert.notStrictEqual(start, -1, `no "${lang}:" block found in translation.service.ts`);

  // The block ends at the first line that closes it at the same indent.
  const rest = source.slice(start + 1);
  const end = rest.search(/\n    \},?\r?\n/);
  assert.notStrictEqual(end, -1, `the "${lang}:" block is never closed at its own indent`);

  const keys = [];
  for (const line of rest.slice(0, end).split('\n')) {
    const m = line.match(/^ {6}(?:'([^']+)'|([A-Za-z_$][\w$]*)):/);
    if (m) keys.push(m[1] ?? m[2]);
  }
  return keys;
}

function duplicates(keys) {
  const seen = new Set();
  const dupes = new Set();
  for (const k of keys) {
    if (seen.has(k)) dupes.add(k);
    seen.add(k);
  }
  return [...dupes].sort();
}

const source = fs.readFileSync(SERVICE, 'utf8');
const en = keysOf(source, 'en');
const ar = keysOf(source, 'ar');

test('the en and ar blocks were both found and are not empty', () => {
  // A parser that silently matched nothing would make every assertion below vacuous.
  assert.ok(en.length > 200, `only ${en.length} en keys parsed — the scanner is reading the file wrong`);
  assert.ok(ar.length > 200, `only ${ar.length} ar keys parsed — the scanner is reading the file wrong`);
});

test('every admin translation key exists in both en and ar', () => {
  const enSet = new Set(en);
  const arSet = new Set(ar);
  assert.deepEqual([...enSet].filter(k => !arSet.has(k)).sort(), [],
    'keys present in en but MISSING FROM ar — the Arabic UI will show the raw key');
  assert.deepEqual([...arSet].filter(k => !enSet.has(k)).sort(), [],
    'keys present in ar but MISSING FROM en');
});

test('no key is defined twice in the same language block', () => {
  assert.deepEqual(duplicates(en), [], 'duplicate keys in the en block');
  assert.deepEqual(duplicates(ar), [], 'duplicate keys in the ar block');
});

/**
 * A template that pipes a key which does not exist renders THE KEY ITSELF — `featuresSaved`
 * where a sentence belongs. Nothing fails: not the build, not the type checker, not the two
 * tests above, because a key that was never added to either block is absent from both and so is
 * perfectly "paired". The only thing that catches it is looking at the screen in the right
 * language, which is how it reaches the owner instead of a test.
 *
 * So: every literal key a template or a component asks for must exist.
 *
 * <p>Literals only. `('storeType.' + value) | t` is built at runtime from a list the test cannot
 * see, so it is skipped rather than guessed at — a guard that invents keys would fail on correct
 * code, and a guard nobody trusts gets deleted.
 */
const SRC = path.join(__dirname, '..', '..', 'daman-admin-frontend', 'src', 'app');

function walkSrc(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walkSrc(full));
    else if (/\.(ts|html)$/.test(entry.name)) out.push(full);
  }
  return out;
}

test('every literal translation key a template asks for exists', () => {
  const known = new Set(en);
  const missing = new Map();

  for (const file of walkSrc(SRC)) {
    if (file.endsWith('translation.service.ts')) continue;
    const text = fs.readFileSync(file, 'utf8');

    // `'key' | t` and `"key" | t` in a template, and `instant('key')` / `instant("key")` in a
    // component. BOTH quote styles, because the dashboard template uses double quotes
    // throughout — 12 keys this guard was blind to on the one page the branch rewrote. The
    // negative lookbehind for `+` keeps concatenated keys out.
    const patterns = [
      /(?<![+\w])'([A-Za-z_$][\w$.]*)'\s*\|\s*t\b/g,
      /(?<![+\w])"([A-Za-z_$][\w$.]*)"\s*\|\s*t\b/g,
      /\.instant\(\s*'([A-Za-z_$][\w$.]*)'/g,
      /\.instant\(\s*"([A-Za-z_$][\w$.]*)"/g
    ];
    for (const re of patterns) {
      for (const m of text.matchAll(re)) {
        if (known.has(m[1])) continue;
        const where = path.relative(SRC, file).split(path.sep).join('/');
        if (!missing.has(m[1])) missing.set(m[1], where);
      }
    }
  }

  assert.deepEqual([...missing].map(([k, f]) => `${k}  (${f})`).sort(), [],
    'keys used in the UI but defined in NEITHER block — these render as raw keys on screen');
});
