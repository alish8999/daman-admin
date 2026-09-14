const { spawn } = require('child_process');
const { EventEmitter } = require('events');
const readline = require('readline');

function runScript(scriptPath, args = []) {
  const emitter = new EventEmitter();
  const child = spawn('powershell.exe', [
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', scriptPath,
    ...args,
  ]);

  const wireLines = (stream) => {
    const rl = readline.createInterface({ input: stream });
    rl.on('line', (line) => emitter.emit('line', line));
  };
  wireLines(child.stdout);
  wireLines(child.stderr);

  child.on('exit', (code) => emitter.emit('exit', code));
  child.on('error', (err) => emitter.emit('error', err));

  return emitter;
}

module.exports = { runScript };
