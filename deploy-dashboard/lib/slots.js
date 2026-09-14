const path = require('path');

// lib/slots.js -> deploy-dashboard/ -> daman-admin/
const ADMIN_ROOT = path.join(__dirname, '..', '..');

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
};

module.exports = { SLOTS };
