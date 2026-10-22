import { h, icon, clear } from './dom.js';
import * as store from '../store.js';
import { posterCard, emptyState, artImg, titleHref, itemMenu } from './components.js';
import { renderContinueRow } from './continue.js';
import { isInProgress, ratioOf } from '../progress.js';
import { debounce, yearOf } from '../util.js';

export function shelf() {
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  const s = store.settings();
  let text = '', type = 'all', filter = 'all', sort = s.libSort || 'added', mode = s.libView || 'grid';
  const contBox = h('div', {});
  const out = h('div', {});
  const tb = h('div', { class: 'toolbar' });
  const search = h('input', { class: 'inp', type: 'search', placeholder: 'Filter your shelf', 'aria-label': 'Filter your shelf', onInput: debounce(() => { text = search.value.trim().toLowerCase(); draw(); }, 120) });
  const sortSel = h('select', { class: 'inp', style: { 'max-width': '190px' }, 'aria-label': 'Sort', onChange: (e) => { sort = e.target.value; store.setSettings({ libSort: sort }); draw(); } },
    [['added', 'Recently added'], ['title', 'Title A–Z'], ['year', 'Year'], ['watched', 'Last watched'], ['unwatched', 'Unwatched first']].map(([v, l]) => h('option', { value: v, selected: v === sort, text: l })));
  const viewBtn = h('button', { class: 'ibtn ring', type: 'button', 'aria-label': 'Toggle grid or list view', onClick: () => { mode = mode === 'grid' ? 'list' : 'grid'; store.setSettings({ libView: mode }); draw(); } }, icon('shelf'));
  const typeChips = h('div', { class: 'chipgroups' });
  tb.append(search, sortSel, viewBtn);
  page.append(h('div', { class: 'page-h' }, h('h1', { text: 'Shelf' })), contBox, tb, typeChips, out);

  function state(item) {
    const rec = store.getProgress(item.id, null);
    const eps = store.allProgress().filter((r) => r.mediaId === item.id);
    const watched = rec?.watched || (item.type === 'series' && eps.some((r) => r.episodeId && r.watched));
    const prog = isInProgress(rec, s.threshold) || eps.some((r) => r.episodeId && isInProgress(r, s.threshold));
    const last = Math.max(0, ...eps.map((r) => r.updatedAt || 0));
    return { watched: !!rec?.watched, any: !!watched, prog, last };
  }

  function draw() {
    const lib = store.library();
    clear(typeChips);
    const g1 = h('div', { class: 'chips', role: 'group', 'aria-label': 'Type' });
    const g2 = h('div', { class: 'chips', role: 'group', 'aria-label': 'Status' });
    typeChips.append(g1, g2);
    const chip = (label, active, on) => h('button', { class: 'chip', type: 'button', 'aria-pressed': String(active), onClick: on }, label);
    for (const [v, l] of [['all', 'All'], ['movie', 'Movies'], ['series', 'Series']]) g1.append(chip(l, type === v, () => { type = v; draw(); }));
    for (const [v, l] of [['all', 'Any'], ['unwatched', 'Unwatched'], ['progress', 'In progress'], ['watched', 'Watched']]) g2.append(chip(l, filter === v, () => { filter = v; draw(); }));
    let items = lib.map((i) => ({ i, st: state(i) }));
    if (text) items = items.filter(({ i }) => (i.name || '').toLowerCase().includes(text));
    if (type !== 'all') items = items.filter(({ i }) => i.type === type);
    if (filter === 'unwatched') items = items.filter(({ st }) => !st.any && !st.prog);
    if (filter === 'progress') items = items.filter(({ st }) => st.prog);
    if (filter === 'watched') items = items.filter(({ st }) => st.any);
    const cmp = {
      added: (a, b) => (b.i.addedAt || 0) - (a.i.addedAt || 0),
      title: (a, b) => (a.i.name || '').localeCompare(b.i.name || ''),
      year: (a, b) => Number(yearOf(b.i) || 0) - Number(yearOf(a.i) || 0),
      watched: (a, b) => b.st.last - a.st.last,
      unwatched: (a, b) => Number(a.st.any) - Number(b.st.any) || (b.i.addedAt || 0) - (a.i.addedAt || 0),
    }[sort];
    items.sort(cmp);
    clear(out);
    if (!lib.length) { out.append(emptyState('Your shelf is empty', 'Use the bookmark button on any title to keep it here, even if a source goes away.', h('a', { class: 'btn pri', href: '#/', text: 'Go to Today' }))); return; }
    if (!items.length) { out.append(emptyState('Nothing matches', 'Try clearing the filters.', h('button', { class: 'btn', type: 'button', text: 'Clear filters', onClick: () => { text = ''; search.value = ''; type = 'all'; filter = 'all'; draw(); } }))); return; }
    if (mode === 'grid') out.append(h('div', { class: 'grid', role: 'list' }, items.map(({ i }) => { const c = posterCard(i); c.setAttribute('role', 'listitem'); return c; })));
    else out.append(h('div', { class: 'list', role: 'list' }, items.map(({ i, st }) => h('a', { class: 'lrow', href: titleHref(i), role: 'listitem' },
      h('div', { class: 'art' }, artImg(i.poster, '') || h('div', { class: 'ph' })),
      h('div', {}, h('div', { class: 'serif', text: i.name }), h('div', { class: 'mute', text: [i.type === 'series' ? 'Series' : 'Movie', yearOf(i)].filter(Boolean).join(' · ') }), st.any ? h('span', { class: 'badge ok', text: 'Watched' }) : st.prog ? h('span', { class: 'badge', text: 'In progress' }) : null),
      h('button', { class: 'ibtn', type: 'button', 'aria-label': `Actions for ${i.name}`, onClick: (e) => { e.preventDefault(); itemMenu(i); } }, icon('more'))))));
  }
  const unsub = store.subscribe((k) => { if (k === 'library' || k === 'progress') draw(); });
  renderContinueRow(contBox, ctl.signal, { onChange: () => renderContinueRow(contBox, ctl.signal) });
  draw();
  return { destroy() { ctl.abort(); unsub(); } };
}
