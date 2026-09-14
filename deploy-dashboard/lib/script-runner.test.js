const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { runScript } = require('./script-runner');

function writeTempScript(content) {
  const file = path.join(
    os.tmpdir(),
    `script-runner-test-${Date.now()}-${Math.random().toString(16).slice(2)}.ps1`
  );
  fs.writeFileSync(file, content);
  return file;
}

test('runScript streams stdout lines and reports a zero exit code', async () => {
  const script = writeTempScript('Write-Host "hello"\nWrite-Host "world"\nexit 0\n');
  const lines = [];
  const emitter = runScript(script, []);
  const exitCode = await new Promise((resolve) => {
    emitter.on('line', (line) => lines.push(line));
    emitter.on('exit', resolve);
  });
  fs.unlinkSync(script);
  assert.strictEqual(exitCode, 0);
  assert.deepStrictEqual(lines, ['hello', 'world']);
});

test('runScript reports a non-zero exit code on failure', async () => {
  const script = writeTempScript('Write-Host "boom"\nexit 1\n');
  const emitter = runScript(script, []);
  const exitCode = await new Promise((resolve) => {
    emitter.on('exit', resolve);
  });
  fs.unlinkSync(script);
  assert.strictEqual(exitCode, 1);
});

test('runScript forwards arguments to the script', async () => {
  const script = writeTempScript('param([string]$Version)\nWrite-Host "got:$Version"\nexit 0\n');
  const lines = [];
  const emitter = runScript(script, ['-Version', '9.9.9']);
  await new Promise((resolve) => {
    emitter.on('line', (line) => lines.push(line));
    emitter.on('exit', resolve);
  });
  fs.unlinkSync(script);
  assert.ok(lines.includes('got:9.9.9'));
});
