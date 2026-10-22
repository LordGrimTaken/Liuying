// Full-screen search overlay: opens with "/" or any search control, closes with Esc.
import { h, icon, clear, trapFocus } from './dom.js';
import * as store from '../store.js';
import { catalogTitle } from '../addon/protocol.js';
import { loadCatalog } from '../addon/service.js';
import { describeError, searchableCatalogs } from '../addon/client.js';
import { posterCard, rowSection, showSkeletons, errorCard, emptyState } from './components.js';
import { debounce } from '../util.js';
import { matchesType, typeFilters, typeFilterLabel, suggest } from '../ux.js';
import { cachedTitles, rowSubtitle } from './today.js';
import { currentRoute, navigate } from '../router.js';

let open = null; // {close}

export const isSearchOpen = () => !!open;

export function openSearch(q = '') {
  if (open) { open.setQuery?.(q); return open; }
  const prevFocus = document.activeElement;
  let ctl = null;
  let typeFilter = 'all';
  let sections = [];
  let lastRun = '';

  const input = h('input', { class: 'inp', type: 'search', id: 'so-q', placeholder: 'Search titles across your sources', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', enterkeyhint: 'search', 'aria-label': 'Search', 'aria-controls': 'so-body', value: q });
  const clr = h('button', { class: 'ibtn clr', type: 'button', 'aria-label': 'Clear search', hidden: true, onClick: () => { input.value = ''; onInput(); input.focus(); } }, icon('close', 18));
  const closeBtn = h('button', { class: 'btn sm so-close', type: 'button', 'aria-label': 'Close search', onClick: () => close() }, icon('close', 16), h('span', { text: 'Close' }), h('kbd', { text: 'Esc' }));
  const typeBox = h('div', { class: 'chips', role: 'group', 'aria-label': 'Filter by type' });
  const sugBox = h('div', { class: 'sugs', role: 'listbox', 'aria-label': 'Suggestions' });
  const recent = h('div', { class: 'recent' });
  const results = h('div', { class: 'results', 'aria-live': 'polite' });
  const body = h('div', { class: 'so-body', id: 'so-body' }, sugBox, recent, results);
  const form = h('form', { class: 'searchbar', role: 'search', onSubmit: (e) => { e.preventDefault(); run(true); } }, h('span', { class: 'ic' }, icon('search', 20)), input, clr);
  const ov = h('div', { class: 'so', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Search' },
    h('div', { class: 'so-in' }, h('div', { class: 'so-top' }, form, closeBtn), typeBox, body));
  const shell = document.getElementById('shell');

  function drawTypes() {
    const types = typeFilters(sections.map((s) => s.cat.type));
    clear(typeBox);
    for (const t of types) typeBox.append(h('button', { class: 'chip', type: 'button', 'aria-pressed': String(typeFilter === t), onClick: () => { typeFilter = t; applyFilter(); drawTypes(); } }, typeFilterLabel(t)));
  }
  function applyFilter() {
    let shown = 0;
    for (const s of sections) { const hide = !matchesType(s.cat.type, typeFilter) || s.empty; s.sec.el.hidden = hide; if (!hide) shown++; }
    results.querySelector('.nofilter')?.remove();
    if (sections.length && sections.every((s) => s.done) && !shown && sections.some((s) => !s.empty)) {
      results.append(h('p', { class: 'mute nofilter', text: `No ${typeFilterLabel(typeFilter).toLowerCase()} results. Try All.` }));
    }
  }

  function drawSuggestions() {
    clear(sugBox);
    const v = input.value.trim();
    if (!v || v.toLowerCase() === lastRun.toLowerCase()) return;
    const pools = [store.settings().recentSearches ? store.recentSearches() : [], store.library().map((i) => i.name), cachedTitles()];
    for (const s of suggest(v, pools, 5)) {
      sugBox.append(h('button', { class: 'sug', type: 'button', role: 'option', onClick: () => { input.value = s; run(true); input.focus(); } }, icon('search', 16), h('span', { text: s })));
    }
  }

  function drawRecent() {
    clear(recent);
    const v = input.value.trim();
    if (v || !store.settings().recentSearches) return;
    const list = store.recentSearches();
    if (!list.length) { if (!results.childNodes.length) recent.append(h('p', { class: 'mute', text: 'Type at least two letters. Results come from every enabled source that supports search.' })); return; }
    recent.append(h('div', { class: 'row-h' }, h('div', { class: 'ht' }, h('h2', { text: 'Recent searches' })), h('button', { class: 'linkbtn', type: 'button', text: 'Clear all', onClick: () => { store.clearRecent(); drawRecent(); } })),
      h('div', { class: 'chips wrap' }, list.map((s) => h('span', { class: 'chip rc' },
        h('button', { type: 'button', text: s, onClick: () => { input.value = s; run(true); } }),
        h('button', { type: 'button', 'aria-label': `Remove ${s} from recent searches`, onClick: () => { store.removeRecent(s); drawRecent(); } }, icon('close', 14))))));
  }

  function run(immediate) {
    const v = input.value.trim();
    clr.hidden = !input.value;
    ctl?.abort();
    clear(results); sections = []; lastRun = v;
    if (v.length < 2) { lastRun = ''; drawTypes(); drawRecent(); drawSuggestions(); return; }
    clear(recent); clear(sugBox);
    ctl = new AbortController();
    const signal = ctl.signal;
    if (store.settings().recentSearches && immediate) store.pushRecent(v);
    const targets = searchableCatalogs(store.sources().filter((s) => !(s.manifest?.behaviorHints?.adult) || !store.settings().hideAdult));
    if (!targets.length) {
      results.append(emptyState(store.sources().length ? 'No source supports search' : 'No sources yet', store.sources().length ? 'None of your enabled sources offers a searchable catalog.' : 'Add a source to search.', h('a', { class: 'btn pri', href: '#/sources', onClick: () => close(true), text: 'Open sources' })));
      return;
    }
    let pending = targets.length, found = 0;
    const live = h('p', { class: 'progress-line' }, h('span', { class: 'spin' }), `Searching ${pending} catalog${pending > 1 ? 's' : ''}…`);
    results.append(live);
    for (const t of targets) {
      const sec = rowSection(catalogTitle(t.cat), { sub: rowSubtitle(t.cat, t.source) });
      showSkeletons(sec.scroller, 6);
      const entry = { ...t, sec, empty: false, done: false };
      sections.push(entry);
      results.append(sec.el);
      const go = async () => {
        showSkeletons(sec.scroller, 6);
        try {
          const items = await loadCatalog(t.source, t.cat, { search: v }, { signal });
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
          if (!signal.aborted) {
            entry.done = true; pending--;
            drawTypes(); applyFilter();
            if (pending <= 0) {
              live.remove();
              if (!found) results.append(emptyState(`No results for “${v}”`, 'Check the spelling, or add a source that supports search.'));
            } else live.lastChild.textContent = `Searching ${pending} catalog${pending > 1 ? 's' : ''}…`;
          }
        }
      };
      go();
    }
  }

  const onInput = debounce(() => run(false), 400);
  input.addEventListener('input', () => { clr.hidden = !input.value; drawSuggestions(); if (!input.value.trim()) run(false); else onInput(); });
  // click on a result link closes the overlay (hash navigation follows)
  ov.addEventListener('click', (e) => { const a = e.target.closest?.('a.pcard'); if (a) close(true); });

  const untrap = trapFocus(ov);
  function onKey(e) { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); } }
  document.addEventListener('keydown', onKey, true);
  function close(navigated = false) {
    if (!open) return;
    ctl?.abort(); onInput.cancel(); untrap();
    document.removeEventListener('keydown', onKey, true);
    document.body.classList.remove('noscroll');
    ov.remove();
    shell?.removeAttribute('inert');
    open = null;
    const r = currentRoute();
    if (r?.name === 'search' && !navigated) navigate('/', undefined, { replace: true });
    else if (!navigated) prevFocus?.focus?.();
  }

  document.body.append(ov);
  document.body.classList.add('noscroll');
  shell?.setAttribute('inert', '');
  drawTypes(); drawRecent();
  if (q) run(false);
  input.focus();
  open = { close, setQuery(v) { if (v) { input.value = v; run(false); } input.focus(); } };
  return open;
}

export function closeSearch() { open?.close(true); }

/** Global shortcut: "/" opens search unless the user is typing or watching. */
export function initSearchShortcut() {
  document.addEventListener('keydown', (e) => {
    if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) return;
    const t = e.target;
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
    if (document.body.dataset.route === 'play' || document.getElementById('modal')?.hidden === false) return;
    e.preventDefault();
    openSearch();
  });
}
