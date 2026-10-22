import { h, icon, clear } from './dom.js';
import * as store from '../store.js';
import { catalogTitle } from '../addon/protocol.js';
import { loadCatalog } from '../addon/service.js';
import { describeError, searchableCatalogs } from '../addon/client.js';
import { posterCard, rowSection, showSkeletons, errorCard, emptyState } from './components.js';
import { debounce } from '../util.js';
import { navigate } from '../router.js';

export function search({ query }) {
  const view = document.getElementById('view');
  let ctl = null;
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  const input = h('input', { class: 'inp', type: 'search', id: 'q', placeholder: 'Search titles across your sources', autocomplete: 'off', 'aria-label': 'Search', value: query.q || '' });
  const clr = h('button', { class: 'ibtn clr', type: 'button', 'aria-label': 'Clear search', hidden: true, onClick: () => { input.value = ''; onInput(); input.focus(); } }, icon('close', 18));
  const typeBox = h('div', { class: 'chips', role: 'group', 'aria-label': 'Type filter' });
  const results = h('div', { class: 'results', 'aria-live': 'polite' });
  const recent = h('div', { class: 'recent' });
  page.append(h('div', { class: 'page-h' }, h('h1', { text: 'Search' })),
    h('form', { class: 'searchbar', role: 'search', onSubmit: (e) => { e.preventDefault(); run(true); } }, h('span', { class: 'ic' }, icon('search', 20)), input, clr),
    h('div', { style: { height: '12px' } }), typeBox, recent, results);
  let typeFilter = 'all';
  let sections = [];

  function drawTypes() {
    const types = ['all', ...new Set(sections.map((s) => s.cat.type))];
    clear(typeBox);
    if (types.length < 3) return;
    for (const t of types) typeBox.append(h('button', { class: 'chip', type: 'button', 'aria-pressed': String(typeFilter === t), onClick: () => { typeFilter = t; applyFilter(); drawTypes(); } }, t === 'all' ? 'All' : ({ movie: 'Movies', series: 'Series' }[t] || t)));
  }
  function applyFilter() { for (const s of sections) s.sec.el.hidden = typeFilter !== 'all' && s.cat.type !== typeFilter || s.empty; }

  function drawRecent() {
    clear(recent);
    const q = input.value.trim();
    if (q.length >= 2 || !store.settings().recentSearches) return;
    const list = store.recentSearches();
    if (!list.length) { if (!results.childNodes.length) recent.append(h('p', { class: 'mute', text: 'Type at least two letters. Results come from every enabled source that supports search.' })); return; }
    recent.append(h('div', { class: 'row-h' }, h('h2', { text: 'Recent searches' }), h('button', { class: 'linkbtn', type: 'button', text: 'Clear all', onClick: () => { store.clearRecent(); drawRecent(); } })),
      h('div', { class: 'chips' }, list.map((q) => h('span', { class: 'chip', style: { display: 'inline-flex', gap: '6px', alignItems: 'center' } },
        h('button', { type: 'button', text: q, onClick: () => { input.value = q; run(true); } }),
        h('button', { type: 'button', 'aria-label': `Remove ${q}`, onClick: () => { store.removeRecent(q); drawRecent(); } }, icon('close', 14))))));
  }

  function run(immediate) {
    const q = input.value.trim();
    clr.hidden = !input.value;
    ctl?.abort();
    clear(results); sections = []; clear(typeBox);
    if (q.length < 2) { drawRecent(); return; }
    clear(recent);
    ctl = new AbortController();
    const signal = ctl.signal;
    history.replaceState(null, '', '#/search?q=' + encodeURIComponent(q));
    if (store.settings().recentSearches && immediate) store.pushRecent(q);
    const targets = searchableCatalogs(store.sources().filter((s) => !(s.manifest?.behaviorHints?.adult) || !store.settings().hideAdult));
    if (!targets.length) {
      results.append(emptyState(store.sources().length ? 'No source supports search' : 'No sources yet', store.sources().length ? 'None of your enabled sources offers a searchable catalog.' : 'Add a source to search.', h('a', { class: 'btn pri', href: '#/sources', text: 'Open sources' })));
      return;
    }
    let pending = targets.length, found = 0;
    const live = h('p', { class: 'progress-line' }, h('span', { class: 'spin' }), `Searching ${pending} catalog${pending > 1 ? 's' : ''}…`);
    results.append(live);
    for (const t of targets) {
      const sec = rowSection(catalogTitle(t.cat), { sub: t.source.manifest.name });
      showSkeletons(sec.scroller, 6);
      const entry = { ...t, sec, empty: false };
      sections.push(entry);
      results.append(sec.el);
      const go = async () => {
        showSkeletons(sec.scroller, 6);
        try {
          const items = await loadCatalog(t.source, t.cat, { search: q }, { signal });
          if (signal.aborted) return;
          clear(sec.scroller);
          if (!items.length) { entry.empty = true; sec.el.hidden = true; } else {
            found += items.length;
            for (const it of items.slice(0, 40)) { const c = posterCard(it, { srcId: store.sourceId(t.source) }); c.setAttribute('role', 'listitem'); sec.scroller.append(c); }
          }
        } catch (e) {
          if (signal.aborted) return;
          sec.scroller.className = '';
          sec.scroller.replaceChildren(errorCard('This source failed', describeError(e), () => { sec.scroller.className = 'scroller'; go(); }));
        } finally {
          if (signal.aborted) return;
          pending--;
          drawTypes(); applyFilter();
          if (pending <= 0) {
            live.remove();
            if (!found && !results.querySelector('.state.err')) results.append(emptyState(`No results for “${q}”`, 'Check the spelling, or add a source that supports search.'));
          } else live.lastChild.textContent = `Searching ${pending} catalog${pending > 1 ? 's' : ''}…`;
        }
      };
      go();
    }
  }
  const onInput = debounce(() => run(false), 400);
  input.addEventListener('input', () => { clr.hidden = !input.value; onInput(); });
  drawRecent();
  if (query.q) run(false); else setTimeout(() => input.focus(), 50);
  return { destroy() { ctl?.abort(); onInput.cancel(); } };
}
