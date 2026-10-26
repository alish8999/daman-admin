(function () {
  const cards = document.querySelectorAll('.card');

  // ── Token strip ──────────────────────────────────────────────────────────
  // Two tokens, minted by two different backends, each valid only on its own: 'portal' (a login at
  // admin.damansoft.com) and 'local' (the local admin backend on :8083). The strip is the only
  // place either is entered; every card reads it from here. State comes from lib/token-state-core.js
  // (served as /token-state-core.js): a 401 flips a token to expired, a 2xx back to active, and
  // nothing else - a 5xx/503/network failure says nothing about the token.
  const tokenCore = window.tokenStateCore;
  const tokens = (function () {
    const kinds = {
      portal: { storageKey: 'remotePortalToken' },
      local: { storageKey: 'buildGenericToken' },
    };
    Object.keys(kinds).forEach((kind) => {
      const k = kinds[kind];
      k.field = document.querySelector('.token-field[data-token-kind="' + kind + '"]');
      k.input = k.field.querySelector('.token-input');
      k.stateText = k.field.querySelector('[data-token-state-text]');
      k.message = k.field.querySelector('[data-token-message]');
      k.state = 'missing';
      k.listeners = [];
      // sessionStorage only: survives a reload of this tab, never touches disk, gone when it closes.
      try {
        const saved = sessionStorage.getItem(k.storageKey);
        if (saved) k.input.value = saved;
      } catch (err) {
        // Blocked storage - the token just won't survive a reload.
      }
    });

    function render(kind) {
      const k = kinds[kind];
      k.field.dataset.state = k.state;
      k.stateText.textContent = k.state;
      k.message.textContent = tokenCore.tokenMessage(kind, k.state);
    }

    function apply(kind, current, lastStatus) {
      const k = kinds[kind];
      k.state = tokenCore.nextTokenState(current, { hasValue: k.input.value.trim() !== '', lastStatus });
      render(kind);
    }

    Object.keys(kinds).forEach((kind) => {
      const k = kinds[kind];
      apply(kind, 'active', null);
      k.input.addEventListener('input', () => {
        try {
          sessionStorage.setItem(k.storageKey, k.input.value);
        } catch (err) {
          // Non-fatal.
        }
        // A newly pasted value has not been rejected by anything yet, so it starts from 'active'
        // rather than inheriting the old value's expiry.
        apply(kind, 'active', null);
        k.listeners.forEach((fn) => fn());
      });
    });

    const api = {
      input(kind) { return kinds[kind].input; },
      value(kind) { return kinds[kind].input.value.trim(); },
      isExpired(kind) { return kinds[kind].state === 'expired'; },
      // Runs after the strip has updated its own state for a newly pasted/edited value.
      onInput(kind, fn) { kinds[kind].listeners.push(fn); },
      observe(kind, status) { apply(kind, kinds[kind].state, status); },
      // Wrap a fetch made with this token: its HTTP status (or the lack of one) is the evidence.
      track(kind, request) {
        return request.then(
          (r) => { api.observe(kind, r.status); return r; },
          (err) => { api.observe(kind, null); throw err; }
        );
      },
    };
    return api;
  })();

  // Slots whose script needs the portal token. EventSource cannot send an Authorization header and
  // a token has no business in a URL, so each posts its OWN copy just before its OWN stream opens.
  const PORTAL_TOKEN_SLOTS = new Set(['store-backend', 'store-deploy-only', 'store-restart-all']);

  const newStoreBtn = document.getElementById('new-store-btn');
  const newStorePanel = document.getElementById('new-store-panel');
  if (newStoreBtn && newStorePanel) {
    newStoreBtn.addEventListener('click', () => {
      const opening = newStorePanel.hidden;
      newStorePanel.hidden = !opening;
      newStoreBtn.setAttribute('aria-expanded', String(opening));
    });
  }

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

      // EventSource cannot send an Authorization header and a token has no business in a URL,
      // so a slot that needs one hands it over in its own POST first and only then opens the
      // stream. Everything after this point is identical for every slot.
      let ready = Promise.resolve();
      if (PORTAL_TOKEN_SLOTS.has(slot)) {
        const token = tokens.value('portal');
        if (!token) {
          footer.textContent = 'Paste the portal token in the token strip first.';
          footer.className = 'footer failure';
          return;
        }
        ready = fetch(`/api/deploy/${slot}/token`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ token }),
        }).then((r) => {
          if (!r.ok) return r.json().then((b) => Promise.reject(new Error(b.error || `HTTP ${r.status}`)));
        });
      }

      btn.disabled = true;
      log.textContent = '';
      logPanel.open = true;
      footer.textContent = 'Running...';
      footer.className = 'footer';

      ready.then(startStream).catch((err) => {
        footer.textContent = err.message;
        footer.className = 'footer failure';
        btn.disabled = false;
      });

      function startStream() {
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
      }
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
    const tokenInput = tokens.input('local');

    function authHeaders() {
      const token = tokens.value('local');
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
      return tokens.track('local', fetch('/api/versions', { headers: authHeaders() }))
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
    tokens.onInput('local', () => { loadKnownVersions(); });

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
      tokens.track('local', fetch('/api/changelog-library', {
        method: 'PUT',
        headers: authJsonHeaders(),
        body: JSON.stringify(changelogLibrary),
      }))
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
      tokens.track('local', fetch('/api/changelog-library', { headers: authHeaders() }))
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
        ? tokens.track('local', fetch(`/api/versions/${existing.id}`, { method: 'PUT', headers: authJsonHeaders(), body: JSON.stringify(payload) }))
        : tokens.track('local', fetch('/api/versions', { method: 'POST', headers: authJsonHeaders(), body: JSON.stringify(payload) }));
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

    class TokenExpired extends Error {}

    function stopPollingExpired() {
      isPolling = false;
      btn.disabled = false;
      footer.textContent = 'Local token expired - stopped watching the build (it may still be running). Paste a fresh token to resume.';
      footer.className = 'footer failure';
    }

    function poll() {
      // Once the token reads expired the poll stops until a new value is pasted: every further
      // tick would just be another 401. A build may still be running server-side; the message says so.
      if (tokens.isExpired('local')) {
        stopPollingExpired();
        return;
      }
      tokens.track('local', fetch('/api/build/generic/status', { headers: authHeaders() }))
        .then((r) => (r.status === 401 ? Promise.reject(new TokenExpired()) : r.json()))
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
        .catch((err) => {
          if (err instanceof TokenExpired) { stopPollingExpired(); return; }
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

      tokens.track('local', fetch(`/api/build/generic?version=${encodeURIComponent(queuedVersion)}&platform=${encodeURIComponent(platform)}`, {
        method: 'POST',
        headers: authHeaders(),
      }))
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
    // Nothing to reflect (and nothing worth a 401) without a token.
    if (tokens.value('local')) tokens.track('local', fetch('/api/build/generic/status', { headers: authHeaders() }))
      .then((r) => (r.ok ? r.json() : { status: 'IDLE' }))
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

    const clientSelect = card.querySelector('.create-client-select');
    const expiryInput = card.querySelector('.create-expiry-input');
    const subdomainInput = card.querySelector('.create-subdomain-input');
    const subdomainHint = card.querySelector('.create-subdomain-hint');
    const btn = card.querySelector('.create-btn');
    const log = card.querySelector('.log');
    const logPanel = card.querySelector('.log-panel');
    const footer = card.querySelector('.footer');
    const SUGGESTED_HINT = '(suggested from the client; editable)';

    function authHeaders() {
      const token = tokens.value('portal');
      return token ? { Authorization: `Bearer ${token}` } : {};
    }

    function setStatus(text, kind) {
      footer.textContent = text;
      footer.className = kind ? `footer ${kind}` : 'footer';
    }

    function readError(r, body) {
      if (r.status === 401) return tokenCore.tokenMessage('portal', 'expired');
      if (r.status === 403) return 'Forbidden (403): this portal token is not allowed to do that.';
      return (body && body.error) || `Request failed (HTTP ${r.status})`;
    }

    function parseBody(text) {
      try { return JSON.parse(text); } catch (err) { return null; }
    }

    function loadClients() {
      if (!tokens.value('portal')) return;
      tokens.track('portal', fetch('/api/remote/clients', { headers: authHeaders() }))
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

    // "Retry provisioning" on a FAILED row of the fleet table lands here: open the panel with that
    // row's client and subdomain filled in. Pressing Create then resumes the stage walk.
    document.addEventListener('prefill-create-store', (e) => {
      const { clientCode, subdomain } = e.detail || {};
      if (!clientCode) return;
      suggestSeq++; // a pending suggestion for an earlier pick must not overwrite this
      if (newStorePanel && newStorePanel.hidden && newStoreBtn) newStoreBtn.click();
      // The client list may not have loaded (or the client may be absent from it): make sure the
      // option exists so the select really holds this code.
      if (!Array.from(clientSelect.options).some((o) => o.value === clientCode)) {
        const opt = document.createElement('option');
        opt.value = clientCode;
        opt.textContent = clientCode;
        clientSelect.append(opt);
      }
      clientSelect.value = clientCode;
      subdomainInput.value = subdomain || '';
      subdomainHint.textContent = '(from the failed row; Create resumes where it stopped)';
      setStatus('Pre-filled from the failed row. Press Create to retry provisioning; it resumes at the stage that failed.');
      card.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });

    let loadTimer = null;
    tokens.onInput('portal', () => {
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
      tokens.track('portal', fetch(`/api/remote/hosted-stores/suggest-subdomain?clientCode=${encodeURIComponent(code)}`, {
        headers: authHeaders(),
      }))
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

    // POST /api/hosted-stores answers 202 as soon as the registry row exists and runs the twelve
    // stages afterwards, server-side: the whole sequence takes 125-150 s and the remote admin API
    // is reached through a Cloudflare tunnel whose edge answers 524 after 100 s of origin silence.
    // So the terminal state is read from the registry instead of from that response.
    const POLL_INTERVAL_MS = 5000;
    // Generous ceiling for a ~2-minute job. Giving up here only stops THIS PAGE polling - the
    // server is unaffected and the row keeps the truth, which is what the message says.
    const POLL_CEILING_MS = 6 * 60 * 1000;

    // Describes a registry row. Never returns null for a row that exists: the previous version
    // answered null for any status that was neither ACTIVE nor FAILED, so the caller fell through
    // to its error branch and rendered a success as "Request failed (HTTP 201)". With a 202 and a
    // PROVISIONING row that branch is no longer unreachable.
    function describeRow(row) {
      if (!row || !row.status) return null;
      if (row.status === 'ACTIVE') {
        return {
          text: `Created${row.frontendHostname ? ': https://' + row.frontendHostname : ''}`,
          kind: 'success',
          done: true,
        };
      }
      if (row.status === 'FAILED') {
        return {
          text: `Failed at stage ${row.failedStage || 'unknown'} - click Create again to resume`,
          kind: 'failure',
          done: true,
        };
      }
      return { text: `Provisioning... (stage ${row.failedStage || 'ALLOCATE'})`, kind: null, done: false };
    }

    function describe(status, body) {
      // 202 is the current answer; 201 is accepted too so an older server still reports correctly.
      if (status === 202 || status === 201) {
        return describeRow(body) || { text: 'Accepted; waiting for the registry row...', kind: null, done: false };
      }
      if (status === 409) {
        // Two different 409s: the client is already ACTIVE (carries the row), or a provision for it
        // is already running (carries an {error}).
        if (body && body.status) {
          return {
            text: `Already ${body.status}${body.frontendHostname ? ': https://' + body.frontendHostname : ''}`,
            kind: 'failure',
            done: true,
          };
        }
        return {
          text: (body && body.error) || 'A provision for this client is already running',
          kind: 'failure',
          done: true,
        };
      }
      return null;
    }

    // Watches the registry until this client's row is ACTIVE or FAILED. A read that fails is NOT
    // reported as a failed provision - the server is still working - so it keeps polling.
    function pollUntilTerminal(clientCode) {
      const startedAt = Date.now();

      const schedule = () => {
        if (Date.now() - startedAt > POLL_CEILING_MS) {
          setStatus('Still PROVISIONING after 6 minutes - read the row before retrying', 'failure');
          appendLine(log, 'Stopped polling after 6 minutes. The server has NOT stopped: read the row'
            + ' with GET /api/hosted-stores and follow DEPLOYMENT.md 10.5.');
          btn.disabled = false;
          return;
        }
        setTimeout(tick, POLL_INTERVAL_MS);
      };

      const tick = () => {
        // An expired token stops the poll (every tick would be another 401); the server's
        // provisioning carries on regardless and the row keeps the truth.
        if (tokens.isExpired('portal')) {
          setStatus('Portal token expired - stopped watching the registry. The server is still provisioning; paste a fresh token to resume.', 'failure');
          appendLine(log, 'Stopped polling: portal token expired. Paste a fresh one, then read GET /api/hosted-stores.');
          btn.disabled = false;
          return;
        }
        tokens.track('portal', fetch('/api/remote/hosted-stores', { headers: authHeaders() }))
          .then((r) => r.text().then((text) => ({ r, body: parseBody(text) })))
          .then(({ r, body }) => {
            // Both backend shapes (bare array / { stores }): see registry-rows.js.
            const rows = registryRows(body);
            if (!r.ok || !rows) {
              appendLine(log, `Could not read the registry (${readError(r, body)}); still polling...`);
              schedule();
              return;
            }
            const row = rows.find((candidate) => candidate.clientCode === clientCode);
            if (!row) {
              appendLine(log, 'The registry has no row for this client yet; still polling...');
              schedule();
              return;
            }
            const outcome = describeRow(row);
            setStatus(outcome.text, outcome.kind);
            if (!outcome.done) {
              schedule();
              return;
            }
            appendLine(log, JSON.stringify(row, null, 2));
            btn.disabled = false;
          })
          .catch(() => {
            appendLine(log, 'Could not reach the dashboard server; still polling...');
            schedule();
          });
      };

      tick();
    }

    btn.addEventListener('click', () => {
      const clientCode = clientSelect.value;
      const subdomain = subdomainInput.value.trim();
      if (!tokens.value('portal')) { setStatus('Paste the portal token in the token strip first.', 'failure'); return; }
      if (!clientCode) { setStatus('Select a client first.', 'failure'); return; }
      if (!subdomain) { setStatus('Enter a subdomain first.', 'failure'); return; }

      btn.disabled = true;
      log.textContent = '';
      logPanel.open = true;
      setStatus('Running...');
      appendLine(log, `Creating hosted store for ${clientCode} at ${subdomain}...`);

      tokens.track('portal', fetch('/api/remote/hosted-stores', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ clientCode, subdomain, expiresAt: expiryInput.value }),
      }))
        .then((r) => r.text().then((text) => ({ r, body: parseBody(text) })))
        .then(({ r, body }) => {
          const outcome = describe(r.status, body);
          if (!outcome) {
            const message = readError(r, body);
            appendLine(log, message);
            setStatus(message, 'failure');
            btn.disabled = false;
            return;
          }
          appendLine(log, JSON.stringify(body, null, 2));
          setStatus(outcome.text, outcome.kind);
          if (outcome.done) {
            btn.disabled = false;
            return;
          }
          // Accepted: the stages are running server-side. The button stays disabled until the row
          // reaches a terminal state, so a second click cannot start a duplicate run (the server
          // refuses one with 409 anyway).
          appendLine(log, 'Accepted. The server is provisioning this store - about two minutes, most'
            + ' of it the Pages custom domain going active. Watching the registry...');
          pollUntilTerminal(clientCode);
        })
        .catch((err) => {
          appendLine(log, `Request failed: ${err.message}`);
          setStatus('Could not reach the dashboard server', 'failure');
          btn.disabled = false;
        });
    });
  })();

  // ── Hosted-store fleet table ─────────────────────────────────────────────
  // One row per registry row, polled every FLEET_POLL_MS while the tab is visible. All decisions
  // (which buttons, what text) live in lib/store-row-core.js; this block is only DOM + fetch.
  // Rows are built with textContent, never innerHTML: hostnames and failed-stage text come from
  // the server.
  (function initFleetTable() {
    const mount = document.getElementById('fleet-mount');
    const meta = document.getElementById('fleet-meta');
    if (!mount || !meta) return;

    const core = window.storeRowCore;
    const FLEET_POLL_MS = 10000;
    const ACTION_LABEL = {
      restart: 'Restart', suspend: 'Suspend', resume: 'Resume', remove: 'Remove…', 'forget-row': 'Forget row',
      'retry-provisioning': 'Retry provisioning',
    };
    // Remove and Forget row look and read differently on purpose: Remove destroys a customer's data,
    // Forget row only deletes a registry record.
    const ACTION_CLASS = { remove: 'row-btn-danger', 'forget-row': 'row-btn-forget' };

    let timer = null;
    let inFlight = false;
    let lastRows = null;
    let lastFleet = null;
    let lastGoodAt = null; // when the table was last read successfully
    let pollNotice = false; // the visible notice is a failed-poll one, so a good poll may clear it
    const busy = new Set(); // codes with a lifecycle request in flight

    const notice = document.createElement('p');
    notice.className = 'fleet-notice';
    notice.setAttribute('role', 'status');
    notice.hidden = true;
    mount.before(notice);

    function setNotice(text, kind) {
      notice.textContent = text;
      notice.className = 'fleet-notice' + (kind ? ' ' + kind : '');
      notice.hidden = !text;
    }

    function authHeaders() {
      const token = tokens.value('portal');
      return token ? { Authorization: 'Bearer ' + token } : {};
    }

    function el(tag, className, text) {
      const node = document.createElement(tag);
      if (className) node.className = className;
      if (text !== undefined && text !== null) node.textContent = text;
      return node;
    }

    function showMessage(text) {
      mount.replaceChildren(el('p', 'fleet-empty', text));
    }

    function render() {
      if (!lastRows) return;
      const now = Date.now();
      meta.textContent = [core.capacityLabel(lastFleet), 'Jar on disk: ' + core.formatJarTime(lastFleet && lastFleet.jarModifiedAt, now)]
        .filter(Boolean).join(' · ');

      if (lastRows.length === 0) {
        showMessage('No hosted stores yet. Use + New store.');
        return;
      }

      const table = el('table', 'fleet-table');
      const thead = el('thead');
      const head = el('tr');
      ['Store', 'Hostnames', 'Port', 'Status', 'Backup', 'Actions'].forEach((t) => head.append(el('th', null, t)));
      thead.append(head);
      table.append(thead);

      const body = el('tbody');
      lastRows.forEach((store) => {
        const label = core.rowLabel(store);
        const tr = el('tr');
        tr.dataset.code = store.clientCode;

        tr.append(el('td', 'cell-code', store.clientCode));

        const hosts = el('td', 'cell-hosts');
        if (store.frontendHostname) {
          const a = el('a', null, store.frontendHostname);
          a.href = 'https://' + store.frontendHostname;
          a.target = '_blank';
          a.rel = 'noopener';
          hosts.append(a);
        }
        if (store.apiHostname) hosts.append(el('div', 'cell-sub', store.apiHostname));
        tr.append(hosts);

        tr.append(el('td', null, store.port === undefined || store.port === null ? '—' : String(store.port)));

        const status = el('td', 'cell-status');
        status.append(el('span', 'pill pill-' + label.statusClass, label.statusText));
        if (label.stalenessClass) status.append(el('span', 'pill pill-' + label.stalenessClass, label.stalenessClass));
        if (label.detail) status.append(el('div', 'cell-sub', label.detail));
        tr.append(status);

        tr.append(el('td', 'cell-backup', core.backupLabel(store, now)));

        const actions = el('td', 'cell-actions');
        const working = busy.has(store.clientCode);
        core.actionsFor(store.status).forEach((action) => {
          const b = el('button', 'row-btn ' + (ACTION_CLASS[action] || ''), ACTION_LABEL[action]);
          b.type = 'button';
          b.dataset.action = action;
          b.dataset.code = store.clientCode;
          b.disabled = working;
          actions.append(b);
        });
        if (working) actions.append(el('span', 'cell-sub', 'working…'));
        tr.append(actions);

        body.append(tr);
      });
      table.append(body);
      mount.replaceChildren(table);
    }

    function describeFailure(r, body) {
      if (body && typeof body === 'object' && (body.error || body.message)) return String(body.error || body.message);
      if (r.status === 401) return 'portal token rejected (expired)';
      return 'HTTP ' + r.status;
    }

    function parse(text) {
      try { return JSON.parse(text); } catch (err) { return null; }
    }

    function schedule() {
      clearTimeout(timer);
      timer = null;
      // A 401 means every further tick is another 401: stop until a new token is pasted.
      if (document.hidden || tokens.isExpired('portal') || !tokens.value('portal')) return;
      timer = setTimeout(refresh, FLEET_POLL_MS);
    }

    function refresh() {
      clearTimeout(timer);
      timer = null;
      if (!tokens.value('portal')) {
        lastRows = null;
        meta.textContent = '';
        showMessage('Paste the portal token above to load the stores.');
        return Promise.resolve();
      }
      if (tokens.isExpired('portal')) {
        showMessage('Portal token expired. Paste a fresh one above to resume.');
        return Promise.resolve();
      }
      if (inFlight) return Promise.resolve();
      inFlight = true;
      return tokens.track('portal', fetch('/api/remote/hosted-stores', { headers: authHeaders() }))
        .then((r) => r.text().then((text) => ({ r, body: parse(text) })))
        .then(({ r, body }) => {
          // Both backend shapes (bare array / { stores }): see registry-rows.js. Not a migration shim.
          const rows = r.ok ? registryRows(body) : null;
          if (!rows) {
            // Keep the last good table; a failed read is not "no stores".
            if (!lastRows) showMessage('Could not read the stores (' + describeFailure(r, body) + ').');
            else { setNotice('Could not refresh the stores (' + describeFailure(r, body) + '); showing the last good read (' + core.readAge(lastGoodAt, Date.now()) + ').', 'failure'); pollNotice = true; }
            return;
          }
          if (pollNotice) { setNotice(''); pollNotice = false; }
          lastGoodAt = Date.now();
          lastRows = rows;
          lastFleet = Array.isArray(body) ? { stores: rows } : body;
          render();
        })
        .catch(() => {
          if (!lastRows) showMessage('Could not reach the dashboard server.');
          else { setNotice('Could not reach the dashboard server; showing the last good read (' + core.readAge(lastGoodAt, Date.now()) + '). Statuses below may be out of date.', 'failure'); pollNotice = true; }
        })
        .then(() => { inFlight = false; schedule(); });
    }

    function post(code, action) {
      const route = core.lifecycleRouteFor(action);
      if (!route) return Promise.reject(new Error('"' + action + '" is not a lifecycle action'));
      return tokens.track('portal', fetch('/api/remote/hosted-stores/' + encodeURIComponent(code) + '/' + route, {
        method: 'POST',
        headers: authHeaders(),
      }));
    }

    function forget(code) {
      return tokens.track('portal', fetch('/api/remote/hosted-stores/' + encodeURIComponent(code), {
        method: 'DELETE',
        headers: authHeaders(),
      }));
    }

    // Runs a request for one row: marks it busy, reports the outcome, then re-reads the table.
    function runAction(code, verb, request) {
      busy.add(code);
      setNotice(verb + ' ' + code + '… this can take up to a minute.', '');
      render();
      return request()
        .then((r) => r.text().then((text) => ({ r, body: parse(text) })))
        .then(({ r, body }) => {
          if (r.ok) setNotice(verb + ' ' + code + ': done.', 'success');
          else setNotice(verb + ' ' + code + ' failed: ' + describeFailure(r, body), 'failure');
        })
        .catch((err) => setNotice(verb + ' ' + code + ' failed: ' + err.message, 'failure'))
        .then(() => {
          busy.delete(code);
          return refresh();
        });
    }

    // ── Remove dialog: the store's code has to be typed ──
    const backdrop = document.getElementById('remove-modal-backdrop');
    const dlgCode = document.getElementById('remove-modal-code');
    const dlgPath = document.getElementById('remove-modal-path');
    const dlgExpect = document.getElementById('remove-modal-expect');
    const dlgInput = document.getElementById('remove-modal-input');
    const dlgSubmit = document.getElementById('remove-modal-submit');
    let removing = null;

    function closeRemove() {
      backdrop.hidden = true;
      removing = null;
      dlgInput.value = '';
    }

    function openRemove(code) {
      removing = code;
      dlgCode.textContent = code;
      dlgExpect.textContent = code;
      // The date is chosen by the server when it writes the archive, so it stays a placeholder here.
      dlgPath.textContent = '/root/removed-stores/' + code + '-<date>.zip';
      dlgInput.value = '';
      dlgSubmit.disabled = true;
      backdrop.hidden = false;
      dlgInput.focus();
    }

    dlgInput.addEventListener('input', () => {
      dlgSubmit.disabled = !(removing && dlgInput.value === removing);
    });
    dlgSubmit.addEventListener('click', () => {
      const code = removing;
      if (!code || dlgInput.value !== code) return; // re-checked here: the disabled flag is not the guard
      closeRemove();
      runAction(code, 'Removing', () => post(code, 'remove'));
    });
    document.getElementById('remove-modal-close').addEventListener('click', closeRemove);
    document.getElementById('remove-modal-cancel').addEventListener('click', closeRemove);
    backdrop.addEventListener('click', (e) => { if (e.target === backdrop) closeRemove(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !backdrop.hidden) closeRemove(); });

    mount.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-action]');
      if (!btn || btn.disabled) return;
      const code = btn.dataset.code;
      const action = btn.dataset.action;
      const store = (lastRows || []).find((s) => s.clientCode === code);
      const domain = (store && store.frontendHostname) || code;

      if (action === 'suspend') {
        if (!window.confirm('Suspend ' + domain + '?\n\nThe store goes offline and its customer cannot use it until you resume it. Their data is kept.')) return;
        runAction(code, 'Suspending', () => post(code, 'suspend'));
      } else if (action === 'resume') {
        runAction(code, 'Resuming', () => post(code, 'resume'));
      } else if (action === 'restart') {
        // Restart bounces a LIVE store: its till is down until the backend answers health again.
        if (!window.confirm('Restart ' + domain + '?\n\nThe store will be briefly unavailable (typically 30 to 60 seconds) while it comes back. Anyone mid-sale on it will be interrupted.')) return;
        runAction(code, 'Restarting', () => post(code, 'restart'));
      } else if (action === 'remove') {
        openRemove(code);
      } else if (action === 'retry-provisioning') {
        // Not a lifecycle call. Re-running Create is what resumes the stage walk (it picks up at
        // failedStage and keeps the port), so hand the operator to that panel, pre-filled.
        document.dispatchEvent(new CustomEvent('prefill-create-store', {
          detail: { clientCode: code, subdomain: store ? store.subdomain : '' },
        }));
      } else if (action === 'forget-row') {
        if (!window.confirm('Forget the registry row for "' + code + '"?\n\nThis is NOT Remove. It deletes only the registry record, to free the subdomain and port of a mistyped or half-created store. It does not stop any service, delete DNS or tunnel entries, or delete any data.')) return;
        runAction(code, 'Forgetting row for', () => forget(code));
      }
    });

    let debounce = null;
    tokens.onInput('portal', () => {
      clearTimeout(debounce);
      debounce = setTimeout(refresh, 400);
    });
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) { clearTimeout(timer); timer = null; } else refresh();
    });

    refresh();
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
