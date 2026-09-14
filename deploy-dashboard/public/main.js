(function () {
  const cards = document.querySelectorAll('.card');

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

      source.addEventListener('log', (event) => {
        const { line } = JSON.parse(event.data);
        log.textContent += line + '\n';
        log.scrollTop = log.scrollHeight;
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
        btn.disabled = false;
      });

      source.onerror = () => {
        footer.textContent = 'Connection error - deploy may still be running server-side; check the server console.';
        footer.className = 'footer failure';
        source.close();
        btn.disabled = false;
      };
    });
  });
})();
