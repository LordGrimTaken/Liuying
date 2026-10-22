import * as store from './store.js';
import * as sync from './sync.js';
import { route, start as startRouter, render, currentRoute } from './router.js';
import { h, icon, clear } from './ui/dom.js';
import { today } from './ui/today.js';
import { search } from './ui/search.js';
import { shelf } from './ui/shelf.js';
import { sources } from './ui/sources.js';
import { settings } from './ui/settings.js';
import { browse } from './ui/browse.js';
import { details } from './ui/details.js';
import { streams } from './ui/streams.js';
import { player } from './player/player.js';
import { refreshManifests, applyTimeoutSetting } from './addon/service.js';

const NAV = [['today', '#/', 'Today'], ['search', '#/search', 'Search'], ['shelf', '#/shelf', 'Shelf'], ['sources', '#/sources', 'Sources'], ['settings', '#/settings', 'Settings']];
const SECTION = { today: 'today', browse: 'today', title: 'today', streams: 'today', play: 'today', search: 'search', shelf: 'shelf', sources: 'sources', settings: 'settings' };
const TITLES = { today: 'Today', search: 'Search', shelf: 'Shelf', sources: 'Sources', settings: 'Settings', browse: 'Browse', title: 'Details', streams: 'Streams', play: 'Now playing' };

function applySettings() {
  const s = store.settings();
  document.documentElement.dataset.theme = s.theme || 'dark';
  document.documentElement.dataset.poster = s.posterSize || 'm';
  document.body.classList.toggle('reduce-motion', !!s.reduceMotion);
  const tc = { dark: '#14110f', black: '#000000', paper: '#f2e9da' }[s.theme] || '#14110f';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', tc);
  applyTimeoutSetting();
}

function buildRail() {
  const rail = document.getElementById('rail');
  clear(rail);
  rail.append(
    h('a', { class: 'brand', href: '#/', 'aria-label': 'Liuying home' }, h('span', { class: 'seal' }, '流', h('br'), '影')),
    h('div', { class: 'nav' }, NAV.map(([k, hr, label]) => h('a', { class: 'navlink', href: hr, 'data-nav': k }, icon(k === 'today' ? 'today' : k, 22), h('span', { text: label })))),
    h('div', { class: 'railfoot', id: 'railfoot' }));
}
function markNav() {
  const r = currentRoute();
  const sec = SECTION[r?.name] || 'today';
  document.querySelectorAll('.navlink').forEach((a) => { if (a.dataset.nav === sec) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
  document.title = `${TITLES[r?.name] || 'Liuying'} – Liuying Web Player`;
}

function drawSyncChip(st) {
  const foot = document.getElementById('railfoot');
  if (!foot) return;
  clear(foot);
  if (st.user) foot.append(h('a', { class: 'sync', href: '#/settings', 'data-state': st.state, title: `Signed in as ${st.user.username}` }, h('span', { class: 'dot' }), h('span', { text: st.state === 'syncing' ? 'Syncing' : st.state === 'offline' ? 'Offline' : st.state === 'error' ? 'Sync issue' : 'Synced' }), h('span', { text: st.user.username })));
  else if (st.state === 'signedout') foot.append(h('a', { class: 'sync', href: '/login', 'data-state': 'signedout' }, h('span', { class: 'dot' }), h('span', { text: 'Sign in' })));
}
function drawBanner(st) {
  const b = document.getElementById('banner');
  const show = st.state === 'signedout' && !store.prefs().dismissedBanner && !document.body.classList.contains('playing');
  b.hidden = !show;
  if (!show) { clear(b); return; }
  if (b.firstChild) return;
  b.className = 'banner';
  b.append(h('div', { class: 'grow' }, h('a', { href: '/login', text: 'Sign in to sync across devices' }), ' — your sources, shelf and progress follow you. Without an account everything stays in this browser.'),
    h('button', { class: 'btn sm', type: 'button', text: 'Dismiss', 'aria-label': 'Dismiss sign-in banner', onClick: () => { store.setPref('dismissedBanner', true); drawBanner(sync.getStatus()); } }));
}

async function main() {
  await store.init();
  applySettings();
  store.subscribe((k) => { if (k === 'settings') applySettings(); });
  buildRail();

  route('/', today, 'today');
  route('/search', search, 'search');
  route('/shelf', shelf, 'shelf');
  route('/sources', sources, 'sources');
  route('/settings', settings, 'settings');
  route('/browse/:sid/:type/:cid', browse, 'browse');
  route('/title/:type/:id', details, 'title');
  route('/streams/:type/:id', streams, 'streams');
  route('/play/:type/:id', player, 'play');

  let first = true;
  addEventListener('hashchange', () => { queueMicrotask(() => { markNav(); drawBanner(sync.getStatus()); if (!first) document.getElementById('view')?.focus({ preventScroll: true }); first = false; }); });
  await startRouter();
  markNav();
  first = false;

  sync.subscribeStatus((st) => { drawSyncChip(st); drawBanner(st); });
  sync.setHooks({ onRemoteChange: () => { const r = currentRoute(); if (r && r.name !== 'play') render(); } });
  sync.start();
  sync.init().then(() => refreshManifests().then((n) => { const r = currentRoute(); if (n && r && ['today', 'sources'].includes(r.name)) render(); }));
}

main().catch((e) => {
  console.error(e);
  document.getElementById('view').textContent = 'Something went wrong starting the player. Reload the page.';
});
