// Pure logic for the "Restart all stores" walk: turns a RestartRun (GET /api/hosted-stores/
// restart-all/status) into the sentence the owner reads. Loaded two ways, like store-row-core.js:
// require()d by the node tests, and as a plain <script> in the page.
//
// Field names are the backend's: RestartRun { state, currentClientCode, walked, skipped, ... },
// walked[] = { clientCode, restarted, healthy, error }, skipped[] = { clientCode, reason }.
// HALTED and NOOP are the two outcomes where the fleet restart did NOT do what was asked, so both
// are reported as failures; an unrecognised state is also a failure, never a success.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.restartRunCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {

  /** Returns { done, kind, text }. done=false means keep polling. */
  function describeRestartRun(run) {
    const walked = Array.isArray(run && run.walked) ? run.walked : [];
    const state = run && run.state;
    if (state === 'RUNNING') {
      const cur = run.currentClientCode;
      return { done: false, kind: '', text: 'Restarting all stores' + (cur ? ': ' + cur + ' now' : '') + ' (' + walked.length + ' done so far)… one at a time, this can take several minutes.' };
    }
    if (state === 'DONE') {
      const n = walked.filter((w) => w && w.restarted).length;
      const skipped = Array.isArray(run.skipped) ? run.skipped.length : 0;
      return { done: true, kind: 'success', text: 'Restart all: done. ' + n + (n === 1 ? ' store' : ' stores') + ' restarted' + (skipped ? ', ' + skipped + ' skipped' : '') + '.' };
    }
    if (state === 'HALTED') {
      const bad = walked.filter((w) => w && !w.healthy).pop();
      const reason = bad && bad.error ? ' (' + bad.error + ')' : '';
      return { done: true, kind: 'failure', text: 'Restart all HALTED at ' + (bad ? bad.clientCode : 'an unknown store') + reason + '. ' + walked.length + ' stores were reached; the stores after it were NOT restarted.' };
    }
    if (state === 'NOOP') {
      return { done: true, kind: 'failure', text: 'Restart all restarted NOTHING: there were no stores eligible to restart. Check the registry and the box; this is not a success.' };
    }
    // IDLE (the backend restarted mid-walk and forgot it) or anything unrecognised: not a success.
    return { done: true, kind: 'failure', text: 'Restart all: the backend reports no walk running and no result (state ' + (state || 'unknown') + '). It may have been restarted mid-walk; check the table.' };
  }

  return { describeRestartRun };
});
