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

  cards.forEach((card) => {
    const slot = card.dataset.slot;
    const btn = card.querySelector('.deploy-btn');
    const log = card.querySelector('.log');
    const footer = card.querySelector('.footer');
    const versionInput = card.querySelector('.version-input');

    if (slot === 'generic') {
      fetch('/api/generic/latest-version')
        .then((r) => r.json())
        .then((data) => {
          if (data.version) versionInput.value = data.version;
        })
        .catch(() => {});
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
        if (ownedBySource.has(slot)) return;
        btn.disabled = true;
        footer.textContent = 'Running...';
        footer.className = 'footer';
        pollUntilIdle(slot, btn, footer);
      });
    })
    .catch(() => {});
})();
