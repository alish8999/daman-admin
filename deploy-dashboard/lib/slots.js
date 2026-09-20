const path = require('path');

// lib/slots.js -> deploy-dashboard/ -> daman-admin/
const ADMIN_ROOT = path.join(__dirname, '..', '..');

// The hosted viora store (see Docs/superpowers/specs/2026-09-15-hosted-store-instance-design.md).
// Everything the two store scripts need is fixed here, server-side: a deploy is triggered by a
// plain GET, so none of these values may ever come from the request.
const HOSTED_STORE = {
  clientCode: 'viora',
  port: 8090,
  pagesProject: 'daman-store-viora',
  apiHost: 'viora-api.damansoft.com',
  siteUrl: 'https://viora.damansoft.com/',
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
    label: `Hosted Store Backend (${HOSTED_STORE.clientCode})`,
    args: ['-ClientCode', HOSTED_STORE.clientCode, '-Port', String(HOSTED_STORE.port)],
  },
  'store-frontend': {
    scriptPath: path.join(ADMIN_ROOT, 'deploy-store-frontend.ps1'),
    label: `Hosted Store Frontend (${HOSTED_STORE.clientCode})`,
    args: ['-PagesProject', HOSTED_STORE.pagesProject, '-ExpectedApiHost', HOSTED_STORE.apiHost],
  },
};

module.exports = { SLOTS, HOSTED_STORE };
