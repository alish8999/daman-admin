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
  ], {
    // stdin is deliberately 'ignore': nothing can type into a script spawned
    // here, so a script that unexpectedly prompts (SSH host-key confirmation,
    // a re-auth flow) gets EOF and fails fast instead of hanging forever with
    // the slot lock held and no visible reason in the dashboard.
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  const wireLines = (stream) => {
    const rl = readline.createInterface({ input: stream });
    rl.on('line', (line) => emitter.emit('line', line));
  };
  wireLines(child.stdout);
  wireLines(child.stderr);

  child.on('close', (code) => emitter.emit('exit', code));
  child.on('error', (err) => emitter.emit('error', err));

  // Exposed so the caller can track the OS process for cleanup on shutdown.
  // Undefined if the spawn itself failed.
  emitter.pid = child.pid;

  return emitter;
}

module.exports = { runScript };
