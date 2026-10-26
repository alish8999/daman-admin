const path = require('path');

// lib/slots.js -> deploy-dashboard/ -> daman-admin/
const ADMIN_ROOT = path.join(__dirname, '..', '..');

// The hosted-store deployment (see Docs/superpowers/specs/2026-10-24-online-store-provisioning-design.md).
// Per-store values (client code, port, hostnames) are NOT here: provisioning owns them in the
// registry. Only what is a property of the shared deployment lives here, fixed server-side, because
// a deploy is triggered by a plain GET and none of these may ever come from the request.
const HOSTED_STORE = {
  pagesProject: 'daman-store',
  // The one Pages project every hosted store is served from - the only frontend URL that is
  // a property of the shared deployment rather than of a single tenant.
  sharedSiteUrl: 'https://daman-store.pages.dev/',
};

// `args` are fixed command-line arguments passed to the script on every run.
const SLOTS = {
  backend: {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-admin-backend.ps1'),
    label: 'Admin Backend',
  },
  frontend: {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-admin-frontend.ps1'),
    label: 'Admin Frontend',
  },
  generic: {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-generic-release.ps1'),
    label: 'Generic Release',
    requiresVersion: true,
  },
  'store-backend': {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-store-backend.ps1'),
    label: 'Hosted Store Backend (all stores)',
    // Shared jar for every store, restarted one at a time by the server: no per-store arguments.
    args: [],
    // The restart-all route on the REMOTE portal is admin-token-gated, so this script needs a
    // Bearer token. It cannot come from the environment at `npm start`: admin sessions live in
    // the remote backend's memory (SessionService) and die whenever IT restarts - including on
    // the admin-backend deploy this same dashboard runs, which logs every admin out. The page
    // already holds a freshly pasted one (the Create online client card's remote portal token);
    // the server takes it from a POST and hands it to the script in its ENVIRONMENT, never on
    // the command line, where Windows shows one process's argv to every other process.
    requiresAdminToken: true,
  },
  // The two halves of 'store-backend', as separate buttons. Each is its own slot so each holds its
  // own run lock: a Deploy and a Restart all cannot collide, and neither blocks the other's card.
  // Both stay SCRIPT slots rather than API calls on purpose: restart-all is a synchronous walk with
  // up to 120 s of health wait per store, and proxied through admin-api.damansoft.com it would hit
  // Cloudflare's ~100 s edge timeout (524) while the walk carried on server-side. Same token rule
  // as above: the script's preflight needs a valid admin token even when it only restarts.
  'store-deploy-only': {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-store-backend.ps1'),
    label: 'Hosted Store Backend (deploy only, no restart)',
    args: ['-NoRestart'],
    requiresAdminToken: true,
  },
  'store-restart-all': {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-store-backend.ps1'),
    label: 'Hosted Store Backend (restart all, no deploy)',
    args: ['-RestartOnly'],
    requiresAdminToken: true,
  },
  'store-frontend': {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-store-frontend.ps1'),
    label: 'Hosted Store Frontend (shared)',
    args: ['-PagesProject', HOSTED_STORE.pagesProject],
  },
};

module.exports = { SLOTS, HOSTED_STORE };
