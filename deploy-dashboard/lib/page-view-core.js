// Which page block belongs to which view. Loaded two ways, like api-base-core.js: require()d by the
// node tests, and as a plain <script> in the page.
//
// The page is split into a Hosted stores view and a Deployment view. A block carries data-view;
// a block with no data-view is shared and shows in both. The view comes from ?view=, and anything
// missing or unrecognised resolves to null, which shows everything. A typo must never hide both
// halves of the page.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./api-base-core'));
  else root.pageViewCore = factory(root.apiBaseCore);
})(typeof self !== 'undefined' ? self : this, function (apiBaseCore) {

  const VIEW_STORES = 'stores';
  const VIEW_DEPLOY = 'deploy';

  /**
   * The view named by ?view=, or null meaning "show everything".
   *
   * Matches only a whole `view` key (`?view=` or `&view=`), so `?preview=stores` does not count.
   */
  function resolveView(search) {
    const raw = /[?&]view=([^&]*)/.exec(String(search || ''));
    if (!raw) return null;
    if (raw[1] === VIEW_STORES || raw[1] === VIEW_DEPLOY) return raw[1];
    return null;
  }

  /**
   * The view a slot's card belongs to. Fleet slots go to Hosted stores; every other slot,
   * including one nobody has named yet, stays on Deployment so it does not vanish from both pages.
   */
  function viewForSlot(slot) {
    return apiBaseCore.FLEET_SLOTS.indexOf(slot) !== -1 ? VIEW_STORES : VIEW_DEPLOY;
  }

  /**
   * Whether a block shows in the resolved view. A block with no data-view is shared and always
   * shows; with no resolved view (null) every block shows.
   */
  function isBlockVisible(blockView, resolvedView) {
    if (resolvedView === null) return true;
    if (!blockView) return true;
    return blockView === resolvedView;
  }

  return { VIEW_STORES, VIEW_DEPLOY, resolveView, viewForSlot, isBlockVisible };
});
