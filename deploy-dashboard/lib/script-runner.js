const { spawn } = require('child_process');
const { EventEmitter } = require('events');
const readline = require('readline');

// `extraEnv` is overlaid on this process's environment for the child only. Secrets belong
// here rather than in `args`: a command line is world-readable on Windows (any process can
// read another's argv) and lands in shell history and crash dumps, while an environment
// block is only readable by the process itself and its parent.
function runScript(scriptPath, args = [], extraEnv = null) {
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
    env: extraEnv ? { ...process.env, ...extraEnv } : process.env,
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
