// Watch history: everything watched or started, newest first, by day. Entries can be marked or removed (L023).
import { h, icon, clear, toast } from './dom.js';
import * as store from '../store.js';
import { buildHistory } from '../ux.js';
import { parseEpisodeId, setWatched, titleHint, ratioOf } from '../progress.js';
import { artImg, titleHref, emptyPage } from './components.js';
import { debounce } from '../util.js';

const hint = (id) => titleHint(id, { meta: store.cachedMeta(id), library: store.library(), progress: store.allProgress() });
const timeOf = (ms) => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

/** Removing keeps a hidden marker (position 0, not watched, dismissed) so sync cannot bring the entry back. */
export const hiddenRecord = (rec, now = Date.now()) => ({ ...rec, positionMs: 0, watched: false, dismissed: true, updatedAt: now });

export function history() {
  const view = document.getElementById('view');
  const page = h('div', { class: 'page hist' });
  clear(view).append(page);
  let text = '';
  const out = h('div', {});
  const search = h('input', { class: 'inp', type: 'search', placeholder: 'Filter history', 'aria-label': 'Filter history', onInput: debounce(() => { text = search.value.trim().toLowerCase(); draw(); }, 120) });
  const count = h('p', { class: 'mute', role: 'status' });
  page.append(h('div', { class: 'page-h' }, h('a', { class: 'ibtn ring', href: '#/shelf', 'aria-label': 'Back to shelf' }, icon('back')), h('h1', { text: 'History' })),
    h('p', { class: 'lede', text: 'What you watched or started, newest first. Removing an entry only hides it here and in Continue Watching.' }), h('div', { class: 'toolbar' }, search), count, out);

  function status(rec) {
    if (rec.watched) return h('span', { class: 'badge ok', text: 'Watched' });
    const r = Math.round(ratioOf(rec) * 100);
    return h('span', { class: 'badge', text: r ? `${r}% watched` : 'Started' });
  }
  function entry({ rec, name, type, ep }) {
    const hh = hint(rec.mediaId);
    const label = ep && Number.isFinite(ep.episode) ? `S${ep.season} E${ep.episode}` : '';
    const prev = rec;
    const mark = () => { store.putProgress(setWatched(prev, { mediaId: rec.mediaId, episodeId: rec.episodeId, watched: !rec.watched, now: Date.now() })); toast(rec.watched ? 'Marked as not watched' : 'Marked as watched'); };
    const remove = () => {
      store.putProgress(hiddenRecord(prev));
      toast(`Removed ${name}${label ? ' ' + label : ''} from history`, { action: 'Undo', onAction: () => store.putProgress({ ...prev, updatedAt: Date.now() }), ms: 7000 });
    };
    return h('li', { class: 'hist-row' },
      h('a', { class: 'hthumb', href: titleHref({ type, id: rec.mediaId }), tabindex: '-1', 'aria-hidden': 'true' }, h('div', { class: 'ph', text: name }), artImg(hh.poster || rec.poster, '')),
      h('div', { class: 'grow' },
        h('a', { class: 'hname', href: titleHref({ type, id: rec.mediaId }), text: name }),
        h('div', { class: 'mute hmeta' }, [label, timeOf(rec.updatedAt)].filter(Boolean).join(' · '), ' ', status(rec))),
      h('button', { class: 'ibtn', type: 'button', 'aria-label': `${rec.watched ? 'Mark not watched' : 'Mark watched'}: ${name} ${label}`.trim(), title: rec.watched ? 'Mark not watched' : 'Mark watched', onClick: mark }, icon('check', 20)),
      h('button', { class: 'ibtn', type: 'button', 'aria-label': `Remove from history: ${name} ${label}`.trim(), title: 'Remove from history', onClick: remove }, icon('trash', 20)));
  }
  function draw() {
    clear(out);
    let days = buildHistory(store.allProgress(), hint, new Date(), parseEpisodeId);
    const total = days.reduce((n, d) => n + d.items.length, 0);
    if (text) days = days.map((d) => ({ ...d, items: d.items.filter((i) => i.name.toLowerCase().includes(text)) })).filter((d) => d.items.length);
    const n = days.reduce((a, d) => a + d.items.length, 0);
    count.textContent = !total ? '' : text ? `${n} of ${total} entries` : `${total} entr${total === 1 ? 'y' : 'ies'}`;
    if (!total) { out.append(emptyPage('history', 'Nothing watched yet', 'Titles you start or finish will be listed here.', h('a', { class: 'btn pri', href: '#/', text: 'Browse Today' }))); return; }
    if (!n) { out.append(h('div', { class: 'state compact', text: `No history entries match “${text}”.` })); return; }
    for (const d of days) out.append(h('section', { class: 'hist-day', 'aria-label': d.label }, h('h2', { class: 'sec', text: d.label }), h('ul', { class: 'hist-list' }, d.items.map(entry))));
  }
  draw();
  const unsub = store.subscribe((k) => { if (k === 'progress') { const f = document.activeElement; const key = f?.closest?.('.hist-row') ? [...page.querySelectorAll('.hist-row')].indexOf(f.closest('.hist-row')) : -1; draw(); if (key >= 0) (page.querySelectorAll('.hist-row')[Math.min(key, page.querySelectorAll('.hist-row').length - 1)]?.querySelector('button') || page.querySelector('h1'))?.focus?.({ preventScroll: true }); } });
  return { destroy() { unsub(); } };
}
