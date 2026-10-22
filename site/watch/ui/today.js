import { h, icon, clear, iconBtn } from './dom.js';
import * as store from '../store.js';
import { catalogCapabilities, isHomeEligible, catalogKey, catalogTitle, isAdult } from '../addon/protocol.js';
import { loadCatalog } from '../addon/service.js';
import { describeError } from '../addon/client.js';
import { posterCard, rowSection, showSkeletons, errorCard, emptyState, artImg, titleHref, toggleLibrary } from './components.js';
import { renderContinueRow, streamsHref } from './continue.js';
import { href, enc, restoreScroll } from '../router.js';
import { imgUrl, yearOf, typeLabel } from '../util.js';
import { pickFeatured, orderRows } from '../ux.js';
import { openSearch } from './searchOverlay.js';

const cache = new Map(); // key -> {items, at}
const TTL = 5 * 60e3;

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

export function today() {
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  let timer = null;
  const heroBox = h('div', { class: 'hero-box' });
  const contBox = h('div', { class: 'cont-box' });
  const rowsBox = h('div', { class: 'rows-box' });
  const failBanner = h('div', { class: 'fail-box' });
  const refreshBtn = h('button', { class: 'btn sm', type: 'button', onClick: () => build(true) }, icon('refresh', 18), 'Refresh');
  page.append(h('div', { class: 'page-h' }, h('div', { class: 'grow brandline' }, h('span', { class: 'seal sm', 'aria-hidden': 'true' }, '流', h('br'), '影'), h('h1', { text: 'Today' })), h('button', { class: 'ibtn ring', type: 'button', 'aria-label': 'Search', title: 'Search ( / )', onClick: () => openSearch() }, icon('search', 20)), refreshBtn),
    failBanner, heroBox, contBox, rowsBox);

  let status = {};
  function build(bypass = false) {
    clearInterval(timer);
    clear(heroBox); clear(rowsBox); clear(failBanner);
    status = {};
    const rows = homeRows();
    renderContinueRow(contBox, ctl.signal, { onChange: () => renderContinueRow(contBox, ctl.signal) });
    if (!store.sources().length) {
      clear(contBox);
      rowsBox.append(emptyState('Your scroll is blank', 'Liuying shows what your own sources provide. Add a source with its manifest link and your catalogs will appear here.',
        h('a', { class: 'btn pri', href: '#/sources?add=1', text: 'Add a source' })));
      return;
    }
    if (!rows.length) {
      rowsBox.append(emptyState('No rows to show', 'Your enabled sources have no catalogs for Today, or all of them are hidden. Check Sources or Settings.',
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

  async function loadRow(r, sec, bypass, isHero) {
    const extra = defaultExtra(r.cat);
    const ck = r.key + JSON.stringify(extra || {});
    const cached = cache.get(ck);
    if (cached && !bypass && Date.now() - cached.at < TTL) { fill(cached.items); return; }
    showSkeletons(sec.scroller);
    try {
      const items = await loadCatalog(r.src, r.cat, extra, { signal: ctl.signal });
      cache.set(ck, { items, at: Date.now() });
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
    function fill(items) {
      if (ctl.signal.aborted) return;
      clear(sec.scroller);
      sec.scroller.className = 'scroller';
      if (!items.length) { sec.el.hidden = true; return; }
      sec.scroller.hidden = false;
      for (const it of items.slice(0, 30)) {
        const c = posterCard(it, { srcId: store.sourceId(r.src) });
        c.setAttribute('role', 'listitem');
        sec.scroller.append(c);
      }
      if (isHero) { const feat = store.settings().hero ? pickFeatured(items, 5) : []; if (feat.length) renderHero(feat, r); else clear(heroBox); }
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

  function renderHero(items, r) {
    clear(heroBox);
    let i = 0;
    let paused = false;
    const sid = store.sourceId(r.src);
    const bgs = items.map((it, k) => {
      const useBg = !!it.background;
      const im = artImg(it.background || it.poster);
      if (im) { im.className = 'bg' + (useBg ? '' : ' fromposter') + (k === 0 ? ' on' : ''); im.alt = ''; im.loading = 'eager'; }
      return im;
    });
    const copy = h('div', { class: 'copy', 'aria-live': 'off' });
    const pauseBtn = h('button', { class: 'ibtn hbtn', type: 'button', 'aria-label': 'Pause rotation', onClick: () => { paused = !paused; syncPause(); } });
    const dots = h('div', { class: 'dots' }, items.map((_, k) => h('button', { type: 'button', 'aria-label': `Show featured title ${k + 1} of ${items.length}`, onClick: () => { show(k); } })));
    const ctrls = h('div', { class: 'hctl' }, items.length > 1 ? pauseBtn : null, items.length > 1 ? dots : null);
    const hero = h('section', { class: 'hero', 'aria-roledescription': 'carousel', 'aria-label': 'Featured titles' }, bgs, copy, ctrls);
    heroBox.append(hero);
    function syncPause() {
      pauseBtn.replaceChildren(icon(paused ? 'play' : 'pause', 18));
      pauseBtn.setAttribute('aria-label', paused ? 'Resume rotation' : 'Pause rotation');
    }
    function show(k) {
      i = k;
      const it = items[k];
      bgs.forEach((b, j) => b && b.classList.toggle('on', j === k));
      [...dots.children].forEach((d, j) => d.setAttribute('aria-current', j === k ? 'true' : 'false'));
      const inLib = store.inLibrary(it.id);
      const genres = (Array.isArray(it.genres) ? it.genres : Array.isArray(it.genre) ? it.genre : []).slice(0, 3);
      const more = titleHref(it, sid);
      const type = it.type || 'movie';
      clear(copy).append(
        h('p', { class: 'eyebrow', text: `Featured · ${k + 1} of ${items.length}` }),
        h('h2', { text: it.name || it.id }),
        h('div', { class: 'chipline' },
          it.imdbRating ? h('span', { class: 'hchip rate' }, icon('star', 13), String(it.imdbRating)) : null,
          yearOf(it) ? h('span', { class: 'hchip', text: yearOf(it) }) : null,
          h('span', { class: 'hchip', text: typeLabel(type) }),
          ...genres.map((g) => h('span', { class: 'hchip', text: g }))),
        it.description ? h('p', { class: 'desc', text: it.description }) : null,
        h('div', { class: 'acts' },
          h('a', { class: 'btn pri', href: type === 'series' ? href(`/title/${enc(type)}/${enc(it.id)}`, { src: sid, play: 1 }) : streamsHref(type, it.id) }, icon('play', 18), 'Play'),
          h('a', { class: 'btn', href: more }, icon('info', 18), 'More info'),
          h('button', { class: 'ibtn ring', type: 'button', 'aria-pressed': String(inLib), 'aria-label': inLib ? `Remove ${it.name} from shelf` : `Add ${it.name} to shelf`, title: inLib ? 'On your shelf' : 'Add to shelf', onClick: () => { toggleLibrary(it); show(i); } }, icon(inLib ? 'check' : 'plus', 20))));
    }
    show(0);
    syncPause();
    const reduce = store.settings().reduceMotion || matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (items.length > 1 && !reduce) {
      let hover = false;
      timer = setInterval(() => { if (!paused && !hover) show((i + 1) % items.length); }, 8000);
      hero.addEventListener('mouseenter', () => { hover = true; }); hero.addEventListener('mouseleave', () => { hover = false; });
      hero.addEventListener('focusin', () => { paused = true; syncPause(); });
    } else paused = true, syncPause();
  }

  build();
  return { destroy() { ctl.abort(); clearInterval(timer); } };
}
