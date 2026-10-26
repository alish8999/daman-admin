// Reads the store rows out of a GET /api/hosted-stores body.
//
// The backend used to answer with a bare array and now answers { stores: [...], ...fleet numbers }.
// This dashboard runs locally while the admin backend runs on a remote server and the two are
// deployed separately, so this page is routinely NEWER (or older) than the backend it talks to.
// Accepting both shapes is a standing property of how it is deployed, not a migration step:
// do not remove the array branch.
//
// Returns the rows array, or null when the body is neither shape (an error body, say).
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.registryRows = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  return function registryRows(body) {
    if (Array.isArray(body)) return body;
    if (body && typeof body === 'object' && Array.isArray(body.stores)) return body.stores;
    return null;
  };
});
