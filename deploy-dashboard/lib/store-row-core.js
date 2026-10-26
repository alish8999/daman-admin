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
    ACTIVE: ['restart', 'suspend'],
    SUSPENDED: ['resume', 'remove'],
    FAILED: ['resume', 'forget-row'],
    PROVISIONING: [],
  };

  function actionsFor(status) {
    return Object.prototype.hasOwnProperty.call(ACTIONS, status) ? ACTIONS[status].slice() : [];
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
    const base = live || REGISTRY_ONLY[s.status] || { text: s.status ? String(s.status).toLowerCase() : 'unknown', cls: 'idle' };
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
    } else if (s.status === 'FAILED') {
      out.detail = s.failedStage ? 'Provisioning failed at stage ' + s.failedStage + '.' : 'Provisioning failed.';
    } else if (s.status === 'PROVISIONING') {
      out.detail = 'Provisioning in progress.';
    } else if (s.liveStatus === 'stopped') {
      out.detail = 'Registered as active but its service is not running.';
    }
    return out;
  }

  // Mirrors HostedStoreProvisioningService.requireCapacity() /
  // HostedCapacity.hasRoomForAnotherStore: the box holds N stores when
  //   total - heap*(N-1) - RESERVE >= heap + MARGIN   =>   N = floor((total - RESERVE - MARGIN) / heap)
  // RESERVE and MARGIN are private constants on the server and are not in the response, so they are
  // copied here. That is why the label says "~": if they change on the server this drifts, and the
  // server's own guard stays the authority.
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

  return { actionsFor, rowLabel, capacityLabel, backupLabel, formatJarTime };
});
