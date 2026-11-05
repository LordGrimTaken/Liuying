/* Liuying web player service worker. Served at /watch/sw.js, scope /watch/.
 *
 * UPDATING: the app shell is precached in a versioned cache. Whenever ANY shell file changes (or one
 * is added/removed), bump VERSION below and refresh the hash line. A changed sw.js makes the browser
 * install the new worker, which re-downloads every SHELL file into a fresh cache and deletes the old
 * caches on activate; open pages are told to reload via a toast. test/sw.test.mjs
 * (`node --test test/*.test.mjs`) fails if SHELL disagrees with the files on disk, or if shell
 * content changed without the hash being updated (the failure prints the new hash).
 * Between bumps, shell assets are served stale-while-revalidate, so an edit that forgot the bump
 * still arrives on the next load.
 * shell-hash: 0d25a6a65093
 *
 * NEVER cached: /api/*, addon manifests/catalogs/streams, subtitles, posters and media (cross-origin
 * or non-shell). Only same-origin GETs under /watch/ and the hls vendor file are handled here.
 */
const VERSION = 'v26';
const CACHE = `liuying-shell-${VERSION}`;
const PREFIX = 'liuying-shell-';
const SCOPE_PATH = '/watch/';
const OFFLINE = '/watch/offline.html';
const HLS = '/vendor/hls.min.js';

const SHELL = [
  '/watch/',
  OFFLINE,
  '/watch/app.css',
  '/watch/favicon.svg',
  '/watch/icons/logo.svg',
  '/watch/manifest.webmanifest',
  '/watch/icons/icon.svg',
  '/watch/icons/icon-192.png',
  '/watch/icons/icon-512.png',
  '/watch/icons/icon-maskable-512.png',
  '/watch/icons/apple-touch-icon.png',
  '/watch/main.js',
  '/watch/progress.js',
  '/watch/router.js',
  '/watch/store.js',
  '/watch/sync-merge.js',
  '/watch/sync.js',
  '/watch/util.js',
  '/watch/ux.js',
  '/watch/addon/client.js',
  '/watch/addon/protocol.js',
  '/watch/addon/service.js',
  '/watch/addon/streams.js',
  '/watch/addon/sourcetest.js',
  '/watch/player/playback.js',
  '/watch/ui/history.js',
  '/watch/player/player.js',
  '/watch/player/party.js',
  '/watch/player/subs-controller.js',
  '/watch/player/subtitles.js',
  '/watch/plugins/contract.js',
  '/watch/plugins/engine.js',
  '/watch/plugins/host.js',
  '/watch/plugins/rules.js',
  '/watch/plugins/sandbox.htm',
  '/watch/plugins/service.js',
  '/watch/plugins/vendor/NOTICE.txt',
  '/watch/plugins/vendor/cheerio.js',
  '/watch/plugins/vendor/crypto-js.js',
  '/watch/ui/browse.js',
  '/watch/ui/calendar.js',
  '/watch/ui/catalogPage.js',
  '/watch/ui/components.js',
  '/watch/ui/continue.js',
  '/watch/ui/details.js',
  '/watch/ui/hero.js',
  '/watch/ui/dom.js',
  '/watch/ui/pluginsPanel.js',
  '/watch/ui/search.js',
  '/watch/ui/searchOverlay.js',
  '/watch/ui/settings.js',
  '/watch/ui/shelf.js',
  '/watch/ui/sources.js',
  '/watch/ui/streams.js',
  '/watch/ui/today.js',
  HLS,
];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    // cache:'reload' skips the HTTP cache so a new version never precaches stale files
    await Promise.all(SHELL.map(async (url) => {
      const res = await fetch(new Request(url, { cache: 'reload', credentials: 'same-origin' }));
      if (!res.ok) throw new Error(`precache ${url}: ${res.status}`);
      await cache.put(url, res);
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const k of await caches.keys()) if (k.startsWith(PREFIX) && k !== CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

/** True for requests this worker is allowed to answer from the cache. */
function handled(req, url) {
  if (req.method !== 'GET') return false;
  if (url.origin !== self.location.origin) return false; // addons, posters, streams, subtitles
  if (req.headers.has('range')) return false; // media
  if (['video', 'audio', 'track'].includes(req.destination)) return false;
  if (url.pathname === HLS) return true;
  if (url.pathname === '/watch' || !url.pathname.startsWith(SCOPE_PATH)) return false;
  if (url.pathname === '/watch/sw.js') return false; // browser manages updates of the worker itself
  return true;
}

async function revalidate(cache, key, req) {
  const res = await fetch(req, { cache: 'no-cache' });
  if (res.ok && res.type === 'basic') await cache.put(key, res.clone());
  return res;
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);
  if (!handled(req, url)) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    // every in-scope page navigation (/watch/, /watch/anything) gets the same SPA shell. A navigation to a real file (the plugin
    // sandbox frame, /watch/plugins/sandbox.htm) must get that file, with its own headers, not the shell.
    const nav = req.mode === 'navigate' && !/\.[^/]+$/.test(url.pathname);
    const key = nav ? '/watch/' : url.pathname;
    const hit = await cache.match(key);
    const update = revalidate(cache, key, nav ? new Request('/watch/', { cache: 'no-cache' }) : req.mode === 'navigate' ? new Request(url.pathname, { cache: 'no-cache' }) : req);
    if (hit) { event.waitUntil(update.catch(() => {})); return hit; }
    try { return await update; } catch (err) {
      if (nav) { const off = await cache.match(OFFLINE); if (off) return off; }
      return Response.error();
    }
  })());
});




