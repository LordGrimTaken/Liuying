// Dedicated Movies / Series pages: hero, genre tabs and rows from the first catalogs of that type.
import { h, icon, clear } from './dom.js';
import * as store from '../store.js';
import { catalogCapabilities, isHomeEligible, catalogTitle, isAdult } from '../addon/protocol.js';
import { describeError } from '../addon/client.js';
import { posterCard, rowSection, showSkeletons, errorCard, emptyPage } from './components.js';
import { renderHero } from './hero.js';
import { fetchRow, rowSubtitle, visibleItems } from './today.js';
import { href, enc, restoreScroll } from '../router.js';
import { pickFeatured } from '../ux.js';

const COPY = {
  movie: { path: '/movies', title: 'Movies', noun: 'movie', icon: 'film' },
  series: { path: '/series', title: 'Series', noun: 'series', icon: 'tv' },
};
const MAX_ROWS = 6;

export const movies = (ctx) => catalogPage('movie', ctx);
export const series = (ctx) => catalogPage('series', ctx);

/** Home-eligible catalogs of one type from every enabled source (adult sources hidden when asked). */
export function catalogsOfType(type) {
  const s = store.settings();
  const out = [];
  for (const src of store.enabledSources()) {
    if (isAdult(src.manifest) && s.hideAdult) continue;
    for (const cat of src.manifest.catalogs) {
      if (cat.type !== type || !isHomeEligible(cat)) continue;
      out.push({ src, cat, caps: catalogCapabilities(cat) });
    }
  }
  return out;
}

/** Distinct genre options across catalogs, in first-seen order. */
export function genresOf(cats) {
  const seen = new Set();
  for (const c of cats) if (c.caps.genre) for (const g of c.caps.genreOptions) seen.add(g);
  return [...seen];
}

function catalogPage(type, { query = {} } = {}) {
  const info = COPY[type];
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  const heroBox = h('div', { class: 'hero-box' });
  const tabsBox = h('div', { class: 'gtabs', role: 'tablist', 'aria-label': `${info.title} genres` });
  const rowsBox = h('div', { class: 'rows-box' });
  page.append(h('div', { class: 'page-h sr' }, h('h1', { text: info.title })));
  let heroCtl = null;

  const cats = catalogsOfType(type);
  if (!store.sources().length) {
    page.append(emptyPage(info.icon, 'Your scroll is blank', 'Add a source and its catalogs will appear here.', h('a', { class: 'btn pri', href: '#/sources?add=1', text: 'Add a source' })));
    return {};
  }
  if (!cats.length) {
    page.append(emptyPage(info.icon, `No ${info.title.toLowerCase()} catalogs`, `None of your enabled sources offer ${info.title.toLowerCase()} for browsing. Add or enable another source.`, h('a', { class: 'btn pri', href: '#/sources', text: 'Open sources' })));
    return {};
  }
  const genres = genresOf(cats);
  let genre = genres.includes(query.genre) ? query.genre : '';
  page.append(heroBox);
  if (genres.length) page.append(tabsBox);
  page.append(rowsBox);
  if (store.settings().hero) heroBox.append(h('div', { class: 'sk hero', 'aria-hidden': 'true' }));

  function drawTabs() {
    clear(tabsBox);
    for (const g of ['', ...genres]) {
      const b = h('button', { class: 'gtab', type: 'button', role: 'tab', 'aria-selected': String(g === genre), tabindex: g === genre ? '0' : '-1', onClick: () => setGenre(g) }, g || 'All');
      tabsBox.append(b);
    }
    tabsBox.querySelector('[aria-selected="true"]')?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }
  function setGenre(g) {
    if (g === genre) return;
    genre = g;
    try { history.replaceState(null, '', href(info.path, genre ? { genre } : undefined)); } catch { /* ignore */ }
    drawTabs();
    buildRows();
  }
  tabsBox.addEventListener('keydown', (e) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    const all = ['', ...genres];
    const k = (all.indexOf(genre) + (e.key === 'ArrowRight' ? 1 : all.length - 1)) % all.length;
    setGenre(all[k]);
    tabsBox.querySelector('[aria-selected="true"]')?.focus();
  });

  let gen = 0, heroDone = false, pending = 0;
  const settle = () => { if (--pending <= 0 && !heroDone && !heroBox.querySelector('.hero')) clear(heroBox); };
  function buildRows() {
    gen++;
    const mine = gen;
    clear(rowsBox);
    pending = 0;
    const list = (genre ? cats.filter((c) => c.caps.genre && c.caps.genreOptions.includes(genre)) : cats).slice(0, MAX_ROWS);
    if (!list.length) { rowsBox.append(emptyPage(info.icon, 'Nothing here yet', `No catalog offers ${genre}.`)); return; }
    for (const { src, cat, caps } of list) {
      const extra = genre ? { genre } : caps.genreRequired ? { genre: caps.genreOptions[0] } : undefined;
      const title = catalogTitle(cat) + (genre ? ` · ${genre}` : '');
      const sec = rowSection(title, { allHref: href(`/browse/${store.sourceId(src)}/${enc(cat.type)}/${enc(cat.id)}`, genre ? { genre } : undefined), sub: rowSubtitle(cat, src) });
      rowsBox.append(sec.el);
      load();
      async function load(bypass = false) {
        pending++;
        showSkeletons(sec.scroller);
        try {
          const items = visibleItems(await fetchRow(src, cat, extra, { bypass, signal: ctl.signal }));
          if (ctl.signal.aborted || mine !== gen) return;
          clear(sec.scroller);
          sec.scroller.className = 'scroller';
          if (!items.length) { sec.el.hidden = true; settle(); return; }
          for (const it of items.slice(0, 30)) { const c = posterCard(it, { srcId: store.sourceId(src) }); c.setAttribute('role', 'listitem'); sec.scroller.append(c); }
          if (!heroDone) {
            heroDone = true;
            const feat = store.settings().hero ? pickFeatured(items, 5) : [];
            if (feat.length) heroCtl = renderHero(heroBox, feat, { srcId: store.sourceId(src) }); else clear(heroBox);
          }
          restoreScroll();
          settle();
        } catch (e) {
          if (ctl.signal.aborted || mine !== gen) return;
          sec.scroller.classList.remove('scroller');
          sec.scroller.replaceChildren(errorCard(`Couldn't load ${cat.name || cat.id}`, `${src.manifest.name}: ${describeError(e)}`, () => { sec.scroller.className = 'scroller'; load(true); }));
          settle();
        }
      }
    }
  }

  drawTabs();
  buildRows();
  return { destroy() { ctl.abort(); heroCtl?.destroy(); } };
}
