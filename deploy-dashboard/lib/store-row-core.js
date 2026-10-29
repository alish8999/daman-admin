// Pure logic for the hosted-store table. Loaded two ways, like token-state-core.js: require()d by
// the node tests, and as a plain <script> in the page (served at /store-row-core.js).
//
// There is deliberately NO per-store version anywhere in here. No endpoint reports one and every
// store runs the same shared jar, so a per-row version would be invented. The header shows the jar
// on disk; a row shows STALENESS, a separate axis from status ("running" AND "stale").
//
// The page is routinely newer than the backend it calls (they deploy separately), so every field
// the newer backend adds (liveStatus, staleness, backupAt) may be absent. Absent means "not
// reported", and is never rendered as a verdict.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.storeRowCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  // Per registry status. 'remove' (destroys data, keeps one backup) and 'forget-row' (registry row
  // only) are different operations on different rows and must never be offered together.
  const ACTIONS = {
    // 'sync-unit' re-renders the systemd unit from the registry row. A unit is written ONCE, at
    // provisioning, and a jar redeploy never rewrites it, so a store created before a template
    // change stays frozen on the old one until this is run. It is a no-op when already current.
    ACTIVE: ['restart', 'sync-unit', 'suspend'],
    SUSPENDED: ['resume', 'remove'],
    // NOT 'resume': the lifecycle /resume route only un-suspends and 409s on a FAILED row.
    // 'retry-provisioning' re-runs Create (which picks up at failedStage); it is a page action,
    // not a lifecycle route.
    FAILED: ['retry-provisioning', 'forget-row'],
    PROVISIONING: [],
  };

  function actionsFor(status) {
    return Object.prototype.hasOwnProperty.call(ACTIONS, status) ? ACTIONS[status].slice() : [];
  }

  // The lifecycle routes (POST /api/remote/hosted-stores/{code}/<route>) an action maps to. Actions
  // absent here are NOT lifecycle calls: retry-provisioning opens the New store panel, forget-row
  // is a DELETE. main.js posts only through this map, so an action cannot reach a route by accident.
  const LIFECYCLE_ROUTE = { restart: 'restart', 'sync-unit': 'sync-unit', suspend: 'suspend', resume: 'resume', remove: 'remove' };

  function lifecycleRouteFor(action) {
    return Object.prototype.hasOwnProperty.call(LIFECYCLE_ROUTE, action) ? LIFECYCLE_ROUTE[action] : null;
  }

  const LIVE = {
    running: { text: 'running', cls: 'ok' },
    starting: { text: 'starting', cls: 'warn' },
    stopped: { text: 'stopped', cls: 'bad' },
    suspended: { text: 'suspended', cls: 'idle' },
    failed: { text: 'failed', cls: 'bad' },
  };

  // What to show when the backend sent no liveStatus: only what the registry itself says.
  const REGISTRY_ONLY = {
    ACTIVE: { text: 'active', cls: 'idle' },
    SUSPENDED: { text: 'suspended', cls: 'idle' },
    FAILED: { text: 'failed', cls: 'bad' },
    PROVISIONING: { text: 'provisioning', cls: 'warn' },
  };

  function rowLabel(store) {
    const s = store || {};
    const live = LIVE[s.liveStatus];
    // A liveStatus this page does not know is NOT upgraded to the registry status (which would read
    // "active"): an unknown stays unknown. Only an ABSENT liveStatus (older backend) uses the registry.
    const unrecognised = !live && s.liveStatus !== undefined && s.liveStatus !== null && s.liveStatus !== '';
    const base = live || (unrecognised ? { text: 'unknown', cls: 'idle' } : null) || REGISTRY_ONLY[s.status] || { text: s.status ? String(s.status).toLowerCase() : 'unknown', cls: 'idle' };
    const out = { statusText: base.text, statusClass: base.cls, detail: '', stalenessClass: '' };

    if (s.liveStatus === 'running') {
      if (s.staleness === 'stale') {
        out.detail = 'Running an older jar than the one on disk. Restart it to load the new one.';
        out.stalenessClass = 'stale';
      } else if (s.staleness === 'current') {
        out.detail = 'Running the current jar.';
        out.stalenessClass = 'current';
      } else if (s.staleness === 'unknown') {
        // The start time or the jar timestamp could not be read. NOT "up to date".
        out.detail = 'Staleness unknown: cannot tell which jar this is running (start time or jar time unreadable).';
        out.stalenessClass = 'unknown';
      }
      // staleness absent (older backend): say nothing rather than guess.
    } else if (unrecognised) {
      out.detail = 'The backend reported a live status this page does not recognise: "' + String(s.liveStatus) + '".';
    } else if (s.status === 'FAILED') {
      out.detail = s.failedStage ? 'Provisioning failed at stage ' + s.failedStage + '.' : 'Provisioning failed.';
    } else if (s.status === 'PROVISIONING') {
      out.detail = 'Provisioning in progress.';
    } else if (s.liveStatus === 'stopped') {
      out.detail = 'Registered as active but its service is not running.';
    }
    return out;
  }

  // TWO paths, both permanent (same skew rule as registry-rows.js: this page is routinely newer or
  // older than the backend it calls):
  //  1. fleet.maxStores, sent by the backend. HostedCapacity.maxStores is the one definition of
  //     capacity and is also what makes provisioning refuse, so the number is authoritative and is
  //     shown without a tilde.
  //  2. An older backend sends no maxStores. Estimate it from the fleet numbers it does send,
  //     mirroring HostedCapacity.maxStores: floor((total - RESERVE - MARGIN) / heap). RESERVE and
  //     MARGIN are private to the server and not in the response, so they are copied here and the
  //     result is hedged with "~": if they change on the server this path drifts.
  // Do not delete path 2 and do not add a "remove once deployed" note: skew is how this is deployed.
  const NON_STORE_RESERVE_MB = 1024;
  const RAM_MARGIN_MB = 300;

  function capacityLabel(fleet) {
    if (!fleet || typeof fleet !== 'object') return '';
    const rows = Array.isArray(fleet.stores) ? fleet.stores.length : 0;
    // Every row counts, suspended and failed included: the server reserves a heap for each.
    const committed = Number.isFinite(fleet.committedStores) ? fleet.committedStores : rows;
    if (!Number.isFinite(fleet.totalMemoryMb) || !Number.isFinite(fleet.heapMb) || fleet.heapMb <= 0) {
      return committed + (committed === 1 ? ' store' : ' stores') + ' (capacity unknown)';
    }
    if (Number.isInteger(fleet.maxStores) && fleet.maxStores >= 0) {
      return committed + ' of ' + fleet.maxStores + ' slots';
    }
    const slots = Math.max(0, Math.floor((fleet.totalMemoryMb - NON_STORE_RESERVE_MB - RAM_MARGIN_MB) / fleet.heapMb));
    return committed + ' of ~' + slots + ' slots';
  }

  function pad(n) { return n < 10 ? '0' + n : String(n); }

  function absolute(ms) {
    const d = new Date(ms);
    return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate())
      + ' ' + pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ' UTC';
  }

  function ago(ms, nowMs) {
    const sec = Math.max(0, Math.round((nowMs - ms) / 1000));
    if (sec < 60) return 'just now';
    const min = Math.round(sec / 60);
    if (min < 60) return min + ' min ago';
    const h = Math.floor(min / 60);
    if (h < 48) return h + ' h ago';
    return Math.floor(h / 24) + ' d ago';
  }

  function stamp(iso, nowMs) {
    const ms = Date.parse(iso);
    if (!Number.isFinite(ms)) return null;
    return absolute(ms) + ' (' + ago(ms, nowMs) + ')';
  }

  function size(bytes) {
    if (!Number.isFinite(bytes) || bytes < 0) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
  }

  // backupAt is a raw ISO instant, the string "unknown", null (no backup exists) or absent (older
  // backend that does not report it). The last two are different facts and read differently.
  function backupLabel(store, nowMs) {
    const s = store || {};
    if (s.backupAt === undefined) return '—';
    if (s.backupAt === null) return 'no backup';
    const when = s.backupAt === 'unknown' ? null : stamp(s.backupAt, nowMs);
    if (!when) return 'backup time unknown';
    const sz = size(s.backupSizeBytes);
    return sz ? when + ', ' + sz : when;
  }

  function formatJarTime(iso, nowMs) {
    return (typeof iso === 'string' && stamp(iso, nowMs)) || 'unknown';
  }

  // How old the last good read of the fleet is, for the "could not refresh" notice.
  function readAge(sinceMs, nowMs) {
    if (!Number.isFinite(sinceMs)) return 'age unknown';
    const sec = Math.max(0, Math.round((nowMs - sinceMs) / 1000));
    if (sec < 60) return sec + ' s old';
    const min = Math.round(sec / 60);
    if (min < 60) return min + ' min old';
    return Math.floor(min / 60) + ' h old';
  }


  // ── Live server stats ──────────────────────────────────────────────────────
  // DECIMAL units (1 GB = 1000^3 bytes), deliberately NOT the 1024-based units size() uses for
  // backups. The Hetzner console advertises this box as 4 GB RAM / 40 GB disk, which is the same
  // MemTotal / filesystem size in decimal; 1024-based units would read 3.7 / 37 beside it and look
  // like a fault in the server. Do not "correct" these to 1024.
  const GB = 1e9;
  const MB = 1e6;

  function isCount(n) { return typeof n === 'number' && Number.isFinite(n) && n >= 0; }

  function dec(n) { return n < 10 ? n.toFixed(1) : String(Math.round(n)); }

  // "used / total UNIT", in the total's unit so the pair reads on one scale. Null when either is unknown.
  function pairLabel(used, total) {
    if (!isCount(used) || !isCount(total) || total <= 0 || used > total) return null;
    const div = total >= GB ? GB : MB;
    return dec(used / div) + ' / ' + dec(total / div) + (div === GB ? ' GB' : ' MB');
  }

  function uptimeLabel(sec) {
    if (!isCount(sec)) return null;
    if (sec >= 86400) return 'up ' + Math.floor(sec / 86400) + 'd';
    if (sec >= 3600) return 'up ' + Math.floor(sec / 3600) + 'h';
    return 'up ' + Math.floor(sec / 60) + 'm';
  }

  // The box-health strip. '' (render nothing) when the backend sent no boxStats (older backend, or
  // the box could not read them). A single unreadable field is omitted, never shown as 0.
  function boxLabel(fleet) {
    const b = fleet && typeof fleet === 'object' ? fleet.boxStats : null;
    if (!b || typeof b !== 'object') return '';
    const parts = [];
    const ram = isCount(b.ramTotalBytes) && isCount(b.ramAvailableBytes)
      ? pairLabel(b.ramTotalBytes - b.ramAvailableBytes, b.ramTotalBytes) : null;
    if (ram) parts.push('RAM ' + ram);
    if (isCount(b.load1)) {
      const cores = Number.isInteger(b.cores) && b.cores > 0 ? ' (' + b.cores + (b.cores === 1 ? ' core)' : ' cores)') : '';
      parts.push('load ' + b.load1.toFixed(2) + cores);
    }
    const disk = isCount(b.diskTotalBytes) && isCount(b.diskFreeBytes)
      ? pairLabel(b.diskTotalBytes - b.diskFreeBytes, b.diskTotalBytes) : null;
    if (disk) parts.push('disk ' + disk);
    const up = uptimeLabel(b.uptimeSeconds);
    if (up) parts.push(up);
    return parts.length ? ['box'].concat(parts).join(' · ') : '';
  }

  // One store's own memory. undefined = backend does not report it -> '' (nothing). A unit that is
  // not running has no memory to report -> 'not running'. null/garbage for a unit that should be up
  // -> 'unknown', never 0.
  // Live statuses with no process to measure. The one definition: a status not in here and not
  // reported as a number reads 'unknown', so a new non-running status must be added here.
  const NOT_RUNNING = new Set(['stopped', 'suspended', 'failed']);

  // Whether to show the Memory column at all: only when the backend reports memoryBytes for some
  // row (null counts as reported; absent/undefined is an older backend). Carries the permanent
  // page-newer-than-backend skew rule, so it lives here where it is tested, not in main.js.
  function hasMemory(rows) {
    return Array.isArray(rows) && rows.some((s) => s && s.memoryBytes !== undefined);
  }

  function memoryLabel(bytes, liveStatus) {
    if (bytes === undefined) return '';
    if (NOT_RUNNING.has(liveStatus)) return 'not running';
    if (!isCount(bytes)) return 'unknown';
    return bytes >= GB ? dec(bytes / GB) + ' GB' : Math.round(bytes / MB) + ' MB';
  }

  return { boxLabel, memoryLabel, hasMemory, lifecycleRouteFor, readAge, actionsFor, rowLabel, capacityLabel, backupLabel, formatJarTime };
});
