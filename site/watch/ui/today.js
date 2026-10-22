import { h, icon, clear, iconBtn, toast } from './dom.js';
import * as store from '../store.js';
import { catalogCapabilities, isHomeEligible, catalogKey, catalogTitle, isAdult } from '../addon/protocol.js';
import { loadCatalog } from '../addon/service.js';
import { describeError } from '../addon/client.js';
import { posterCard, rowSection, showSkeletons, errorCard, emptyPage } from './components.js';
import { renderHero } from './hero.js';
import { renderContinueRow } from './continue.js';
import { href, enc, restoreScroll } from '../router.js';
import { pickFeatured, orderRows, pickSurprise } from '../ux.js';
import { navigate } from '../router.js';
import { titleHref } from './components.js';

const cache = new Map(); // key -> {items, at}
const TTL = 15 * 60e3;          // served without asking the source (L089)
const STALE_MAX = 24 * 3600e3;  // older than this is not shown at all
const sameIds = (a, b) => a.length === b.length && a.every((x, i) => x.id === b[i].id);
/** Last saved copy of a catalog (this tab, or the previous visit), or null. */
const saved = (ck) => { const m = cache.get(ck); if (m) return m; const d = store.cachedCatalog(ck); return d && Date.now() - d.at < STALE_MAX ? d : null; };
const remember = (ck, items) => { cache.set(ck, { items, at: Date.now() }); store.cacheCatalog(ck, items); };
/** Hide-watched setting applied to a catalog (L004). */
export const visibleItems = (items) => (store.settings().hideWatched ? items.filter((it) => !store.getProgress(it.id, null)?.watched) : items);

export const defaultExtra = (cat) => {
  const c = catalogCapabilities(cat);
  return c.genreRequired && c.genreOptions.length ? { genre: c.genreOptions[0] } : undefined;
};

/** Every home-eligible catalog in the user's saved order, with a `hidden` flag (used by Settings). */
export function allHomeRows() {
  const s = store.settings();
  const rows = [];
  for (const src of store.enabledSources()) {
    if (isAdult(src.manifest) && s.hideAdult) continue;
    for (const cat of src.manifest.catalogs) {
      if (!isHomeEligible(cat)) continue;
      const key = catalogKey(src.url, cat);
      rows.push({ src, cat, key, hidden: !!s.hiddenCatalogs?.[key] });
    }
  }
  return orderRows(rows, s.homeOrder);
}
export const homeRows = () => allHomeRows().filter((r) => !r.hidden);

const PLURAL = { movie: 'Movies', series: 'Series', tv: 'TV', channel: 'Channels' };
export const rowSubtitle = (cat, src) => `${PLURAL[cat.type] || typeLabel(cat.type)} from ${src.manifest.name}`;

/** Titles seen in already-loaded catalogs; feeds search suggestions. */
export const cachedTitles = () => [...cache.values()].flatMap((c) => c.items.map((i) => i.name)).filter(Boolean);

/** Cached catalog fetch shared by Today, Movies and Series rows. */
export async function fetchRow(src, cat, extra, { bypass = false, signal } = {}) {
  const ck = catalogKey(src.url, cat) + JSON.stringify(extra || {});
  const cached = saved(ck);
  if (cached && !bypass && Date.now() - cached.at < TTL) return cached.items;
  try {
    const items = await loadCatalog(src, cat, extra, { signal });
    remember(ck, items);
    return items;
  } catch (e) {
    if (cached && !signal?.aborted) return cached.items; // offline or the source is down: show the last copy
    throw e;
  }
}
export const isCached = (src, cat, extra) => { const c = saved(catalogKey(src.url, cat) + JSON.stringify(extra || {})); return !!c && Date.now() - c.at < TTL; };

export function today() {
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  const heroBox = h('div', { class: 'hero-box' });
  const contBox = h('div', { class: 'cont-box' });
  const rowsBox = h('div', { class: 'rows-box' });
  const failBanner = h('div', { class: 'fail-box' });
  const refreshBtn = h('button', { class: 'btn sm refresh', type: 'button', onClick: () => build(true) }, icon('refresh', 18), 'Refresh');
  const surpriseBtn = h('button', { class: 'btn sm', type: 'button', title: 'Open a random title from your shelf or what is on screen', onClick: surprise }, icon('dice', 18), 'Surprise me');
  page.append(h('div', { class: 'page-h sr' }, h('h1', { text: 'Today' })), failBanner, heroBox, contBox, rowsBox, h('div', { class: 'refresh-box' }, surpriseBtn, refreshBtn));
  /** A random unwatched title: from your shelf first, otherwise from the catalogs loaded here (L010). */
  function surprise() {
    const unwatched = (i) => !!store.getProgress(i.id, null)?.watched;
    const lib = store.library();
    const pool = lib.some((i) => !unwatched(i)) ? lib : [...cache.values()].flatMap((c) => c.items).filter((i) => i && i.id && i.type);
    const pick = pickSurprise(pool, { isWatched: unwatched, avoid: store.prefs().lastSurprise || '' });
    if (!pick) { toast('Nothing to pick from yet. Add a source or save a title first.'); return; }
    store.setPref('lastSurprise', pick.id);
    toast(`Tonight: ${pick.name}`);
    navigate(`/title/${encodeURIComponent(pick.type || 'movie')}/${encodeURIComponent(pick.id)}`);
  }
  let heroCtl = null;

  let status = {};
  function build(bypass = false) {
    heroCtl?.destroy(); heroCtl = null;
    clear(heroBox); clear(rowsBox); clear(failBanner);
    surpriseBtn.hidden = false; refreshBtn.hidden = false;
    status = {};
    const rows = homeRows();
    renderContinueRow(contBox, ctl.signal, { onChange: () => renderContinueRow(contBox, ctl.signal) });
    if (!store.sources().length) {
      clear(contBox);
      surpriseBtn.hidden = true; refreshBtn.hidden = true;
      if (store.prefs().dismissedGuide) rowsBox.append(emptyPage('sources', 'Your scroll is blank', 'Add a source with its manifest link and your catalogs will appear here.', h('a', { class: 'btn pri', href: '#/sources?add=1', text: 'Add a source' }), h('button', { class: 'btn', type: 'button', text: 'Show the guide', onClick: () => { store.setPref('dismissedGuide', false); build(); } })));
      else rowsBox.append(firstRun());
      return;
    }
    if (!rows.length) {
      rowsBox.append(emptyPage('today', 'No rows to show', 'Your enabled sources have no catalogs for Today, or all of them are hidden. Check Sources or Settings.',
        h('a', { class: 'btn pri', href: '#/sources', text: 'Open sources' })));
      return;
    }
    if (store.settings().hero) heroBox.append(h('div', { class: 'sk hero', 'aria-hidden': 'true' }));
    const heroKey = store.settings().heroCatalog && rows.find((r) => r.key === store.settings().heroCatalog) ? store.settings().heroCatalog : rows[0].key;
    let first = true;
    for (const r of rows) {
      const sec = rowSection(catalogTitle(r.cat), { allHref: href(`/browse/${store.sourceId(r.src)}/${enc(r.cat.type)}/${enc(r.cat.id)}`), sub: rowSubtitle(r.cat, r.src) });
      sec.el.hidden = false;
      rowsBox.append(sec.el);
      loadRow(r, sec, bypass, r.key === heroKey);
    }
  }

  /** Three short steps for someone with no source yet; the card can be dismissed for good (L078). */
  function firstRun() {
    const step = (n, title, text) => h('li', {}, h('span', { class: 'n', 'aria-hidden': 'true', text: String(n) }), h('div', {}, h('b', { text: title }), h('span', { text })));
    return h('section', { class: 'firstrun', 'aria-labelledby': 'fr-h' },
      h('h2', { id: 'fr-h', text: 'Start in three steps' }),
      h('ol', {},
        step(1, 'A source is a link', 'It points to an addon that lists titles and streams. Liuying does not host or provide any content, you bring your own.'),
        step(2, 'Add one', 'Paste its manifest link (it ends in /manifest.json). Catalogs appear here within seconds.'),
        step(3, 'Know who sees what', 'A source can see your IP address and what you ask it for, like any website. Liuying has no analytics, and your shelf and progress stay in this browser unless you sign in.')),
      h('div', { class: 'actions' }, h('a', { class: 'btn pri', href: '#/sources?add=1', text: 'Add a source' }), h('button', { class: 'btn', type: 'button', text: 'Skip for now', onClick: () => { store.setPref('dismissedGuide', true); build(); } })));
  }

  async function loadRow(r, sec, bypass, isHero) {
    const extra = defaultExtra(r.cat);
    const ck = r.key + JSON.stringify(extra || {});
    const cached = saved(ck);
    if (cached && !bypass) {
      // stale-while-revalidate: draw the saved copy now, refresh behind it only if it is old (L088)
      status[r.key] = 'ok';
      fill(cached.items);
      if (Date.now() - cached.at < TTL) return;
      try {
        const items = await loadCatalog(r.src, r.cat, extra, { signal: ctl.signal });
        remember(ck, items);
        if (!ctl.signal.aborted && !sameIds(items, cached.items)) fill(items);
      } catch { /* keep showing the saved copy */ }
      return;
    }
    showSkeletons(sec.scroller);
    try {
      const items = await loadCatalog(r.src, r.cat, extra, { signal: ctl.signal });
      remember(ck, items);
      status[r.key] = 'ok';
      fill(items);
    } catch (e) {
      if (ctl.signal.aborted) return;
      status[r.key] = 'err';
      if (isHero) clear(heroBox);
      clear(sec.scroller);
      sec.scroller.classList.remove('scroller');
      sec.scroller.replaceChildren(errorCard(`Couldn't load ${r.cat.name || r.cat.id}`, `${r.src.manifest.name}: ${describeError(e)}`, () => { sec.scroller.className = 'scroller'; loadRow(r, sec, true, isHero); }));
      checkAllFailed();
    }
    function fill(all) {
      if (ctl.signal.aborted) return;
      const items = visibleItems(all);
      clear(sec.scroller);
      sec.scroller.className = 'scroller';
      sec.el.hidden = !items.length;
      if (!items.length) return;
      sec.scroller.hidden = false;
      for (const it of items.slice(0, 30)) {
        const c = posterCard(it, { srcId: store.sourceId(r.src) });
        c.setAttribute('role', 'listitem');
        sec.scroller.append(c);
      }
      if (isHero) { const feat = store.settings().hero ? pickFeatured(items, 5) : []; heroCtl?.destroy(); heroCtl = null; if (feat.length) heroCtl = renderHero(heroBox, feat, { srcId: store.sourceId(r.src) }); else clear(heroBox); }
      restoreScroll();
    }
  }

  function checkAllFailed() {
    const vals = Object.values(status);
    const total = homeRows().length;
    if (vals.length === total && vals.every((v) => v === 'err')) {
      clear(failBanner).append(h('div', { class: 'state err', role: 'alert' }, icon('warn', 24),
        h('div', { class: 'grow' }, h('strong', { text: "Can't reach your sources" }), h('span', { class: 'mute', text: 'Every row failed to load. You may be offline, or your sources may not allow web access.' })),
        h('button', { class: 'btn sm', type: 'button', text: 'Retry', onClick: () => build(true) }),
        h('a', { class: 'btn sm', href: '#/sources', text: 'Check sources' })));
    }
  }

  build();
  return { destroy() { ctl.abort(); heroCtl?.destroy(); } };
}
