// Continue Watching cards (Today + Shelf).
import { h, icon, clear } from './dom.js';
import * as store from '../store.js';
import { continueWatching, remainingMinutes, parseEpisodeId, normalizeVideos, setWatched } from '../progress.js';
import { href, enc } from '../router.js';
import { artImg, sheet, titleHref } from './components.js';
import { loadMeta } from '../addon/service.js';

export const streamsHref = (type, id, videoId) => href(`/streams/${enc(type)}/${enc(id)}`, videoId ? { v: videoId } : undefined);

function cardInfo(c) {
  const meta = store.cachedMeta(c.mediaId);
  const rec = c.rec || c.base || {};
  const name = meta?.name || rec.name || '';
  let epLabel = '';
  let thumb = '';
  if (c.type === 'series' || c.episodeId) {
    const vids = meta ? normalizeVideos(meta.videos) : [];
    const ep = c.episode || vids.find((v) => v.id === c.episodeId);
    const p = parseEpisodeId(c.episodeId);
    const s = ep?.season ?? p?.season, e = ep?.episode ?? p?.episode;
    if (s !== undefined && Number.isFinite(e)) epLabel = `S${s} E${e}`;
    if (ep?.title) epLabel += epLabel ? ` · ${ep.title}` : ep.title;
    thumb = ep?.thumbnail || '';
  }
  return { name, epLabel, img: thumb || meta?.background || meta?.poster || rec.poster || '', needsMeta: !name };
}

export function continueCard(c, { onChange } = {}) {
  const info = cardInfo(c);
  const target = streamsHref(c.type, c.mediaId, c.episodeId);
  const remain = c.kind === 'resume' ? remainingMinutes(c.rec) : 0;
  const pct = c.kind === 'resume' && c.rec.durationMs ? Math.round((c.rec.positionMs / c.rec.durationMs) * 100) : 0;
  const el = h('a', { class: 'wcard', href: target, 'aria-label': `${c.kind === 'upnext' ? 'Up next' : 'Resume'}: ${info.name || 'Untitled'} ${info.epLabel}` },
    h('div', { class: 'art' },
      h('div', { class: 'ph', 'aria-hidden': 'true', text: info.name || '…' }),
      artImg(info.img),
      h('span', { class: 'tag', text: c.kind === 'upnext' ? 'Up next' : `${remain} min left` }),
      h('div', { class: 'play' }, h('span', {}, icon('play', 22))),
      pct ? h('div', { class: 'pbar', style: { '--p': pct + '%' } }, h('i')) : null),
    h('span', { class: 'ttl', text: info.name || 'Loading…' }),
    h('span', { class: 'sub', text: info.epLabel || (c.kind === 'resume' ? 'Movie' : '') }));
  const menu = (e) => {
    e.preventDefault(); e.stopPropagation();
    sheet(info.name || 'Continue watching', [
      { label: 'Open details', icon: 'eye', run: () => { location.hash = titleHref({ type: c.type, id: c.mediaId }); } },
      { label: 'Mark watched', icon: 'check', run: () => {
        store.putProgress(setWatched(c.rec || null, { mediaId: c.mediaId, episodeId: c.episodeId, watched: true, now: Date.now(), meta: { type: c.type, name: info.name } }));
        onChange?.();
      } },
      { label: 'Remove from Continue Watching', icon: 'close', run: () => { dismiss(c, info); onChange?.(); } },
    ]);
  };
  el.append(h('button', { class: 'more', type: 'button', 'aria-label': `Actions for ${info.name}`, onClick: menu }, icon('more', 18)));
  el.addEventListener('contextmenu', menu);
  return el;
}

export function dismiss(c, info = {}) {
  const now = Date.now();
  if (c.type === 'series' || c.episodeId) {
    store.putProgress({ mediaId: c.mediaId, episodeId: null, type: c.type, name: info.name, positionMs: 0, durationMs: 0, watched: false, dismissed: true, updatedAt: now });
  } else if (c.rec) {
    store.putProgress({ ...c.rec, dismissed: true, updatedAt: now });
  }
}

/** Compute cards; lazily fetch metadata for series/names we lack. Calls render(cards) possibly twice. */
export async function computeContinue(render, signal) {
  const s = store.settings();
  if (!s.showContinue) { render([]); return; }
  const run = () => continueWatching(store.allProgress(), store.cachedMeta, { threshold: s.threshold, showUpNext: s.showUpNext });
  let { cards, needMeta } = run();
  const lacking = cards.filter((c) => !store.cachedMeta(c.mediaId) && !(c.rec || c.base)?.name).map((c) => ({ id: c.mediaId, type: c.type }));
  render(cards);
  const todo = [...new Map([...needMeta.map((id) => [id, 'series']), ...lacking.map((x) => [x.id, x.type])]).entries()].slice(0, 8);
  if (!todo.length) return;
  await Promise.all(todo.map(([id, type]) => loadMeta(type, id, { signal }).catch(() => null)));
  if (signal?.aborted) return;
  render(run().cards);
}

export function renderContinueRow(container, signal, { onChange } = {}) {
  clear(container);
  const draw = (cards) => {
    clear(container);
    if (!cards.length) return;
    const scroller = h('div', { class: 'scroller', role: 'list' }, cards.map((c) => continueCard(c, { onChange: () => { onChange?.(); } })));
    container.append(h('section', { class: 'row', 'aria-label': 'Continue watching' }, h('div', { class: 'row-h' }, h('div', { class: 'ht' }, h('h2', { text: 'Continue Watching' }), h('p', { class: 'rsub', text: 'Pick up where you left off' }))), scroller));
  };
  return computeContinue(draw, signal);
}
