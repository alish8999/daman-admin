const path = require('path');

// lib/slots.js -> deploy-dashboard/ -> daman-admin/
const ADMIN_ROOT = path.join(__dirname, '..', '..');

// The hosted viora store (see Docs/superpowers/specs/2026-09-15-hosted-store-instance-design.md).
// Everything the two store scripts need is fixed here, server-side: a deploy is triggered by a
// plain GET, so none of these values may ever come from the request.
const HOSTED_STORE = {
  clientCode: 'viora',
  port: 8090,
  pagesProject: 'daman-store',
  apiHost: 'viora-api.damansoft.com',
  siteUrl: 'https://viora.damansoft.com/',
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
    // The script reads the admin Bearer token from DAMAN_ADMIN_TOKEN (set before `npm start`).
    args: [],
  },
  'store-frontend': {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-store-frontend.ps1'),
    label: 'Hosted Store Frontend (shared)',
    args: ['-PagesProject', HOSTED_STORE.pagesProject],
  },
};

module.exports = { SLOTS, HOSTED_STORE };
