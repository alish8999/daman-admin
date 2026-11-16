const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const SCRIPT = fs.readFileSync(
  path.join(__dirname, '..', '..', 'deploy-mobile-release.ps1'), 'utf8');

/**
 * The Android release's signature check is the only thing standing between an
 * unsigned APK and the public download page, so its failure has to mean what it
 * says.
 *
 * apksigner is a .bat wrapper around a JAR: it finds a JVM through JAVA_HOME or
 * `java` on PATH and nothing else. A plain PowerShell session on this machine has
 * neither - verified 2026-11-17: JAVA_HOME empty, `java` not on PATH - so the
 * wrapper printed "JAVA_HOME is not set" and exited non-zero, which the script
 * then reported as "apksigner refused" on an APK signed correctly with the
 * 4096-bit Damansoft key. A missing JDK and a bad signature must not share a
 * message.
 */

test('the script finds a JDK for apksigner instead of assuming one is on PATH', () => {
  const signerCall = SCRIPT.indexOf('$apksigner.FullName verify');
  assert.ok(signerCall > 0, 'the apksigner call moved - this check is reading the wrong script');
  const before = SCRIPT.slice(0, signerCall);
  assert.match(before, /JAVA_HOME/,
    'nothing resolves a JDK before apksigner runs; the wrapper will fail on a signed APK');
});

test('a missing JDK does not masquerade as a bad signature', () => {
  // Two separate throws: one says the signature was refused, one says there is no
  // JDK to check it with. One message for both sends the owner to rebuild an APK
  // that was never the problem.
  assert.match(SCRIPT, /throw "No JDK/,
    'no distinct failure for "cannot check the signature at all"');
});

test("apksigner's own warnings cannot abort the deploy", () => {
  // The script runs under $ErrorActionPreference = "Stop". In Windows PowerShell 5.1
  // a native command's stderr, redirected with 2>&1, arrives as ErrorRecords - and
  // under Stop the first one throws before the exit code is ever read. apksigner
  // prints a harmless "restricted method" warning on JDK 26 (verified 2026-11-17:
  // exit 0, "Verifies"), which was enough to kill the deploy with a
  // NativeCommandError that named no cause.
  const call = SCRIPT.indexOf('$apksigner.FullName verify');
  const guard = SCRIPT.indexOf("$ErrorActionPreference = 'Continue'");
  assert.ok(guard > 0 && guard < call,
    'the apksigner call still runs under Stop with its stderr merged - a warning will abort the deploy');
  assert.match(SCRIPT.slice(call), /\$ErrorActionPreference = \$/,
    'ErrorActionPreference is never restored after the apksigner call');
});
