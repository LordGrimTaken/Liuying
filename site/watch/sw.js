/* Liuying web player service worker. Served at /watch/sw.js, scope /watch/.
 *
 * UPDATING: the app shell is precached under a versioned cache name. Whenever ANY file listed in
 * SHELL below changes (or a file is added/removed), bump VERSION. A new VERSION makes the browser
 * see a changed sw.js, install it (re-downloading every shell file into a fresh cache), and delete
 * the old caches on activate. `npm test` in site/watch (test/sw.test.mjs) fails if SHELL and the
 * files on disk disagree, so a forgotten entry is caught. Between bumps, shell assets are served
 * stale-while-revalidate, so edits that forgot the bump still arrive on the next load.
 *
 * NEVER cached: /api/*, addon manifests/catalogs/streams, subtitles, posters and media (all cross-origin
 * or non-shell). Only same-origin GETs under /watch/ and the hls vendor file are handled here.
 */
const VERSION = 'v1';
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
  '/watch/player/player.js',
  '/watch/player/subs-controller.js',
  '/watch/player/subtitles.js',
  '/watch/ui/browse.js',
  '/watch/ui/calendar.js',
  '/watch/ui/components.js',
  '/watch/ui/continue.js',
  '/watch/ui/details.js',
  '/watch/ui/dom.js',
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
    const nav = req.mode === 'navigate';
    // every in-scope navigation (/watch/, /watch/anything) gets the same SPA shell
    const key = nav ? '/watch/' : url.pathname;
    const hit = await cache.match(key);
    const update = revalidate(cache, key, nav ? new Request('/watch/', { cache: 'no-cache' }) : req);
    if (hit) { event.waitUntil(update.catch(() => {})); return hit; }
    try { return await update; } catch (err) {
      if (nav) { const off = await cache.match(OFFLINE); if (off) return off; }
      return Response.error();
    }
  })());
});
