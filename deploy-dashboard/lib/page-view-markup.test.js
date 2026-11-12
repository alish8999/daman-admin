const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { FLEET_SLOTS } = require('./api-base-core');

// Source scans over the shipped page. The data-view attributes are hand-written markup, so the only
// thing that ties them to FLEET_SLOTS is this file; viewForSlot's own test cannot fail.
function readPublic(name) {
  const file = path.join(__dirname, '..', 'public', name);
  assert.ok(fs.existsSync(file), `page source moved or renamed, update this test: ${file}`);
  return fs.readFileSync(file, 'utf8');
}

const HTML = readPublic('index.html');
const CSS = readPublic('style.css');

/** The markup of every element tagged data-view="<view>", found by balancing same-name tags. */
function blocksFor(view) {
  const blocks = [];
  const open = new RegExp(`<([a-z0-9]+)\\b[^>]*\\bdata-view="${view}"[^>]*>`, 'g');
  let m;
  while ((m = open.exec(HTML)) !== null) {
    const tag = m[1];
    const re = new RegExp(`<(/?)${tag}\\b[^>]*>`, 'g');
    re.lastIndex = m.index + m[0].length;
    let depth = 1;
    let t;
    while (depth > 0 && (t = re.exec(HTML)) !== null) depth += t[1] ? -1 : 1;
    assert.strictEqual(depth, 0, `unbalanced <${tag}> in a data-view="${view}" block`);
    blocks.push(HTML.slice(m.index, re.lastIndex));
  }
  return blocks;
}

function slotsIn(blocks) {
  return blocks.flatMap((b) => [...b.matchAll(/\bdata-slot="([^"]+)"/g)].map((s) => s[1]));
}

test('every slot inside a data-view="stores" block is a FLEET_SLOT', () => {
  const blocks = blocksFor('stores');
  assert.ok(blocks.length > 0, 'found no data-view="stores" block - the scan is not reading the page');
  const slots = slotsIn(blocks);
  assert.ok(slots.length > 0, 'no data-slot found inside the stores blocks');
  slots.forEach((s) => assert.ok(FLEET_SLOTS.includes(s), `slot "${s}" sits in a stores block but is not in FLEET_SLOTS`));
});

test('no slot inside a data-view="deploy" block is a FLEET_SLOT', () => {
  const blocks = blocksFor('deploy');
  assert.ok(blocks.length > 0, 'found no data-view="deploy" block - the scan is not reading the page');
  const slots = slotsIn(blocks);
  assert.ok(slots.length > 0, 'no data-slot found inside the deploy blocks');
  slots.forEach((s) => assert.ok(!FLEET_SLOTS.includes(s), `slot "${s}" is in FLEET_SLOTS but sits in a deploy block`));
});

test('a tagged block cannot out-display the hidden attribute', () => {
  // .cards { display: grid } is an author rule and beats the UA's [hidden] { display: none },
  // so el.hidden = true silently left the build card on the Hosted stores page.
  assert.ok(/\bdata-view="/.test(HTML), 'index.html has no data-view blocks');
  assert.match(CSS, /\[data-view\]\[hidden\]\s*\{[^}]*display:\s*none\s*!important/);
});
