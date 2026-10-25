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
    const platformChecks = card.querySelectorAll('.build-platform-check');
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

    function authJsonHeaders() {
      return Object.assign({ 'Content-Type': 'application/json' }, authHeaders());
    }

    // ── Version auto-suggest ────────────────────────────────────────────────
    // Same bump-patch-by-one logic as the admin portal's Confirm Build modal
    // (clients.component.ts) - direct feedback: "auto increase the version
    // and allow to edit ... reflect that on the dashboard-deploy build button
    // also". Still freely editable; this only seeds the field.
    let knownVersions = [];
    let userEditedVersion = false;
    versionInput.addEventListener('input', () => { userEditedVersion = true; });

    function bumpPatch(current) {
      const match = (current || '').trim().match(/^(\d+)\.(\d+)\.(\d+)$/);
      if (match) return `${match[1]}.${match[2]}.${Number(match[3]) + 1}`;
      return current ? `${current}.1` : '1.0.0';
    }

    function parseVersion(v) {
      const m = (v || '').trim().match(/^(\d+)\.(\d+)\.(\d+)$/);
      return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
    }

    // Negative when a is older than b, positive when newer, 0 when equal;
    // null when either isn't a plain x.y.z string - left unblocked in that
    // case, same leniency bumpPatch() already has.
    function compareVersions(a, b) {
      const pa = parseVersion(a);
      const pb = parseVersion(b);
      if (!pa || !pb) return null;
      for (let i = 0; i < 3; i++) {
        if (pa[i] !== pb[i]) return pa[i] - pb[i];
      }
      return 0;
    }

    // Same restriction as the admin portal's Confirm Build modal - direct
    // feedback: "what is the idea of allow select old version? it should be
    // only new number more than the old one". Equal to the latest is still
    // allowed (building a second platform of a release already in progress);
    // only strictly older is blocked.
    function versionError(version) {
      const latest = knownVersions.length > 0 ? knownVersions[0].versionNumber : '';
      if (!version || !latest) return null;
      const cmp = compareVersions(version, latest);
      if (cmp !== null && cmp < 0) {
        return `${version} is older than the latest version (${latest}) - pick a newer number.`;
      }
      return null;
    }

    const latestVersionLabelEl = document.getElementById('build-latest-version');

    function suggestNextVersion() {
      const fromVersions = knownVersions.length > 0 ? knownVersions[0].versionNumber : '';
      const base = fromVersions || latestBuiltVersionCache || '';
      if (latestVersionLabelEl) latestVersionLabelEl.textContent = base || 'none found';
      if (!userEditedVersion && base) versionInput.value = bumpPatch(base);
    }

    function getSelectedPlatforms() {
      return Array.from(platformChecks).filter((c) => c.checked).map((c) => c.value);
    }

    let latestBuiltVersionCache = null;
    latestVersionRequest.then((data) => {
      latestBuiltVersionCache = data.version || null;
      suggestNextVersion();
    });

    function loadKnownVersions() {
      return fetch('/api/versions', { headers: authHeaders() })
        .then((r) => (r.ok ? r.json() : []))
        .then((versions) => {
          knownVersions = Array.isArray(versions) ? versions : [];
          suggestNextVersion();
        })
        .catch(() => {
          // No token yet, or local admin-backend not reachable - the
          // file-scan-based suggestion above still applies.
        });
    }
    loadKnownVersions();
    tokenInput.addEventListener('input', () => { loadKnownVersions(); });

    // ── Changelog picker ─────────────────────────────────────────────────────
    let changelogLibrary = [];
    let changelogSelectedIds = new Set();
    let changelogEditingId = null;
    let currentChangelogText = '';

    const changelogPreview = card.querySelector('.changelog-preview');
    const changelogPickerBtn = card.querySelector('.changelog-picker-btn');
    const modalBackdrop = document.getElementById('changelog-modal-backdrop');
    const modalError = document.getElementById('changelog-modal-error');
    const modalList = document.getElementById('changelog-list');
    const modalNewInputEn = document.getElementById('changelog-new-input-en');
    const modalNewInputAr = document.getElementById('changelog-new-input-ar');
    const modalAddBtn = document.getElementById('changelog-add-btn');
    const modalSubmitBtn = document.getElementById('changelog-submit-btn');
    const modalCancelBtn = document.getElementById('changelog-cancel-btn');
    const modalCloseBtn = document.getElementById('changelog-modal-close');
    const modalSaving = document.getElementById('changelog-saving');
    const modalNewOnly = document.getElementById('changelog-new-only');

    // Entries already shipped in a released version are hidden by default, so
    // the list opens on exactly what is new since the last release. `deployed`
    // is set server-side when a version is saved with the entry selected; an
    // entry whose flag is null predates the flag and counts as released.
    function isUnreleased(entry) {
      return entry.deployed !== true;
    }

    function visibleChangelogEntries() {
      if (modalNewOnly && !modalNewOnly.checked) return changelogLibrary;
      // A selected entry stays visible even once filtered out, so submitting
      // can never silently drop something the admin had already ticked.
      return changelogLibrary.filter((e) => isUnreleased(e) || changelogSelectedIds.has(e.id));
    }

    if (modalNewOnly) {
      modalNewOnly.addEventListener('change', () => renderChangelogList());
    }

    function showModalError(message) {
      modalError.textContent = message;
      modalError.hidden = false;
    }

    function renderChangelogList() {
      modalList.innerHTML = '';
      if (changelogLibrary.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'changelog-empty';
        empty.textContent = 'No entries yet - add the first one below.';
        modalList.appendChild(empty);
        return;
      }
      const visible = visibleChangelogEntries();
      if (visible.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'changelog-empty';
        empty.textContent = 'Everything in the library has already been released - untick the filter above to see it.';
        modalList.appendChild(empty);
        return;
      }
      visible.forEach((entry) => {
        const row = document.createElement('div');
        row.className = 'changelog-item';
        if (changelogEditingId === entry.id) {
          row.style.flexDirection = 'column';
          row.style.alignItems = 'stretch';

          const enField = document.createElement('div');
          enField.className = 'changelog-edit-field';
          const enLabel = document.createElement('label');
          enLabel.textContent = 'English';
          const enTextarea = document.createElement('textarea');
          enTextarea.className = 'changelog-edit-textarea';
          enTextarea.rows = 2;
          enTextarea.value = entry.en;
          enField.append(enLabel, enTextarea);

          const arField = document.createElement('div');
          arField.className = 'changelog-edit-field';
          const arLabel = document.createElement('label');
          arLabel.textContent = 'العربية';
          const arTextarea = document.createElement('textarea');
          arTextarea.className = 'changelog-edit-textarea';
          arTextarea.dir = 'rtl';
          arTextarea.rows = 2;
          arTextarea.value = entry.ar;
          arField.append(arLabel, arTextarea);

          const saveBtn = document.createElement('button');
          saveBtn.textContent = 'Save';
          saveBtn.addEventListener('click', () => {
            const en = enTextarea.value.trim();
            const ar = arTextarea.value.trim();
            if (!en || !ar) return;
            entry.en = en;
            entry.ar = ar;
            changelogEditingId = null;
            persistLibrary();
          });
          const cancelBtn = document.createElement('button');
          cancelBtn.textContent = 'Cancel';
          cancelBtn.addEventListener('click', () => {
            changelogEditingId = null;
            renderChangelogList();
          });
          const actions = document.createElement('div');
          actions.className = 'changelog-item-actions';
          actions.style.justifyContent = 'flex-end';
          actions.append(saveBtn, cancelBtn);
          row.append(enField, arField, actions);
        } else {
          const checkbox = document.createElement('input');
          checkbox.type = 'checkbox';
          checkbox.checked = changelogSelectedIds.has(entry.id);
          checkbox.addEventListener('change', () => {
            if (checkbox.checked) changelogSelectedIds.add(entry.id);
            else changelogSelectedIds.delete(entry.id);
          });
          const textWrap = document.createElement('div');
          textWrap.className = 'changelog-item-text';
          const enLine = document.createElement('div');
          if (!isUnreleased(entry)) {
            // Only marked on the already-released ones: with the filter on by
            // default, almost every visible row is new, so badging those would
            // be noise. The badge earns its place by explaining why a row is
            // showing when the filter is off.
            const badge = document.createElement('span');
            badge.className = 'changelog-badge';
            badge.textContent = 'released';
            enLine.appendChild(badge);
          }
          enLine.appendChild(document.createTextNode(entry.en));
          const arLine = document.createElement('div');
          arLine.className = 'changelog-item-ar';
          arLine.dir = 'rtl';
          arLine.textContent = entry.ar;
          textWrap.append(enLine, arLine);
          textWrap.addEventListener('click', () => {
            checkbox.checked = !checkbox.checked;
            checkbox.dispatchEvent(new Event('change'));
          });
          const editBtn = document.createElement('button');
          editBtn.textContent = 'Edit';
          editBtn.addEventListener('click', () => {
            changelogEditingId = entry.id;
            renderChangelogList();
          });
          const deleteBtn = document.createElement('button');
          deleteBtn.className = 'changelog-delete-btn';
          deleteBtn.textContent = 'Remove';
          deleteBtn.addEventListener('click', () => {
            if (!confirm('Remove this changelog entry from the library?')) return;
            changelogLibrary = changelogLibrary.filter((e) => e.id !== entry.id);
            changelogSelectedIds.delete(entry.id);
            persistLibrary();
          });
          const actions = document.createElement('div');
          actions.className = 'changelog-item-actions';
          actions.append(editBtn, deleteBtn);
          row.append(checkbox, textWrap, actions);
        }
        modalList.appendChild(row);
      });
    }

    function persistLibrary() {
      modalSaving.hidden = false;
      fetch('/api/changelog-library', {
        method: 'PUT',
        headers: authJsonHeaders(),
        body: JSON.stringify(changelogLibrary),
      })
        .then((r) => {
          if (r.ok) return;
          if (r.status === 401) throw new Error('Session token is missing, wrong, or expired - paste a fresh one from the admin portal.');
          if (r.status === 503) throw new Error('Local admin-backend is not reachable on :8083 - start it first.');
          throw new Error(`Could not save the changelog library (HTTP ${r.status}).`);
        })
        .catch((err) => showModalError((err && err.message) || 'Could not save the changelog library.'))
        .finally(() => {
          modalSaving.hidden = true;
          renderChangelogList();
        });
    }

    function openChangelogModal() {
      if (!tokenInput.value.trim()) {
        footer.textContent = 'Paste a session token first.';
        footer.className = 'footer failure';
        return;
      }
      modalError.hidden = true;
      changelogSelectedIds = new Set();
      changelogEditingId = null;
      modalNewInputEn.value = '';
      modalNewInputAr.value = '';
      modalBackdrop.hidden = false;
      fetch('/api/changelog-library', { headers: authHeaders() })
        .then((r) => {
          if (r.ok) return r.json();
          // 401 = bad/expired token (AuthFilter on :8083); 503 = the dashboard's
          // own proxy couldn't reach the local admin-backend at all; anything
          // else is unexpected. Surfacing which one it was - direct report:
          // the previous version always showed the same generic message
          // regardless of cause, which made this impossible to diagnose.
          if (r.status === 401) return Promise.reject(new Error('Session token is missing, wrong, or expired - paste a fresh one from the admin portal.'));
          if (r.status === 503) return Promise.reject(new Error('Local admin-backend is not reachable on :8083 - start it first.'));
          return Promise.reject(new Error(`Could not load the changelog library (HTTP ${r.status}).`));
        })
        .then((entries) => {
          changelogLibrary = Array.isArray(entries) ? entries : [];
          renderChangelogList();
        })
        .catch((err) => showModalError((err && err.message) || 'Could not load the changelog library.'));
    }

    function closeChangelogModal() {
      modalBackdrop.hidden = true;
    }

    changelogPickerBtn.addEventListener('click', openChangelogModal);
    modalCancelBtn.addEventListener('click', closeChangelogModal);
    modalCloseBtn.addEventListener('click', closeChangelogModal);
    modalBackdrop.addEventListener('click', (e) => { if (e.target === modalBackdrop) closeChangelogModal(); });

    modalAddBtn.addEventListener('click', () => {
      const en = modalNewInputEn.value.trim();
      const ar = modalNewInputAr.value.trim();
      if (!en || !ar) return;
      const id = 'entry-' + Date.now().toString(36);
      changelogLibrary.push({ id, en, ar });
      changelogSelectedIds.add(id);
      modalNewInputEn.value = '';
      modalNewInputAr.value = '';
      persistLibrary();
    });
    modalNewInputEn.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') modalAddBtn.click();
    });
    modalNewInputAr.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') modalAddBtn.click();
    });

    // [EN]/[AR] markers rather than JSON - stays human-readable wherever
    // AppVersion.changelogText ends up shown as-is today (this card's own
    // preview, changelog.txt), while still trivial for later code (the
    // eventual update-badge wiring) to split on and pick a language from.
    // Same format as the admin portal's ChangelogPickerComponent.submit().
    modalSubmitBtn.addEventListener('click', () => {
      const chosen = changelogLibrary.filter((e) => changelogSelectedIds.has(e.id));
      const enLines = chosen.map((e) => '- ' + e.en).join('\n');
      const arLines = chosen.map((e) => '- ' + e.ar).join('\n');
      currentChangelogText = chosen.length > 0 ? `[EN]\n${enLines}\n\n[AR]\n${arLines}` : '';
      changelogPreview.textContent = currentChangelogText || 'No changelog selected for this build yet.';
      changelogPreview.classList.toggle('has-content', !!currentChangelogText);
      closeChangelogModal();
    });

    /**
     * Registers `version` as an AppVersion record before a build starts -
     * every build, not just ones where a changelog was picked. Direct
     * report: "when i build a version from the dashboard deploy it should
     * save that version to the db also" - the previous version only ever
     * called this when currentChangelogText was non-empty, so a plain build
     * (no changelog touched) never showed up on the Versions page or fed the
     * next build's auto-suggested version at all.
     *
     * - New version: always created (changelogText is '' if none was picked).
     * - Existing version: only updated when a changelog WAS picked this time,
     *   so re-building an already-registered version without touching the
     *   picker never wipes out a changelog set earlier.
     */
    function saveChangelogForVersion(version) {
      const existing = knownVersions.find((v) => v.versionNumber === version);
      if (existing && !currentChangelogText.trim()) return Promise.resolve();
      const payload = {
        versionNumber: version,
        releaseDate: existing ? existing.releaseDate : new Date().toISOString().slice(0, 10),
        changelogText: currentChangelogText,
        // Saving the version is what marks these as released, so they stop
        // showing up as new next time the picker is opened. Ids rather than
        // the composed text: the text is editable by hand.
        changelogEntryIds: Array.from(changelogSelectedIds),
      };
      const req = existing
        ? fetch(`/api/versions/${existing.id}`, { method: 'PUT', headers: authJsonHeaders(), body: JSON.stringify(payload) })
        : fetch('/api/versions', { method: 'POST', headers: authJsonHeaders(), body: JSON.stringify(payload) });
      // Best-effort, same as the admin portal's own confirmBuild() - a failure
      // here (e.g. duplicate versionNumber from a race) shouldn't block the build.
      return req.catch(() => {}).then(() => loadKnownVersions());
    }

    let seenLogCount = 0;
    let isPolling = false;

    function renderNewLines(logs) {
      const all = Array.isArray(logs) ? logs : [];
      for (let i = seenLogCount; i < all.length; i++) appendLine(log, all[i]);
      seenLogCount = all.length;
    }

    // Platforms remaining after the one currently building - BuildService only
    // allows one generic build at a time regardless of platform (single
    // GENERIC_KEY status slot), so "build both" runs them one after another
    // instead of concurrently. Direct request: "let me choose 64 or 32 or
    // both instead of one each time".
    let buildQueue = [];
    let queuedVersion = '';

    function finish(status) {
      isPolling = false;
      if (status === 'SUCCESS') {
        if (buildQueue.length > 0) {
          startNextQueuedBuild();
          return;
        }
        footer.textContent = 'Done';
        footer.className = 'footer success';
      } else {
        buildQueue = [];
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

    function startNextQueuedBuild() {
      const platform = buildQueue.shift();
      seenLogCount = 0;
      appendLine(log, `\n== Building ${platform} ==`);
      footer.textContent = `Building ${platform}...`;
      footer.className = 'footer';

      fetch(`/api/build/generic?version=${encodeURIComponent(queuedVersion)}&platform=${encodeURIComponent(platform)}`, {
        method: 'POST',
        headers: authHeaders(),
      })
        .then((r) => r.json().then((body) => ({ ok: r.ok, body })))
        .then(({ ok, body }) => {
          if (!ok) {
            footer.textContent = (body && body.error) || 'Could not start the build.';
            footer.className = 'footer failure';
            buildQueue = [];
            btn.disabled = false;
            return;
          }
          footer.textContent = `Building ${platform}...`;
          startPolling();
        })
        .catch(() => {
          footer.textContent = 'Local admin-backend is not reachable on :8083.';
          footer.className = 'footer failure';
          buildQueue = [];
          btn.disabled = false;
        });
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
      const versionErr = versionError(version);
      if (versionErr) {
        footer.textContent = versionErr;
        footer.className = 'footer failure';
        return;
      }
      const platforms = getSelectedPlatforms();
      if (platforms.length === 0) {
        footer.textContent = 'Choose at least one platform.';
        footer.className = 'footer failure';
        return;
      }

      btn.disabled = true;
      log.textContent = '';
      logPanel.open = true;
      seenLogCount = 0;
      footer.textContent = 'Starting...';
      footer.className = 'footer';

      saveChangelogForVersion(version).then(() => {
        queuedVersion = version;
        buildQueue = platforms.slice();
        startNextQueuedBuild();
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

  // The Create online client card talks to the REMOTE admin portal (proxied through
  // /api/remote/*) because the real client records and licences live in the server's database,
  // not the local one. It needs its own Bearer token: the local session token is not valid there.
  // The token is relayed exactly as pasted - this page never holds or asks for a password.
  // Provisioning is always a POST; nothing on this card is triggered by loading or navigating.
  (function initCreateStoreCard() {
    const card = document.querySelector('.card[data-slot="create-store"]');
    if (!card) return;

    const tokenInput = card.querySelector('.create-token-input');
    const clientSelect = card.querySelector('.create-client-select');
    const expiryInput = card.querySelector('.create-expiry-input');
    const subdomainInput = card.querySelector('.create-subdomain-input');
    const subdomainHint = card.querySelector('.create-subdomain-hint');
    const btn = card.querySelector('.create-btn');
    const log = card.querySelector('.log');
    const logPanel = card.querySelector('.log-panel');
    const footer = card.querySelector('.footer');
    const SUGGESTED_HINT = '(suggested from the client; editable)';

    // sessionStorage only, same as the build card: gone when the tab closes.
    try {
      const saved = sessionStorage.getItem('remotePortalToken');
      if (saved) tokenInput.value = saved;
    } catch (err) {
      // Blocked storage - the token just won't survive a reload.
    }

    function authHeaders() {
      const token = tokenInput.value.trim();
      return token ? { Authorization: `Bearer ${token}` } : {};
    }

    function setStatus(text, kind) {
      footer.textContent = text;
      footer.className = kind ? `footer ${kind}` : 'footer';
    }

    function readError(r, body) {
      if (r.status === 401 || r.status === 403) {
        return 'Remote token is missing, wrong, or expired - paste a fresh one from admin.damansoft.com.';
      }
      return (body && body.error) || `Request failed (HTTP ${r.status})`;
    }

    function parseBody(text) {
      try { return JSON.parse(text); } catch (err) { return null; }
    }

    function loadClients() {
      if (!tokenInput.value.trim()) return;
      fetch('/api/remote/clients', { headers: authHeaders() })
        .then((r) => r.text().then((text) => ({ r, body: parseBody(text) })))
        .then(({ r, body }) => {
          if (!r.ok || !Array.isArray(body)) {
            setStatus(readError(r, body), 'failure');
            return;
          }
          const previous = clientSelect.value;
          clientSelect.textContent = '';
          const placeholder = document.createElement('option');
          placeholder.value = '';
          placeholder.textContent = 'Select a client...';
          clientSelect.append(placeholder);
          body
            .map((c) => ({ code: c.clientCode, name: c.appName }))
            .filter((c) => c.code)
            .sort((a, b) => a.code.localeCompare(b.code))
            .forEach((c) => {
              const opt = document.createElement('option');
              opt.value = c.code;
              opt.textContent = c.name && c.name !== c.code ? `${c.code} - ${c.name}` : c.code;
              clientSelect.append(opt);
            });
          if (previous) clientSelect.value = previous;
          setStatus('Clients loaded', 'success');
        })
        .catch(() => setStatus('Could not reach the dashboard server', 'failure'));
    }

    let loadTimer = null;
    tokenInput.addEventListener('input', () => {
      try {
        sessionStorage.setItem('remotePortalToken', tokenInput.value);
      } catch (err) {
        // Non-fatal.
      }
      clearTimeout(loadTimer);
      loadTimer = setTimeout(loadClients, 400);
    });
    loadClients();

    // Only the response for the CURRENTLY selected client may fill the field, so a slow answer
    // for a previous pick can never overwrite a newer one. Changing the client resets the field.
    let suggestSeq = 0;
    clientSelect.addEventListener('change', () => {
      const code = clientSelect.value;
      const seq = ++suggestSeq;
      subdomainInput.value = '';
      subdomainHint.textContent = SUGGESTED_HINT;
      if (!code) return;
      fetch(`/api/remote/hosted-stores/suggest-subdomain?clientCode=${encodeURIComponent(code)}`, {
        headers: authHeaders(),
      })
        .then((r) => r.text().then((text) => ({ r, body: parseBody(text) })))
        .then(({ r, body }) => {
          if (seq !== suggestSeq) return;
          if (!r.ok || !body) {
            setStatus(readError(r, body), 'failure');
            return;
          }
          // An empty suggestion is legitimate (e.g. an Arabic-only client code): leave the
          // field empty and say so rather than inventing a hostname.
          subdomainInput.value = body.subdomain || '';
          if (!body.subdomain) {
            subdomainHint.textContent = '(no automatic suggestion for this client - type one: lowercase letters, digits, hyphens)';
          }
        })
        .catch(() => {});
    });

    function describe(status, row) {
      if (status === 201 && row && row.status === 'ACTIVE') {
        return { text: `Created${row.frontendHostname ? ': https://' + row.frontendHostname : ''}`, kind: 'success' };
      }
      if (status === 201 && row && row.status === 'FAILED') {
        return {
          text: `Failed at stage ${row.failedStage || 'unknown'} - click Create again to resume`,
          kind: 'failure',
        };
      }
      if (status === 409) {
        return { text: `Already ACTIVE${row && row.frontendHostname ? ': https://' + row.frontendHostname : ''}`, kind: 'failure' };
      }
      return null;
    }

    btn.addEventListener('click', () => {
      const clientCode = clientSelect.value;
      const subdomain = subdomainInput.value.trim();
      if (!tokenInput.value.trim()) { setStatus('Paste the remote portal token first.', 'failure'); return; }
      if (!clientCode) { setStatus('Select a client first.', 'failure'); return; }
      if (!subdomain) { setStatus('Enter a subdomain first.', 'failure'); return; }

      btn.disabled = true;
      log.textContent = '';
      logPanel.open = true;
      setStatus('Running...');
      appendLine(log, `Creating hosted store for ${clientCode} at ${subdomain}... (this can take a minute)`);

      fetch('/api/remote/hosted-stores', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ clientCode, subdomain, expiresAt: expiryInput.value }),
      })
        .then((r) => r.text().then((text) => ({ r, body: parseBody(text) })))
        .then(({ r, body }) => {
          const outcome = describe(r.status, body);
          if (outcome) {
            appendLine(log, JSON.stringify(body, null, 2));
            setStatus(outcome.text, outcome.kind);
          } else {
            const message = readError(r, body);
            appendLine(log, message);
            setStatus(message, 'failure');
          }
        })
        .catch((err) => {
          appendLine(log, `Request failed: ${err.message}`);
          setStatus('Could not reach the dashboard server', 'failure');
        })
        .finally(() => { btn.disabled = false; });
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
