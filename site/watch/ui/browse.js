import { h, icon, clear, keepFocus } from './dom.js';
import * as store from '../store.js';
import { catalogCapabilities, catalogTitle } from '../addon/protocol.js';
import { loadCatalog } from '../addon/service.js';
import { describeError } from '../addon/client.js';
import { posterCard, errorCard, posterSkeleton, emptyState } from './components.js';
import { uniqBy } from '../util.js';
import { goBack } from '../router.js';
import { catalogKey } from '../addon/protocol.js';

export function browse({ params, query }) {
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const src = store.sourceById(params.sid);
  const cat = src?.manifest?.catalogs.find((c) => c.type === params.type && c.id === params.cid);
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  if (!src || !cat) {
    page.append(emptyState('Catalog not found', 'This source or catalog is no longer available.', h('a', { class: 'btn pri', href: '#/', text: 'Back to Today' })));
    return {};
  }
  const caps = catalogCapabilities(cat);
  let genre = query.genre || (caps.genreRequired ? caps.genreOptions[0] : '');
  let items = [];
  let end = false, loading = false, generation = 0;
  const grid = h('div', { class: 'grid', role: 'list' });
  const foot = h('div', { class: 'foot' });
  const chips = h('div', { class: 'chips', role: 'group', 'aria-label': 'Genre filter' });
  page.append(h('div', { class: 'page-h' },
    h('a', { class: 'ibtn ring', href: '#/', 'aria-label': 'Back', onClick: (e) => { e.preventDefault(); goBack('#/'); } }, icon('back')),
    h('h1', { text: cat.name || cat.id }), h('span', { class: 'badge', text: src.manifest.name })),
    h('p', { class: 'mute', text: catalogTitle(cat) }));
  const hideBtn = h('button', { class: 'chip', type: 'button', 'aria-pressed': String(!!store.settings().hideWatched), title: 'Leave out titles you have finished', onClick: () => { const on = !store.settings().hideWatched; store.setSettings({ hideWatched: on }); hideBtn.setAttribute('aria-pressed', String(on)); redraw(); } }, 'Hide watched');
  page.append(h('div', { class: 'chips browse-tools', role: 'group', 'aria-label': 'Display' }, hideBtn));
  if (caps.genre && caps.genreOptions.length) page.append(chips);
  page.append(grid, foot);

  function drawChips() { keepFocus(chips, drawChipsNow); }
  function drawChipsNow() {
    clear(chips);
    if (!caps.genreRequired) chips.append(h('button', { class: 'chip', type: 'button', 'aria-pressed': String(!genre), onClick: () => setGenre('') }, 'All'));
    for (const g of caps.genreOptions) chips.append(h('button', { class: 'chip', type: 'button', 'aria-pressed': String(g === genre), onClick: () => setGenre(g) }, g));
  }
  function setGenre(g) { genre = g; reset(); drawChips(); pump(); }
  function redraw() { rendered = 0; clear(grid); clear(foot); pump(); }
  function reset() { generation++; items = []; rendered = 0; end = false; loading = false; clear(grid); clear(foot); }

  // Cards are built a screenful at a time as the viewer scrolls (catalogs can hold thousands of titles),
  // and the next page is only fetched once every title already received has been drawn.
  const CHUNK = 60;
  let rendered = 0, pumping = false, io;
  const frame = () => new Promise((r) => requestAnimationFrame(r));
  const nearFoot = () => foot.getBoundingClientRect().top < innerHeight + 700;
  function drawChunk() {
    const sid = store.sourceId(src);
    const hide = store.settings().hideWatched;
    for (const it of items.slice(rendered, rendered + CHUNK)) { if (hide && store.getProgress(it.id, null)?.watched) continue; const c = posterCard(it, { srcId: sid }); c.setAttribute('role', 'listitem'); grid.append(c); }
    rendered = Math.min(items.length, rendered + CHUNK);
  }
  function endNote() {
    clear(foot);
    if (!grid.children.length && items.length) foot.append(emptyState('Everything here is watched', 'Turn off Hide watched to see these titles again.', h('button', { class: 'btn', type: 'button', text: 'Show watched titles', onClick: () => { store.setSettings({ hideWatched: false }); hideBtn.setAttribute('aria-pressed', 'false'); redraw(); } })));
    else if (!items.length) foot.append(emptyState('No titles for this filter', 'Nothing came back for this selection.', genre && !caps.genreRequired ? h('button', { class: 'btn', type: 'button', text: 'Clear filter', onClick: () => setGenre('') }) : null));
    else foot.append(h('p', { class: 'mute end-note', text: `— End of list · ${items.length} titles —` }));
  }
  async function pump() {
    if (pumping) return;
    pumping = true;
    const gen = generation;
    try {
      while (gen === generation && nearFoot()) {
        if (rendered < items.length) { drawChunk(); await frame(); continue; }
        if (end) { endNote(); return; }
        if (loading || foot.querySelector('.state.err')) return;
        await fetchMore();
      }
    } finally { pumping = false; }
  }
  async function fetchMore() {
    if (loading || end) return;
    loading = true;
    const gen = generation;
    clear(foot);
    if (!items.length) for (let i = 0; i < 12; i++) grid.append(posterSkeleton());
    else foot.append(h('div', { class: 'progress-line' }, h('span', { class: 'spin' }), 'Loading more…'));
    try {
      const extra = {};
      if (genre) extra.genre = genre;
      if (items.length && caps.skip) extra.skip = items.rawCount || items.length;
      const first = !items.length && !extra.skip;
      const ck = 'browse:' + catalogKey(src.url, cat) + JSON.stringify(extra);
      const hit = first ? store.cachedCatalog(ck) : null;
      let batch;
      if (hit && Date.now() - hit.at < 15 * 60e3) batch = hit.items; // page one again within 15 minutes comes from the cache (L089)
      else {
        batch = await loadCatalog(src, cat, extra, { signal: ctl.signal });
        if (first && batch.length <= 200) store.cacheCatalog(ck, batch, 200);
      }
      if (gen !== generation) return;
      if (!items.length) clear(grid);
      items.rawCount = (items.rawCount || 0) + batch.length;
      const raw = items.rawCount;
      const before = items.length;
      items = uniqBy([...items, ...batch], (m) => m.id);
      items.rawCount = raw;
      clear(foot);
      if (!batch.length || !caps.skip || items.length === before) end = true;
    } catch (e) {
      if (gen !== generation || ctl.signal.aborted) return;
      if (!items.length) clear(grid);
      clear(foot).append(errorCard(items.length ? "Couldn't load more" : "Couldn't load this catalog", describeError(e), () => { clear(foot); pump(); }));
    } finally {
      if (gen === generation) loading = false;
    }
  }
  io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting)) pump(); }, { rootMargin: '700px' });
  io.observe(foot);
  drawChips();
  pump();
  return { destroy() { ctl.abort(); io.disconnect(); } };
}
