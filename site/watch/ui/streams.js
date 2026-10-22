import { h, icon, clear, keepFocus } from './dom.js';
import * as store from '../store.js';
import { loadStreams } from '../addon/service.js';
import { describeError, fetchStreamsFrom } from '../addon/client.js';
import { pickSimilar, sortStreams, RES_RANK, describeStream } from '../addon/streams.js';
import { normalizeVideos, parseEpisodeId } from '../progress.js';
import { href, enc, navigate, goBack } from '../router.js';
import { imgUrl } from '../util.js';
import { artImg, emptyState } from './components.js';
import { badges } from './components.js';
import { nextSort, sortStreamsBy, splitTop, fmtBytes } from '../ux.js';

export function rememberStream(type, mediaId, videoId, s) {
  const stream = {
    url: s.raw.url, kind: s.cls.kind, name: s.title, desc: s.desc,
    subtitles: Array.isArray(s.raw.subtitles) ? s.raw.subtitles : [], hints: s.raw.behaviorHints || {},
    sourceUrl: s.source.url, sourceName: s.source.manifest?.name || '', bingeGroup: s.bingeGroup, res: s.res,
  };
  store.setPref('nowPlaying', { type, mediaId, videoId: videoId || null, stream, at: Date.now() });
  store.setPrefMap('lastStream', mediaId, { sourceUrl: stream.sourceUrl, bingeGroup: stream.bingeGroup, res: stream.res });
}
export const playHref = (type, id, videoId) => href(`/play/${enc(type)}/${enc(id)}`, videoId ? { v: videoId } : undefined);

export function streams({ params, query }) {
  const { type, id } = params;
  const videoId = query.v || '';
  const streamId = videoId || id;
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  const meta = store.cachedMeta(id);
  const lib = store.library().find((i) => i.id === id);
  const name = meta?.name || lib?.name || store.getProgress(id, videoId || null)?.name || id;
  const poster = meta?.poster || lib?.poster || '';
  let epLabel = '';
  if (videoId) {
    const ep = meta ? normalizeVideos(meta.videos).find((v) => v.id === videoId) : null;
    const p = parseEpisodeId(videoId);
    const s = ep?.season ?? p?.season, e = ep?.episode ?? p?.episode;
    if (s !== undefined) epLabel = `S${s} E${e}`;
    if (ep?.title) epLabel += ` · ${ep.title}`;
  }
  const groups = new Map(); // source url -> {source, status, streams, error}
  let order = [];
  let filter = 'all';
  let sort = null; // {key, dir} once the viewer picks a column
  const expanded = new Set();
  const TOP = 5;
  let autoTimer = null;
  let autoCancelled = false;

  const detailsHref = href(`/title/${enc(type)}/${enc(id)}`);
  const back = h('a', { class: 'ibtn ring', href: detailsHref, 'aria-label': 'Back to details', onClick: (e) => { if (e.ctrlKey || e.metaKey || e.shiftKey || e.button) return; e.preventDefault(); goBack(detailsHref, (p) => p.name === 'title' && p.params.id === id); } }, icon('back'));
  const head = h('div', { class: 's-head' }, back, artImg(poster, '') || h('div', { class: 'ph' }), h('div', {}, h('p', { class: 'eyebrow', text: 'Choose a stream' }), h('h1', { text: name }), epLabel ? h('p', { class: 'mute', text: epLabel }) : null));
  const chipsEl = h('div', { class: 'chips', role: 'group', 'aria-label': 'Filter by source' });
  const listEl = h('div', { class: 'slist' });
  const progEl = h('div', {});
  const autoEl = h('div', {});
  page.append(head, autoEl, chipsEl, listEl, progEl);

  function play(s) {
    clearTimeout(autoTimer);
    rememberStream(type, id, videoId, s);
    navigate(`/play/${enc(type)}/${enc(id)}`, videoId ? { v: videoId } : undefined);
  }

  function row(s) {
    const c = s.cls;
    const q = s.res ? h('span', { class: 'badge res', text: s.res }) : h('span', { class: 'mute', text: '–' });
    const cells = [
      h('div', { class: 'smain' }, h('div', { class: 't', text: s.title }), s.desc ? h('div', { class: 'd', text: s.desc }) : null,
        s.badges.filter((b) => b.kind !== 'res' && b.kind !== 'size').length ? badges(s.badges.filter((b) => b.kind !== 'res' && b.kind !== 'size')) : null,
        c.playable && c.warn ? h('div', { class: 'badges' }, h('span', { class: 'badge warn', text: c.warn })) : null,
        !c.playable && c.note ? h('div', { class: 'note' }, icon('warn', 14), c.note) : null),
      h('div', { class: `scell q${s.res ? '' : ' empty'}`, 'data-label': 'Quality' }, q),
      h('div', { class: `scell sz${s.size ? '' : ' empty'}`, 'data-label': 'Size', text: fmtBytes(s.size) || '–' }),
      h('div', { class: 'scell ssc', 'data-label': 'Source', text: s.source.manifest?.name || '' }),
      h('span', { class: 'go' }, icon(c.playable ? 'play' : c.openUrl ? 'external' : 'close', c.playable ? 18 : 16))];
    if (c.playable) return h('button', { class: 'srow', type: 'button', onClick: () => play(s) }, cells);
    if (c.openUrl) return h('a', { class: 'srow', href: c.openUrl, target: '_blank', rel: 'noopener noreferrer' }, cells);
    return h('div', { class: 'srow off', role: 'group', 'aria-disabled': 'true' }, cells);
  }

  /** Top rows plus a collapsed "more" list. */
  function list(items, key) {
    const { top, rest } = splitTop(items, TOP);
    const frag = [...top.map(row)];
    if (rest.length) {
      const open = expanded.has(key);
      if (open) frag.push(...rest.map(row));
      frag.push(h('button', { class: 'more-streams', type: 'button', 'aria-expanded': String(open), onClick: () => { if (open) expanded.delete(key); else expanded.add(key); render(); } },
        icon(open ? 'up' : 'down', 16), open ? 'Show fewer streams' : `Show ${rest.length} more stream${rest.length > 1 ? 's' : ''}`));
    }
    return frag;
  }

  function sortBar(count) {
    if (count < 2) return null;
    const btn = (key, label) => {
      const on = sort?.key === key;
      const dirWord = on ? (sort.dir === 'asc' ? 'ascending' : 'descending') : '';
      return h('button', { class: `sortbtn k-${key}`, type: 'button', 'aria-pressed': String(on), 'aria-label': `Sort by ${label.toLowerCase()}${on ? ', ' + dirWord : ''}`, onClick: () => { sort = nextSort(sort, key); render(); } },
        label, on ? icon(sort.dir === 'asc' ? 'up' : 'down', 14) : null);
    };
    return h('div', { class: 'shead', role: 'group', 'aria-label': 'Sort streams' },
      h('div', { class: 'shl' }, h('span', { class: 'lbl-sort', text: 'Sort by' }), sort ? h('button', { class: 'linkbtn reset', type: 'button', text: 'Reset', onClick: () => { sort = null; render(); } }) : null),
      btn('quality', 'Quality'), btn('size', 'Size'), btn('source', 'Source'));
  }

  function drawChips() {
    clear(chipsEl);
    if (groups.size < 2) return;
    chipsEl.append(h('button', { class: 'chip', type: 'button', 'aria-pressed': String(filter === 'all'), onClick: () => { filter = 'all'; render(); } }, 'All'));
    for (const url of order) {
      const g = groups.get(url);
      chipsEl.append(h('button', { class: 'chip', type: 'button', 'aria-pressed': String(filter === url), onClick: () => { filter = url; render(); } },
        g.source.manifest?.name || 'Source',
        g.status === 'loading' ? h('span', { class: 'spin', 'aria-label': 'loading' }) : g.status === 'err' ? h('span', { class: 'edot', title: 'Failed' }) : h('span', { class: 'n', text: String(g.streams.length) })));
    }
  }

  function render() { keepFocus(page, renderNow); }
  function renderNow() {
    drawChips();
    const s = store.settings();
    clear(listEl);
    const visible = order.filter((u) => filter === 'all' || filter === u).map((u) => groups.get(u));
    const keep = (x) => !(s.hideUnplayable && !x.cls.playable && !x.cls.openUrl);
    const flat = !!sort || s.streamGroup === 'flat';
    const total = visible.reduce((n, g) => n + g.streams.filter(keep).length, 0);
    if (flat) {
      const all = visible.flatMap((g) => g.streams).filter(keep);
      const sorted = sort ? sortStreamsBy(all, sort.key, sort.dir) : sortStreams(all, s.streamSort === 'default' ? 'resolution' : s.streamSort);
      const bar = sortBar(total);
      if (bar) listEl.append(bar);
      if (sorted.length) listEl.append(h('div', { class: 'sgroup flatlist' }, list(sorted, 'flat')));
    } else {
      const bar = sortBar(total);
      if (bar) listEl.append(bar);
      for (const g of visible) {
        const items = sortStreams(g.streams.filter(keep), s.streamSort);
        if (g.status === 'ok' && !items.length) continue;
        const el = h('div', { class: 'sgroup' }, h('h3', { text: g.source.manifest?.name || 'Source' }));
        if (g.status === 'loading') el.append(h('div', { class: 'sk line' }), h('div', { class: 'sk line' }));
        else if (g.status === 'err') el.append(h('div', { class: 'state err', role: 'alert' }, icon('warn', 22), h('div', { class: 'grow' }, h('strong', { text: 'This source failed' }), h('span', { class: 'mute', text: describeError(g.error) })), h('button', { class: 'btn sm', type: 'button', text: 'Retry', onClick: () => retry(g) })));
        else el.append(...list(items, g.source.url));
        listEl.append(el);
      }
    }
    const loading = [...groups.values()].filter((g) => g.status === 'loading').length;
    clear(progEl);
    if (loading) progEl.append(h('div', { class: 'progress-line', role: 'status' }, h('span', { class: 'spin' }), `Still searching ${loading} source${loading > 1 ? 's' : ''}…`));
    else if (groups.size) {
      const total = [...groups.values()].reduce((n, g) => n + g.streams.length, 0);
      const errs = [...groups.values()].filter((g) => g.status === 'err').length;
      if (!total && errs === groups.size && !listEl.querySelector('.state.err')) progEl.append(h('p', { class: 'mute', text: 'Every source failed.' }));
      if (!total && errs < groups.size) progEl.append(emptyState('No streams found', 'Your enabled sources have nothing for this title. Try another source, or check that the right sources are enabled.', h('button', { class: 'btn', type: 'button', text: 'Retry', onClick: start }), h('a', { class: 'btn', href: '#/sources', text: 'Check sources' })));
    }
    if (!groups.size && started) progEl.append(emptyState('No source can provide streams', store.sources().length ? 'None of your enabled sources offers streams for this kind of title.' : 'Add a source to find streams.', h('a', { class: 'btn pri', href: '#/sources', text: 'Open sources' })));
  }

  async function retry(g) {
    g.status = 'loading'; render();
    try {
      const raw = await fetchStreamsFrom(g.source, type, streamId, { signal: ctl.signal });
      g.streams = raw.map((r, i) => describeStream(r, g.source, i, { pageSecure: true }));
      g.status = 'ok';
    } catch (e) { if (ctl.signal.aborted) return; g.status = 'err'; g.error = e; }
    render();
  }

  // auto-pick: binge match for next episode, or auto-select setting
  function considerAuto() {
    if (autoCancelled || autoTimer) return;
    const wantBinge = query.auto === '1';
    if (!wantBinge && !store.settings().autoSelect) return;
    const all = order.flatMap((u) => groups.get(u).streams);
    let pick = wantBinge ? pickSimilar(all, store.prefs().lastStream?.[id]) : null;
    if (!pick && !wantBinge) {
      const playable = all.filter((x) => x.cls.playable);
      pick = sortStreams(playable, 'resolution').sort((a, b) => (RES_RANK[b.res] || 0) - (RES_RANK[a.res] || 0))[0] || null;
      if (!pick) return;
    }
    if (!pick) return;
    let n = 3;
    const label = h('span', {});
    const cancel = h('button', { class: 'btn sm', type: 'button', text: 'Show the list', onClick: () => { autoCancelled = true; clearTimeout(autoTimer); autoTimer = null; clear(autoEl); } });
    const tick = () => { label.textContent = `Starting “${pick.title}” in ${n}…`; if (n-- <= 0) { play(pick); return; } autoTimer = setTimeout(tick, 1000); };
    clear(autoEl).append(h('div', { class: 'banner', role: 'status' }, h('div', { class: 'grow' }, label), cancel));
    tick();
  }

  let started = false;
  function start() {
    groups.clear(); order = []; started = true;
    render();
    loadStreams(type, streamId, {
      signal: ctl.signal,
      onSource: (src) => { groups.set(src.url, { source: src, status: 'loading', streams: [], error: null }); order.push(src.url); render(); },
      onResult: (r) => {
        const g = groups.get(r.source.url);
        if (!g) return;
        if (r.ok) { g.status = 'ok'; g.streams = r.streams; } else { g.status = 'err'; g.error = r.error; }
        render(); considerAuto();
      },
    }).then(() => { if (!ctl.signal.aborted) render(); });
  }
  start();
  return { destroy() { ctl.abort(); clearTimeout(autoTimer); } };
}
