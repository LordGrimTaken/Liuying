import * as store from './store.js';
import * as sync from './sync.js';
import { route, start as startRouter, render, currentRoute, onRoute } from './router.js';
import { h, icon, clear, toast } from './ui/dom.js';
import { today } from './ui/today.js';
import { search } from './ui/search.js';
import { shelf } from './ui/shelf.js';
import { sources } from './ui/sources.js';
import { settings } from './ui/settings.js';
import { browse } from './ui/browse.js';
import { movies, series } from './ui/catalogPage.js';
import { details } from './ui/details.js';
import { calendar } from './ui/calendar.js';
import { openSearch, closeSearch, initSearchShortcut } from './ui/searchOverlay.js';
import { streams } from './ui/streams.js';
import { player } from './player/player.js';
import { refreshManifests, applyTimeoutSetting } from './addon/service.js';

const NAV = [['today', '#/', 'Today', 'today'], ['movies', '#/movies', 'Movies', 'film'], ['series', '#/series', 'Series', 'tv'], ['shelf', '#/shelf', 'Shelf', 'shelf'], ['calendar', '#/calendar', 'Calendar', 'calendar'], ['search', '#/search', 'Search', 'search'], ['sources', '#/sources', 'Sources', 'sources'], ['settings', '#/settings', 'You', 'user']];
const SECTION = { today: 'today', movies: 'movies', series: 'series', browse: 'today', title: 'today', streams: 'today', play: 'today', search: 'search', shelf: 'shelf', calendar: 'calendar', sources: 'sources', settings: 'settings' };
const TITLES = { today: 'Today', movies: 'Movies', series: 'Series', search: 'Search', shelf: 'Shelf', calendar: 'Calendar', sources: 'Sources', settings: 'Settings', browse: 'Browse', title: 'Details', streams: 'Streams', play: 'Now playing' };
/** Routes whose first screen is full-bleed artwork: the top bar stays transparent until the page scrolls. */
const ART_ROUTES = new Set(['today', 'search', 'movies', 'series', 'title']);

function applySettings() {
  const s = store.settings();
  document.documentElement.dataset.theme = s.theme || 'dark';
  document.documentElement.dataset.poster = s.posterSize || 'm';
  document.documentElement.dataset.accent = s.accent || 'vermilion';
  document.documentElement.dataset.font = s.fontScale || 'm';
  document.body.classList.toggle('reduce-motion', !!s.reduceMotion);
  const tc = { dark: '#14110f', black: '#000000', paper: '#f2e9da' }[s.theme] || '#14110f';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', tc);
  applyTimeoutSetting();
}

function buildRail() {
  const rail = document.getElementById('rail');
  clear(rail);
  const searchClick = (e) => { if (e.ctrlKey || e.metaKey || e.shiftKey) return; e.preventDefault(); openSearch(); };
  rail.append(
    h('a', { class: 'brand', href: '#/', 'aria-label': 'Liuying home' }, h('span', { class: 'seal' }, '流', h('br'), '影')),
    h('div', { class: 'nav' }, NAV.map(([k, hr, label, ic]) => h('a', { class: 'navlink', href: hr, 'data-nav': k, title: k === 'settings' ? 'Settings and account' : undefined, onClick: k === 'search' ? searchClick : undefined }, icon(ic, 22), h('span', { class: 'lab', text: label })))));
}

function buildTopbar() {
  const bar = document.getElementById('topbar');
  clear(bar);
  const tb = (name, label, hr, key) => h('a', { class: 'tbtn', href: hr, 'aria-label': label, title: label, 'data-tb': key }, icon(name, 22));
  bar.append(
    h('a', { class: 'tb-brand', href: '#/', 'aria-label': 'Liuying home' }, h('span', { class: 'seal sm', 'aria-hidden': 'true' }, '流', h('br'), '影'), h('span', { class: 'wordmark', text: 'Liuying' })),
    h('div', { class: 'tb-actions' },
      h('button', { class: 'tbtn', type: 'button', 'aria-label': 'Search', title: 'Search ( / )', onClick: () => openSearch() }, icon('search', 22)),
      tb('calendar', 'Calendar', '#/calendar', 'calendar'),
      tb('settings', 'Settings', '#/settings', 'settings'),
      h('a', { class: 'avatar', id: 'acct', href: '#/settings', 'aria-label': 'Account', title: 'Account', 'data-state': 'signedout' }, icon('user', 20))));
  const upd = () => bar.classList.toggle('solid', window.scrollY > 24 || !ART_ROUTES.has(currentRoute()?.name));
  addEventListener('scroll', upd, { passive: true });
  onRoute(upd);
}
function markNav() {
  const r = currentRoute();
  let sec = SECTION[r?.name] || 'today';
  if (r?.name === 'browse') sec = r.params.type === 'series' ? 'series' : r.params.type === 'movie' ? 'movies' : 'today';
  document.querySelectorAll('.navlink').forEach((a) => { if (a.dataset.nav === sec) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
  document.querySelectorAll('.tbtn[data-tb]').forEach((a) => { if (a.dataset.tb === sec) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
  document.title = `${TITLES[r?.name] || 'Liuying'} – Liuying Web Player`;
}

const SYNC_LABEL = { idle: 'Synced', syncing: 'Syncing', offline: 'Offline', error: 'Sync issue' };
function drawSyncChip(st) {
  const a = document.getElementById('acct');
  if (!a) return;
  a.dataset.state = st.user ? st.state || 'idle' : 'signedout';
  if (st.user) {
    const label = `Signed in as ${st.user.username} · ${SYNC_LABEL[st.state] || 'Synced'}`;
    a.href = '#/settings';
    a.title = label; a.setAttribute('aria-label', label);
    a.replaceChildren(h('span', { class: 'initial', text: (st.user.username || '?').trim().charAt(0).toUpperCase() }), h('i', { class: 'sdot' }));
  } else {
    a.href = st.state === 'signedout' ? '/login' : '#/settings';
    a.title = 'Sign in'; a.setAttribute('aria-label', 'Sign in');
    a.replaceChildren(icon('user', 20));
  }
}
function drawBanner(st) {
  const b = document.getElementById('banner');
  const show = st.state === 'signedout' && !store.prefs().dismissedBanner && !document.body.classList.contains('playing');
  b.hidden = !show;
  if (!show) { clear(b); return; }
  if (b.firstChild) return;
  b.className = 'banner';
  b.append(h('div', { class: 'grow' }, h('a', { href: '/login', text: 'Sign in to sync across devices' }), h('span', { class: 'bnote', text: ' — your sources, shelf and progress follow you. Without an account everything stays in this browser.' })),
    h('button', { class: 'btn sm', type: 'button', text: 'Dismiss', 'aria-label': 'Dismiss sign-in banner', onClick: () => { store.setPref('dismissedBanner', true); drawBanner(sync.getStatus()); } }));
}

/** Offline support. The worker only controls /watch/ (not bare /watch), so normalise the address first. */
function registerWorker() {
  if (location.pathname === '/watch') history.replaceState(null, '', '/watch/' + location.search + location.hash);
  if (!('serviceWorker' in navigator)) return;
  const hadController = !!navigator.serviceWorker.controller;
  let notified = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    // a new version took over; don't reload under the viewer (they may be mid-playback)
    if (!hadController || notified) return;
    notified = true;
    toast('Liuying was updated.', { action: 'Reload', onAction: () => location.reload(), ms: 12000 });
  });
  const reg = () => navigator.serviceWorker.register('/watch/sw.js', { scope: '/watch/' }).catch((e) => console.warn('Service worker unavailable', e));
  if (document.readyState === 'complete') reg(); else addEventListener('load', reg, { once: true });
}

async function main() {
  await store.init();
  applySettings();
  store.subscribe((k) => { if (k === 'settings') applySettings(); });
  buildRail();
  buildTopbar();
  initSearchShortcut();

  route('/', today, 'today');
  route('/movies', movies, 'movies');
  route('/series', series, 'series');
  route('/search', search, 'search');
  route('/shelf', shelf, 'shelf');
  route('/calendar', calendar, 'calendar');
  route('/sources', sources, 'sources');
  route('/settings', settings, 'settings');
  route('/browse/:sid/:type/:cid', browse, 'browse');
  route('/title/:type/:id', details, 'title');
  route('/streams/:type/:id', streams, 'streams');
  route('/play/:type/:id', player, 'play');

  let first = true;
  let lastRaw = '';
  onRoute((cur) => { if (cur.name !== 'search') closeSearch(); markNav(); drawBanner(sync.getStatus()); if (!first && cur.raw !== lastRaw && cur.name !== 'play') document.getElementById('view')?.focus({ preventScroll: true }); lastRaw = cur.raw; });
  await startRouter();
  first = false;

  sync.subscribeStatus((st) => { drawSyncChip(st); drawBanner(st); });
  sync.setHooks({ onRemoteChange: () => { const r = currentRoute(); if (r && r.name !== 'play') render(); } });
  sync.start();
  registerWorker();
  sync.init().then(() => refreshManifests().then((n) => { const r = currentRoute(); if (n && r && ['today', 'sources'].includes(r.name)) render(); }));
}

main().catch((e) => {
  console.error(e);
  document.getElementById('view').textContent = 'Something went wrong starting the player. Reload the page.';
});
