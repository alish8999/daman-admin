#!/usr/bin/env node
/**
 * Copies the add-on prices out of the admin and into the two places that cannot ask for them
 * themselves: the fallback shipped inside the POS jar, and the public website.
 *
 *   node daman-admin/sync-prices.mjs [--from <url>] [--check]
 *
 * `--from` defaults to the LIVE admin, because that is the copy the shops read; a developer
 * pointing it at localhost would write figures no shop will ever be quoted.
 * `--check` reports what would change and writes nothing — for a build that wants to fail when
 * the committed files have fallen behind.
 *
 * ── Why this exists ───────────────────────────────────────────────────────────────────────
 * Prices moved into the admin database on 2026-11-19 so the owner could change one from the
 * portal. Two consumers still cannot read it at the moment they need it:
 *
 *   1. `feature-catalog-bundled.json`, the floor a POS falls back to when it has never reached
 *      the server. It was maintained by hand and had already drifted — `mobilePos` was missing
 *      from it entirely, so a shop that had not yet phoned home showed that card with no price.
 *   2. damansoft.com, which is static HTML on Pages. Its tutorial section was quoting $39 for
 *      Product Recipes and $59 for the Manufacturing bundle while the app quoted $25 and $50 —
 *      a contradiction a customer could see by opening the app beside the page.
 *
 * ── How the website is kept honest ────────────────────────────────────────────────────────
 * Any element carrying `data-prices` has its `$NN` / `NN$` figures replaced, in order, by the
 * expressions listed there. An expression is a catalogue key, or `a+b` for a bundle total —
 * `productRecipes+manufacturing` is Recipes' own price plus Manufacturing's marginal one, which
 * is the figure a shop buying both actually pays. Nothing else in the page is touched, so the
 * copy around a price stays hand-written.
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const LIVE = 'https://admin-api.damansoft.com/api/public/feature-catalog';
const BUNDLED = join(ROOT, 'daman-backend/src/main/resources/feature-catalog-bundled.json');

/** Every website file that carries annotated prices. */
const WEB_FILES = [
  'daman-website/index.html',
  'daman-website/tutorials/index.html',
  'daman-website/tutorials/bom.html',
  'daman-website/tutorials/manufacturing.html',
];

const args = process.argv.slice(2);
const check = args.includes('--check');
const fromIndex = args.indexOf('--from');
const source = fromIndex === -1 ? LIVE : args[fromIndex + 1];

function fail(message) {
  console.error('sync-prices: ' + message);
  process.exit(1);
}

const response = await fetch(source).catch(e => fail('could not reach ' + source + ' — ' + e.message));
if (!response.ok) fail(source + ' answered HTTP ' + response.status);
const catalog = await response.json().catch(() => fail(source + ' did not answer with JSON'));
if (!catalog || !Array.isArray(catalog.features) || catalog.features.length === 0) {
  fail('the catalogue came back empty; refusing to write a price list nobody can sell from');
}

const byKey = new Map(catalog.features.map(f => [f.key, f]));

/** A key, or `a+b` meaning a's price plus b's (b being marginal on a). */
function valueOf(expression) {
  const parts = expression.split('+').map(s => s.trim());
  let total = 0;
  for (const key of parts) {
    const feature = byKey.get(key);
    if (!feature) fail('the website names "' + key + '", which the catalogue does not sell');
    if (feature.contactUs || typeof feature.price !== 'number') {
      fail('"' + key + '" is priced on enquiry and cannot be written into a page as a figure');
    }
    total += feature.price;
  }
  return total;
}

const changes = [];

// ── 1. The fallback shipped inside the POS jar ───────────────────────────────────────────
{
  const next = JSON.stringify({
    currency: catalog.currency || 'USD',
    features: catalog.features.map(f => ({
      key: f.key,
      price: typeof f.price === 'number' ? f.price : null,
      contactUs: f.contactUs === true,
      bundledWith: f.bundledWith ?? null,
    })),
  }, null, 2) + '\n';
  const current = existsSync(BUNDLED) ? readFileSync(BUNDLED, 'utf8') : '';
  if (current.replace(/\r\n/g, '\n') !== next) {
    changes.push('feature-catalog-bundled.json');
    if (!check) writeFileSync(BUNDLED, next, 'utf8');
  }
}

// ── 2. The website ───────────────────────────────────────────────────────────────────────
// Matches an opening tag that carries data-prices, then that element's text up to its close.
const ANNOTATED = /(<([a-zA-Z]+)\b[^>]*\bdata-prices="([^"]+)"[^>]*>)([\s\S]*?)(<\/\2>)/g;
/** `$25`, `25$`, and the Arabic pages' `٢٥$` are all just "a run of digits beside a dollar sign". */
const FIGURE = /\$\s?\d+|\d+\s?\$/g;

for (const relative of WEB_FILES) {
  const path = join(ROOT, relative);
  if (!existsSync(path)) fail('missing ' + relative);
  const raw = readFileSync(path, 'utf8');
  const crlf = raw.includes('\r\n');
  const body = raw.replace(/\r\n/g, '\n');

  let touched = 0;
  const next = body.replace(ANNOTATED, (whole, open, _tag, expressions, inner, close) => {
    const wanted = expressions.split(',').map(e => valueOf(e.trim()));
    let i = 0;
    const rewritten = inner.replace(FIGURE, (figure) => {
      if (i >= wanted.length) return figure;
      const value = wanted[i++];
      // Keep the side the dollar sign was on: the Arabic pages write 25$, the English $25.
      return figure.trimStart().startsWith('$') ? '$' + value : value + '$';
    });
    if (i !== wanted.length) {
      fail(relative + ': data-prices="' + expressions + '" lists ' + wanted.length
           + ' price(s) but the text holds ' + i);
    }
    if (rewritten !== inner) touched++;
    return open + rewritten + close;
  });

  if (touched > 0) {
    changes.push(relative + ' (' + touched + ' price' + (touched === 1 ? '' : 's') + ')');
    if (!check) writeFileSync(path, crlf ? next.replace(/\n/g, '\r\n') : next, 'utf8');
  }
}

// ── Report ───────────────────────────────────────────────────────────────────────────────
console.log('prices read from ' + source + ' (' + catalog.features.length + ' add-ons)');
if (changes.length === 0) {
  console.log('everything already matches.');
} else if (check) {
  console.error('OUT OF DATE:\n  ' + changes.join('\n  ') + '\nRun: node daman-admin/sync-prices.mjs');
  process.exit(2);
} else {
  console.log('updated:\n  ' + changes.join('\n  '));
}
