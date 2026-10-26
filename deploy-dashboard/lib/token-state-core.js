// Pure logic for the dashboard's token strip. Loaded two ways: require()d by the node tests and
// by nothing else on the server, and as a plain <script> in the page (the server serves this file
// at /token-state-core.js), which is why it is a UMD wrapper like public/registry-rows.js.
//
// There are two tokens and they are NOT interchangeable: 'portal' is minted by a login at
// admin.damansoft.com, 'local' by the local admin backend on :8083. Each is judged on its own.
//
// Admin sessions are in-memory objects in the backend, so every token dies whenever that backend
// restarts. Only a 401 is evidence of that. A 5xx, a 503 from this dashboard's own
// unreachable-backend path, or a network failure says nothing about the token, so none of them
// may flip it - blaming the token would send the owner hunting for the wrong problem.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.tokenStateCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  // current: 'active' | 'expired' | 'missing'
  // hasValue: is anything pasted in the field
  // lastStatus: HTTP status of the latest call made with this token; null/undefined/0 when the
  //   call never got an HTTP answer (network failure) or none has been made since the value changed.
  function nextTokenState(current, { hasValue, lastStatus }) {
    if (!hasValue) return 'missing';
    if (lastStatus === 401) return 'expired';
    if (typeof lastStatus === 'number' && lastStatus >= 200 && lastStatus < 300) return 'active';
    // No verdict: keep what we believed, except that a value now exists where there was none.
    return current === 'expired' ? 'expired' : 'active';
  }

  const SOURCE = {
    portal: 'a login at admin.damansoft.com',
    local: 'a login on the local admin portal (the backend on :8083)',
  };

  function tokenMessage(kind, state) {
    if (!SOURCE[kind]) throw new Error(`Unknown token kind: ${kind}`);
    switch (state) {
      case 'active':
        return 'Token set. It is checked by the next request that uses it.';
      case 'missing':
        return `No token. Paste one from ${SOURCE[kind]}.`;
      case 'expired':
        return 'Rejected (401): most likely the backend restarted since this token was issued - every '
          + `restart, including an Admin Backend deploy, ends all sessions. Paste a fresh one from ${SOURCE[kind]}.`;
      default:
        throw new Error(`Unknown token state: ${state}`);
    }
  }

  return { nextTokenState, tokenMessage };
});
