import { h, icon, clear, iconBtn } from './dom.js';
import * as store from '../store.js';
import { catalogCapabilities, isHomeEligible, catalogKey, catalogTitle, isAdult } from '../addon/protocol.js';
import { loadCatalog } from '../addon/service.js';
import { describeError } from '../addon/client.js';
import { posterCard, rowSection, showSkeletons, errorCard, emptyState, artImg, titleHref, toggleLibrary } from './components.js';
import { renderContinueRow } from './continue.js';
import { href, enc, restoreScroll } from '../router.js';
import { imgUrl, yearOf } from '../util.js';

const cache = new Map(); // key -> {items, at}
const TTL = 5 * 60e3;

export const defaultExtra = (cat) => {
  const c = catalogCapabilities(cat);
  return c.genreRequired && c.genreOptions.length ? { genre: c.genreOptions[0] } : undefined;
};

export function homeRows() {
  const s = store.settings();
  const rows = [];
  for (const src of store.enabledSources()) {
    if (isAdult(src.manifest) && s.hideAdult) continue;
    for (const cat of src.manifest.catalogs) {
      if (!isHomeEligible(cat)) continue;
      const key = catalogKey(src.url, cat);
      if (s.hiddenCatalogs?.[key]) continue;
      rows.push({ src, cat, key });
    }
  }
  return rows;
}

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
  page.append(h('div', { class: 'page-h' }, h('div', { class: 'grow brandline' }, h('span', { class: 'seal sm', 'aria-hidden': 'true' }, '流', h('br'), '影'), h('h1', { text: 'Today' })), refreshBtn),
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
    const heroKey = store.settings().heroCatalog && rows.find((r) => r.key === store.settings().heroCatalog) ? store.settings().heroCatalog : rows[0].key;
    let first = true;
    for (const r of rows) {
      const sec = rowSection(catalogTitle(r.cat), { allHref: href(`/browse/${store.sourceId(r.src)}/${enc(r.cat.type)}/${enc(r.cat.id)}`) });
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
      if (isHero && store.settings().hero) renderHero(items.filter((i) => i.background || i.poster).slice(0, 5), r);
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
    if (!items.length) return;
    clear(heroBox);
    let i = 0;
    const bgs = items.map((it, k) => { const im = artImg(it.background || it.poster); if (im) { im.className = 'bg' + (k === 0 ? ' on' : ''); im.alt = ''; im.loading = 'eager'; } return im; });
    const copy = h('div', { class: 'copy' });
    const dots = h('div', { class: 'dots' }, items.map((_, k) => h('button', { type: 'button', 'aria-label': `Show featured title ${k + 1}`, onClick: () => { show(k); stopAuto(); } })));
    const hero = h('section', { class: 'hero', 'aria-roledescription': 'carousel', 'aria-label': 'Featured' }, bgs, copy, dots);
    heroBox.append(hero);
    function show(k) {
      i = k;
      const it = items[k];
      bgs.forEach((b, j) => b && b.classList.toggle('on', j === k));
      [...dots.children].forEach((d, j) => d.setAttribute('aria-current', j === k ? 'true' : 'false'));
      const inLib = store.inLibrary(it.id);
      const genres = (it.genres || it.genre || []).slice?.(0, 3) || [];
      clear(copy).append(
        h('p', { class: 'eyebrow', text: 'Featured' }),
        h('h2', { text: it.name || it.id }),
        h('div', { class: 'meta' }, yearOf(it) ? h('span', { text: yearOf(it) }) : null, it.imdbRating ? h('span', { class: 'rate', text: `★ ${it.imdbRating}` }) : null, ...genres.map((g) => h('span', { text: g }))),
        it.description ? h('p', { class: 'desc', text: it.description }) : null,
        h('div', { class: 'acts' },
          h('a', { class: 'btn pri', href: titleHref(it, store.sourceId(r.src)) }, icon('play', 18), 'Play / More info'),
          h('button', { class: 'btn', type: 'button', 'aria-pressed': String(inLib), onClick: () => { toggleLibrary(it); show(i); } }, icon(inLib ? 'bookmarkOn' : 'bookmark', 18), inLib ? 'On shelf' : 'Add to shelf')));
    }
    const stopAuto = () => clearInterval(timer);
    show(0);
    const reduce = store.settings().reduceMotion || matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (items.length > 1 && !reduce) {
      timer = setInterval(() => show((i + 1) % items.length), 8000);
      hero.addEventListener('mouseenter', stopAuto); hero.addEventListener('focusin', stopAuto);
    }
  }

  build();
  return { destroy() { ctl.abort(); clearInterval(timer); } };
}
