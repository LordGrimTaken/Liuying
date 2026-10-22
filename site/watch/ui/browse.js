import { h, icon, clear } from './dom.js';
import * as store from '../store.js';
import { catalogCapabilities, catalogTitle } from '../addon/protocol.js';
import { loadCatalog } from '../addon/service.js';
import { describeError } from '../addon/client.js';
import { posterCard, errorCard, posterSkeleton, emptyState } from './components.js';
import { uniqBy } from '../util.js';

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
    h('a', { class: 'ibtn ring', href: '#/', 'aria-label': 'Back to Today' }, icon('back')),
    h('h1', { text: cat.name || cat.id }), h('span', { class: 'badge', text: src.manifest.name })),
    h('p', { class: 'mute', text: catalogTitle(cat) }));
  if (caps.genre && caps.genreOptions.length) page.append(chips);
  page.append(grid, foot);

  function drawChips() {
    clear(chips);
    if (!caps.genreRequired) chips.append(h('button', { class: 'chip', type: 'button', 'aria-pressed': String(!genre), onClick: () => setGenre('') }, 'All'));
    for (const g of caps.genreOptions) chips.append(h('button', { class: 'chip', type: 'button', 'aria-pressed': String(g === genre), onClick: () => setGenre(g) }, g));
  }
  function setGenre(g) { genre = g; reset(); drawChips(); more(); }
  function reset() { generation++; items = []; end = false; loading = false; clear(grid); clear(foot); }

  let io;
  async function more() {
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
      const batch = await loadCatalog(src, cat, extra, { signal: ctl.signal });
      if (gen !== generation) return;
      if (!items.length) clear(grid);
      items.rawCount = (items.rawCount || 0) + batch.length;
      const raw = items.rawCount;
      const before = items.length;
      items = uniqBy([...items, ...batch], (m) => m.id);
      items.rawCount = raw;
      for (const it of items.slice(before)) { const c = posterCard(it, { srcId: store.sourceId(src) }); c.setAttribute('role', 'listitem'); grid.append(c); }
      clear(foot);
      if (!batch.length || !caps.skip || items.length === before) {
        end = true;
        if (!items.length) foot.append(emptyState('No titles for this filter', 'Nothing came back for this selection.', genre && !caps.genreRequired ? h('button', { class: 'btn', type: 'button', text: 'Clear filter', onClick: () => setGenre('') }) : null));
        else foot.append(h('p', { class: 'mute', style: { 'text-align': 'center', padding: '20px' }, text: '— End of list —' }));
      }
    } catch (e) {
      if (gen !== generation || ctl.signal.aborted) return;
      if (!items.length) clear(grid);
      clear(foot).append(errorCard(items.length ? "Couldn't load more" : "Couldn't load this catalog", describeError(e), () => { loading = false; more(); }));
    } finally { if (gen === generation) loading = false; }
  }
  io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting)) more(); }, { rootMargin: '600px' });
  io.observe(foot);
  drawChips();
  more();
  return { destroy() { ctl.abort(); io.disconnect(); } };
}
