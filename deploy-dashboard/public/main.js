(function () {
  const cards = document.querySelectorAll('.card');

  // Slots this page itself started (an EventSource is open for them), so the
  // /api/status poll never fights the live stream over the same button.
  const ownedBySource = new Set();
  // Slots currently being polled, so we never stack two polls on one slot.
  const polling = new Set();

  const SCROLL_STICK_PX = 30;

  function appendLine(log, line) {
    // Measure BEFORE appending: only keep following the tail if the user was
    // already parked at the bottom, so scrolling up mid-run isn't yanked back.
    const atBottom =
      log.scrollHeight - log.scrollTop - log.clientHeight < SCROLL_STICK_PX;
    // append() beats `textContent +=`, which re-serializes the whole node on
    // every line (O(n^2) over a long deploy).
    log.append(line + '\n');
    if (atBottom) log.scrollTop = log.scrollHeight;
  }

  function pollUntilIdle(slot, btn, footer) {
    if (polling.has(slot)) return;
    polling.add(slot);

    const tick = () => {
      fetch('/api/status')
        .then((r) => r.json())
        .then((status) => {
          if (status[slot]) {
            setTimeout(tick, 2000);
            return;
          }
          polling.delete(slot);
          // A click of our own has taken over in the meantime - leave the
          // button and footer entirely to the EventSource flow.
          if (ownedBySource.has(slot)) return;
          btn.disabled = false;
          if (footer.textContent === 'Running...') {
            footer.textContent = '';
            footer.className = 'footer';
          }
        })
        .catch(() => {
          // Server gone or unreachable: stop polling and unblock the button
          // rather than leaving the card permanently frozen.
          polling.delete(slot);
          if (!ownedBySource.has(slot)) btn.disabled = false;
        });
    };

    setTimeout(tick, 2000);
  }

  // Shared by the Generic Release card's prefill and the build-info footer,
  // so a slow/unreachable clients-build/ directory only has to be reported once.
  const latestVersionRequest = fetch('/api/generic/latest-version')
    .then((r) => r.json())
    .catch(() => ({ version: null }));

  const latestVersionLabel = document.getElementById('latest-built-version');
  if (latestVersionLabel) {
    latestVersionRequest.then((data) => {
      latestVersionLabel.textContent = data.version ? data.version : 'none found';
    });
  }

  function pollHealth() {
    fetch('/api/health')
      .then((r) => r.json())
      .then((health) => {
        document.querySelectorAll('[data-health]').forEach((dot) => {
          const up = health[dot.dataset.health];
          dot.classList.toggle('status-up', up === true);
          dot.classList.toggle('status-down', up === false);
        });
      })
      .catch(() => {
        // Leave dots in whatever state they were - a failed health check on
        // our own dashboard shouldn't read as "the servers are down".
      });
  }
  pollHealth();
  setInterval(pollHealth, 30000);

  cards.forEach((card) => {
    const slot = card.dataset.slot;
    const btn = card.querySelector('.deploy-btn');
    if (!btn) return; // the build-generic card uses its own logic below, not this SSE flow

    const log = card.querySelector('.log');
    const logPanel = card.querySelector('.log-panel');
    const footer = card.querySelector('.footer');
    const versionInput = card.querySelector('.version-input');

    if (slot === 'generic') {
      latestVersionRequest.then((data) => {
        if (data.version) versionInput.value = data.version;
      });
    }

    btn.addEventListener('click', () => {
      let streamUrl = `/api/deploy/${slot}/stream`;
      if (slot === 'generic') {
        const version = versionInput.value.trim();
        if (!version) {
          footer.textContent = 'Enter a version first.';
          footer.className = 'footer failure';
          return;
        }
        streamUrl += `?version=${encodeURIComponent(version)}`;
      }

      btn.disabled = true;
      log.textContent = '';
      logPanel.open = true;
      footer.textContent = 'Running...';
      footer.className = 'footer';

      const source = new EventSource(streamUrl);
      ownedBySource.add(slot);

      source.addEventListener('log', (event) => {
        const { line } = JSON.parse(event.data);
        appendLine(log, line);
      });

      source.addEventListener('done', (event) => {
        const { code } = JSON.parse(event.data);
        if (code === 0) {
          footer.textContent = 'Done';
          footer.className = 'footer success';
        } else {
          footer.textContent = `Failed (exit code ${code})`;
          footer.className = 'footer failure';
        }
        source.close();
        ownedBySource.delete(slot);
        btn.disabled = false;
      });

      source.onerror = () => {
        footer.textContent = 'Connection error - deploy may still be running server-side; check the server console.';
        footer.className = 'footer failure';
        source.close();
        ownedBySource.delete(slot);
        btn.disabled = false;
      };
    });
  });

  // The build-generic card talks to the LOCAL admin-backend (proxied through
  // /api/build/generic*), not one of this dashboard's own scripts, so it needs
  // its own start+poll flow instead of the SSE pattern above: the backend
  // returns the FULL logs array on every poll, not one line per event, so
  // this only ever appends whatever's new since the last poll.
  (function initBuildGenericCard() {
    const card = document.querySelector('.card[data-slot="build-generic"]');
    if (!card) return;

    const btn = card.querySelector('.build-btn');
    const log = card.querySelector('.log');
    const logPanel = card.querySelector('.log-panel');
    const footer = card.querySelector('.footer');
    const versionInput = card.querySelector('.build-version-input');
    const platformSelect = card.querySelector('.build-platform-select');
    const tokenInput = card.querySelector('.build-token-input');

    // sessionStorage only: survives a reload of this tab but never touches
    // disk and disappears the moment the tab closes - no password is ever
    // held here, only the short-lived session token a real admin-portal
    // login already produced.
    try {
      const savedToken = sessionStorage.getItem('buildGenericToken');
      if (savedToken) tokenInput.value = savedToken;
    } catch (err) {
      // Private-browsing/blocked storage - just skip persistence.
    }
    tokenInput.addEventListener('input', () => {
      try {
        sessionStorage.setItem('buildGenericToken', tokenInput.value);
      } catch (err) {
        // Same as above - non-fatal, the token just won't survive a reload.
      }
    });

    function authHeaders() {
      const token = tokenInput.value.trim();
      return token ? { Authorization: `Bearer ${token}` } : {};
    }

    let seenLogCount = 0;
    let isPolling = false;

    function renderNewLines(logs) {
      const all = Array.isArray(logs) ? logs : [];
      for (let i = seenLogCount; i < all.length; i++) appendLine(log, all[i]);
      seenLogCount = all.length;
    }

    function finish(status) {
      isPolling = false;
      if (status === 'SUCCESS') {
        footer.textContent = 'Done';
        footer.className = 'footer success';
      } else {
        footer.textContent = 'Failed';
        footer.className = 'footer failure';
      }
      btn.disabled = false;
    }

    function poll() {
      fetch('/api/build/generic/status', { headers: authHeaders() })
        .then((r) => r.json())
        .then((data) => {
          renderNewLines(data.logs);
          if (data.status === 'BUILDING') {
            setTimeout(poll, 1500);
            return;
          }
          if (data.status === 'SUCCESS' || data.status === 'FAILED') {
            finish(data.status);
            return;
          }
          // IDLE (or an unrecognised status) - nothing left to wait on.
          isPolling = false;
          btn.disabled = false;
        })
        .catch(() => {
          footer.textContent = 'Lost contact with local admin-backend';
          footer.className = 'footer failure';
          isPolling = false;
          btn.disabled = false;
        });
    }

    function startPolling() {
      if (isPolling) return;
      isPolling = true;
      setTimeout(poll, 1200);
    }

    btn.addEventListener('click', () => {
      if (!tokenInput.value.trim()) {
        footer.textContent = 'Paste a session token first.';
        footer.className = 'footer failure';
        return;
      }
      const version = versionInput.value.trim();
      if (version && !/^\d+\.\d+\.\d+$/.test(version)) {
        footer.textContent = 'Version must look like 1.2.3';
        footer.className = 'footer failure';
        return;
      }
      const platform = platformSelect.value;

      btn.disabled = true;
      log.textContent = '';
      logPanel.open = true;
      seenLogCount = 0;
      footer.textContent = 'Starting...';
      footer.className = 'footer';

      fetch(`/api/build/generic?version=${encodeURIComponent(version)}&platform=${encodeURIComponent(platform)}`, {
        method: 'POST',
        headers: authHeaders(),
      })
        .then((r) => r.json().then((body) => ({ ok: r.ok, body })))
        .then(({ ok, body }) => {
          if (!ok) {
            footer.textContent = (body && body.error) || 'Could not start the build.';
            footer.className = 'footer failure';
            btn.disabled = false;
            return;
          }
          footer.textContent = 'Running...';
          startPolling();
        })
        .catch(() => {
          footer.textContent = 'Local admin-backend is not reachable on :8083.';
          footer.className = 'footer failure';
          btn.disabled = false;
        });
    });

    // On load, reflect a build already in progress (e.g. page reloaded mid-build).
    fetch('/api/build/generic/status', { headers: authHeaders() })
      .then((r) => r.json())
      .then((data) => {
        if (data.status !== 'BUILDING') return;
        btn.disabled = true;
        logPanel.open = true;
        footer.textContent = 'Running...';
        footer.className = 'footer';
        renderNewLines(data.logs);
        startPolling();
      })
      .catch(() => {
        // Local admin-backend simply isn't running right now - leave the card idle.
      });
  })();

  // On load, reflect anything already running server-side (e.g. the page was
  // reloaded mid-deploy). Re-attaching to the live log is out of scope - this
  // only keeps the button state honest.
  fetch('/api/status')
    .then((r) => r.json())
    .then((status) => {
      cards.forEach((card) => {
        const slot = card.dataset.slot;
        if (!status[slot]) return;
        const btn = card.querySelector('.deploy-btn');
        const footer = card.querySelector('.footer');
        const logPanel = card.querySelector('.log-panel');
        if (ownedBySource.has(slot)) return;
        btn.disabled = true;
        if (logPanel) logPanel.open = true;
        footer.textContent = 'Running...';
        footer.className = 'footer';
        pollUntilIdle(slot, btn, footer);
      });
    })
    .catch(() => {});
})();
