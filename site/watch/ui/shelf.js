import { h, icon, clear, keepFocus, toast } from './dom.js';
import * as store from '../store.js';
import { posterCard, emptyState, emptyPage, artImg, titleHref, itemMenu, progressive } from './components.js';
import { renderContinueRow } from './continue.js';
import { isInProgress, ratioOf, setWatched } from '../progress.js';
import { shelfStatus, SHELF_STATUS, pickSurprise } from '../ux.js';
import { navigate } from '../router.js';
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
  // multi-select: pick several titles, then mark them watched or remove them (L005)
  let selecting = false;
  const picked = new Set();
  const bar = h('div', { class: 'selbar', role: 'region', 'aria-label': 'Selected titles', hidden: true });
  const selBtn = h('button', { class: 'btn sm', type: 'button', 'aria-pressed': 'false', onClick: () => setSelecting(!selecting) }, 'Select');
  const surpriseBtn = h('button', { class: 'btn sm', type: 'button', title: 'Open a random title from your shelf', onClick: surprise }, icon('dice', 18), 'Surprise me');
  const histLink = h('a', { class: 'btn sm', href: '#/history' }, icon('history', 18), 'History');
  tb.append(search, sortSel, viewBtn);
  page.append(h('div', { class: 'page-h' }, h('h1', { text: 'Shelf' }), h('div', { class: 'actions' }, surpriseBtn, histLink, selBtn)), contBox, tb, typeChips, bar, out);

  function surprise() {
    const lib = store.library();
    const pick = pickSurprise(lib, { isWatched: (i) => state(i).any, avoid: store.prefs().lastSurprise || '' });
    if (!pick) { toast('Add something to your shelf first'); return; }
    store.setPref('lastSurprise', pick.id);
    toast(`Tonight: ${pick.name}`);
    navigate(`/title/${encodeURIComponent(pick.type || 'movie')}/${encodeURIComponent(pick.id)}`);
  }
  function setSelecting(on) { selecting = on; picked.clear(); selBtn.setAttribute('aria-pressed', String(on)); selBtn.textContent = on ? 'Done' : 'Select'; draw(); }
  function drawBar() {
    bar.hidden = !selecting;
    if (!selecting) return;
    const n = picked.size;
    clear(bar).append(h('span', { class: 'selcount', role: 'status', text: n ? `${n} selected` : 'Tap titles to select them' }),
      h('button', { class: 'btn sm', type: 'button', disabled: !n, text: 'Mark watched', onClick: () => { const now = Date.now(); for (const id of picked) { const i = store.library().find((x) => x.id === id); store.putProgress(setWatched(store.getProgress(id, null), { mediaId: id, episodeId: null, watched: true, now, meta: { type: i?.type, name: i?.name, poster: i?.poster } })); } toast(`Marked ${n} watched`); setSelecting(false); } }),
      h('button', { class: 'btn sm danger', type: 'button', disabled: !n, text: 'Remove from shelf', onClick: () => { const snaps = store.library().filter((i) => picked.has(i.id)); snaps.forEach((i) => store.removeFromLibrary(i.id)); toast(`Removed ${snaps.length} from your shelf`, { action: 'Undo', onAction: () => snaps.forEach((i) => store.addToLibrary(i)) }); setSelecting(false); } }));
  }
  const markPicked = (el, id) => { el.dataset.id = id; if (selecting) { el.setAttribute('role', 'checkbox'); el.setAttribute('aria-checked', String(picked.has(id))); el.classList.toggle('picked', picked.has(id)); } return el; };
  out.addEventListener('click', (e) => {
    if (!selecting) return;
    const el = e.target.closest('[data-id]');
    if (!el) return;
    e.preventDefault(); e.stopPropagation();
    const id = el.dataset.id;
    if (picked.has(id)) picked.delete(id); else picked.add(id);
    el.setAttribute('aria-checked', String(picked.has(id))); el.classList.toggle('picked', picked.has(id));
    drawBar();
  }, true);

  let byMedia = new Map();
  let stopFill = () => {};
  function state(item) {
    const rec = store.getProgress(item.id, null);
    const eps = byMedia.get(item.id) || [];
    const watched = rec?.watched || (item.type === 'series' && eps.some((r) => r.episodeId && r.watched));
    const prog = isInProgress(rec, s.threshold) || eps.some((r) => r.episodeId && isInProgress(r, s.threshold));
    const last = Math.max(0, ...eps.map((r) => r.updatedAt || 0));
    return { watched: !!rec?.watched, any: !!watched, prog, last };
  }

  function draw() { keepFocus(page, drawNow); }
  function drawNow() {
    stopFill();
    byMedia = new Map();
    for (const r of store.allProgress()) { if (!byMedia.has(r.mediaId)) byMedia.set(r.mediaId, []); byMedia.get(r.mediaId).push(r); }
    const lib = store.library();
    clear(typeChips);
    const g1 = h('div', { class: 'chips', role: 'group', 'aria-label': 'Type' });
    const g2 = h('div', { class: 'chips', role: 'group', 'aria-label': 'Status' });
    typeChips.append(g1, g2);
    const chip = (label, active, on) => h('button', { class: 'chip', type: 'button', 'aria-pressed': String(active), onClick: on }, label);
    for (const [v, l] of [['all', 'All'], ['movie', 'Movies'], ['series', 'Series']]) g1.append(chip(l, type === v, () => { type = v; draw(); }));
    for (const [v, l] of SHELF_STATUS) g2.append(chip(l, filter === v, () => { filter = v; draw(); }));
    let items = lib.map((i) => ({ i, st: state(i) }));
    if (text) items = items.filter(({ i }) => (i.name || '').toLowerCase().includes(text));
    if (type !== 'all') items = items.filter(({ i }) => i.type === type);
    if (filter !== 'all') items = items.filter(({ st }) => shelfStatus(st) === filter);
    const cmp = {
      added: (a, b) => (b.i.addedAt || 0) - (a.i.addedAt || 0),
      title: (a, b) => (a.i.name || '').localeCompare(b.i.name || ''),
      year: (a, b) => Number(yearOf(b.i) || 0) - Number(yearOf(a.i) || 0),
      watched: (a, b) => b.st.last - a.st.last,
      unwatched: (a, b) => Number(a.st.any) - Number(b.st.any) || (b.i.addedAt || 0) - (a.i.addedAt || 0),
    }[sort];
    items.sort(cmp);
    clear(out);
    tb.hidden = typeChips.hidden = !lib.length;
    if (!lib.length) { out.append(emptyPage('list', 'Your shelf is empty', 'Add movies and series to your shelf with the + button. They stay here even if a source goes away.', h('a', { class: 'btn pri', href: '#/', text: 'Browse titles' }))); return; }
    if (!items.length) { out.append(emptyState('Nothing matches', 'Try clearing the filters.', h('button', { class: 'btn', type: 'button', text: 'Clear filters', onClick: () => { text = ''; search.value = ''; type = 'all'; filter = 'all'; draw(); } }))); return; }
    const box = h('div', { class: mode === 'grid' ? 'grid' : 'list', role: 'list' });
    out.append(box);
    if (mode === 'grid') stopFill = progressive(box, items, ({ i }) => { const c = posterCard(i); if (!selecting) c.setAttribute('role', 'listitem'); return markPicked(c, i.id); });
    else stopFill = progressive(box, items, ({ i, st }) => markPicked(h('a', { class: 'lrow', href: titleHref(i), role: 'listitem' },
      h('div', { class: 'art' }, artImg(i.poster, '') || h('div', { class: 'ph' })),
      h('div', {}, h('div', { class: 'serif', text: i.name }), h('div', { class: 'mute', text: [i.type === 'series' ? 'Series' : 'Movie', yearOf(i)].filter(Boolean).join(' · ') }), st.any ? h('span', { class: 'badge ok', text: 'Watched' }) : st.prog ? h('span', { class: 'badge', text: 'In progress' }) : null),
      h('button', { class: 'ibtn', type: 'button', 'aria-label': `Actions for ${i.name}`, onClick: (e) => { e.preventDefault(); itemMenu(i); } }, icon('more'))), i.id));
    drawBar();
  }
  const unsub = store.subscribe((k) => { if (k === 'library' || k === 'progress') draw(); });
  renderContinueRow(contBox, ctl.signal, { onChange: () => renderContinueRow(contBox, ctl.signal) });
  draw();
  return { destroy() { ctl.abort(); unsub(); stopFill(); } };
}
