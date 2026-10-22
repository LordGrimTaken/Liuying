import { h, icon, clear, toast, confirmDialog, modal, keepFocus } from './dom.js';
import * as store from '../store.js';
import { loadMeta } from '../addon/service.js';
import { describeError } from '../addon/client.js';
import { titleHint, normalizeVideos, playTarget, isReleased, setWatched, ratioOf, remainingMinutes, isInProgress } from '../progress.js';
import { artImg, errorCard, toggleLibrary } from './components.js';
import { streamsHref } from './continue.js';
import { imgUrl, yearOf, fmtDuration } from '../util.js';
import { safeHref } from '../addon/streams.js';
import { shareUrl } from '../ux.js';
import { navigate, enc, goBack } from '../router.js';

const arrayOf = (x) => (Array.isArray(x) ? x : x ? [x] : []);
const seasonChoice = new Map();
const EP_CHUNK = 60; // episodes drawn at a time; seasons with hundreds of entries stay light

export function details({ params, query }) {
  const { type, id } = params;
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const page = h('div', { class: 'page det' });
  clear(view).append(page);
  const origin = query.src ? store.sourceById(query.src) : null;
  let meta = store.cachedMeta(id) || null;
  let state = 'loading'; // loading | ok | error | missing
  let errors = [];
  let expanded = false;
  let sortDesc = false;
  const epShown = new Map(); // season -> how many episode rows are drawn

  // offline / uncached: fall back to the shelf or any progress record's title and poster before the raw id
  const unsub = store.subscribe((k) => { if (k === 'progress' || k === 'library') draw(); });

  function current() {
    const hint = titleHint(id, { meta, library: store.library(), progress: store.allProgress() });
    return { ...(meta || {}), id, type: meta?.type || type, name: hint.name, poster: hint.poster };
  }

  async function fetchIt() {
    state = meta ? 'ok' : 'loading'; draw();
    const res = await loadMeta(type, id, {
      signal: ctl.signal, originUrl: origin?.url,
      onUpdate: (m) => { meta = m; state = 'ok'; draw(); },
    });
    if (ctl.signal.aborted) return;
    errors = res.errors;
    if (res.meta) { meta = res.meta; state = 'ok'; }
    else if (!meta) state = res.sourcesTried && res.errors.length === res.sourcesTried ? 'error' : 'missing';
    draw();
    if (query.play && meta && state === 'ok') {
      const vids = normalizeVideos(meta.videos);
      const t = playTarget({ type: meta.type || type, videos: vids, getRec: (e) => store.getProgress(id, e || null), threshold: store.settings().threshold, mediaId: id });
      if (t.episodeId || !vids.length) navigate(`/streams/${enc(type)}/${enc(id)}`, t.episodeId ? { v: t.episodeId } : undefined, { replace: true });
    }
  }

  function draw() {
    if (ctl.signal.aborted) return;
    const m = current();
    const bgUrl = imgUrl(m.background);
    const name = m.name || (state === 'loading' ? 'Loading…' : id);
    const isSeries = m.type === 'series' || (meta && normalizeVideos(meta.videos).length > 1 && type === 'series');
    const videos = meta ? normalizeVideos(meta.videos) : [];
    const getRec = (epId) => store.getProgress(id, epId || null);
    const target = playTarget({ type: m.type, videos, getRec, threshold: store.settings().threshold, mediaId: id });
    const inLib = store.inLibrary(id);
    const root = h('div', {});

    // hero
    const back = h('a', { class: 'ibtn back', href: '#/', 'aria-label': 'Back', onClick: (e) => { e.preventDefault(); goBack('#/'); } }, icon('back'));
    const poster = h('div', { class: 'pcard' }, h('div', { class: 'art' }, h('div', { class: 'ph', 'aria-hidden': 'true', text: name }), artImg(m.poster, `${name} poster`)));
    const logo = artImg(m.logo, name);
    if (logo) { logo.className = 'logo'; logo.loading = 'eager'; }
    const metaBits = [];
    const yr = yearOf(m); if (yr) metaBits.push(h('span', { text: yr }));
    if (m.runtime) metaBits.push(h('span', { text: String(m.runtime) }));
    if (m.imdbRating) metaBits.push(h('span', { class: 'rate', text: `★ ${m.imdbRating}` }));
    if (m.certification || m.contentRating) metaBits.push(h('span', { class: 'badge', text: m.certification || m.contentRating }));
    const genres = arrayOf(m.genres || m.genre);
    const top = h('div', { class: 'det-top' }, poster, h('div', { class: 'titleblock' },
      logo || null, h('h1', { class: logo ? 'sr' : '', text: name }), h('div', { class: 'metaline' }, metaBits, genres.slice(0, 4).map((g) => h('span', { class: 'badge', text: g })))));
    const heroEl = h('div', { class: 'det-hero' + (bgUrl ? '' : ' nobg') }, bgUrl ? artImg(m.background, '') : null, back, top);
    if (bgUrl) heroEl.querySelector('img')?.classList.add('bg');
    root.append(heroEl);

    const body = h('div', { class: 'det-body' });
    // actions
    const acts = h('div', { class: 'actions' });
    const canPlay = !(m.type === 'series' && !videos.length && state === 'ok');
    if (canPlay) {
      let label = 'Play';
      const ep = target.episode;
      const epTag = ep ? ` S${ep.season} E${ep.episode}` : '';
      if (target.label === 'resume') label = `Resume${epTag}`;
      else if (target.label === 'again') label = `Watch again${epTag}`;
      else label = ep && m.type === 'series' ? `Play${epTag}` : 'Play';
      acts.append(h('a', { class: 'btn pri', href: streamsHref(m.type, id, target.episodeId) }, icon('play', 18), label));
    }
    acts.append(h('button', { class: 'btn', type: 'button', 'aria-pressed': String(inLib), onClick: () => toggleLibrary({ id, type: m.type, name, poster: m.poster, background: m.background, year: yr }) },
      icon(inLib ? 'check' : 'plus', 18), inLib ? 'Saved' : 'Save'));
    if (m.type !== 'series') {
      const rec = getRec(null);
      acts.append(h('button', { class: 'btn', type: 'button', 'aria-pressed': String(!!rec?.watched), onClick: () => setMovieWatched(!rec?.watched) }, icon('eye', 18), rec?.watched ? 'Watched' : 'Mark watched'));
    } else if (videos.length) {
      const allW = videos.filter((v) => isReleased(v)).every((v) => getRec(v.id)?.watched);
      acts.append(h('button', { class: 'btn', type: 'button', 'aria-pressed': String(allW), onClick: () => markMany(videos.filter((v) => isReleased(v) && v.season !== 0), !allW, 'all episodes') }, icon('eye', 18), allW ? 'Watched' : 'Mark watched'));
    }
    acts.append(h('button', { class: 'ibtn ring', type: 'button', 'aria-label': 'Share or copy link', title: 'Share or copy link', onClick: () => share(m) }, icon('share', 20)));
    const trailer = arrayOf(m.trailers).map((t) => t?.source || t?.ytId).find(Boolean) || arrayOf(m.trailerStreams).map((t) => t?.ytId).find(Boolean);
    if (trailer) acts.append(h('a', { class: 'btn', href: `https://www.youtube.com/watch?v=${encodeURIComponent(trailer)}`, target: '_blank', rel: 'noopener noreferrer' }, icon('external', 18), 'Trailer'));
    const note = target.label === 'resume' && target.rec ? `${remainingMinutes(target.rec)} min left` : '';
    body.append(h('div', {}, acts, note ? h('p', { class: 'progress-note', text: note }) : null));

    // states
    if (state === 'loading') body.append(h('div', {}, h('div', { class: 'sk line' }), h('div', { class: 'sk line' }), h('div', { class: 'sk line' })));
    if (state === 'error') body.append(errorCard("Couldn't load details", errors.length ? describeError(errors[0].error) : 'Your sources did not respond.', () => { errors = []; fetchIt(); }));
    if (state === 'missing') body.append(h('div', { class: 'state compact' }, 'Limited info available. No source returned details for this title, but you can still look for streams.'));
    

    // synopsis
    if (m.description) {
      const syn = h('p', { class: 'synopsis' + (expanded ? '' : ' clamp'), text: m.description });
      body.append(h('div', {}, syn, m.description.length > 260 ? h('button', { class: 'linkbtn', type: 'button', 'aria-expanded': String(expanded), text: expanded ? 'Less' : 'More', onClick: () => { expanded = !expanded; draw(); } }) : null));
    }
    // facts
    const facts = [];
    const add = (k, v) => { const a = arrayOf(v).filter(Boolean); if (a.length) facts.push(h('dt', { text: k }), h('dd', { text: a.join(', ') })); };
    add('Director', m.director || m.directors); add('Writer', m.writer || m.writers); add('Released', m.released ? new Date(m.released).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' }) : ''); add('Country', m.country); add('Language', m.language);
    if (facts.length) body.append(h('dl', { class: 'facts' }, facts));
    // cast
    const cast = arrayOf(m.cast).slice(0, 16);
    if (cast.length) body.append(h('section', {}, h('h2', { class: 'sec', text: 'Cast' }), h('div', { class: 'cast', role: 'list' }, cast.map((n) => h('div', { class: 'p', role: 'listitem' }, h('div', { class: 'av', 'aria-hidden': 'true', text: String(n).split(/\s+/).slice(0, 2).map((x) => x[0]).join('').toUpperCase() }), h('span', { text: n }))))));
    // episodes
    if (m.type === 'series') body.append(episodes(videos, getRec));
    if (state === 'ok' && errors.length) body.append(h('p', { class: 'det-note' }, icon('warn', 14), `${errors.length} source${errors.length > 1 ? 's' : ''} couldn't provide details; showing what the others returned.`));
    root.append(body);
    keepFocus(page, () => clear(page).append(root));
  }

  async function share(m) {
    const url = shareUrl(location.origin, m.type || type, id);
    try {
      if (navigator.share) { await navigator.share({ title: m.name || id, url }); return; }
    } catch (e) { if (e?.name === 'AbortError') return; }
    try { await navigator.clipboard.writeText(url); toast('Link copied'); return; } catch { /* fall through */ }
    const inp = h('input', { class: 'inp', readonly: true, value: url, 'aria-label': 'Link to this title' });
    modal({ title: 'Copy link', body: h('div', {}, inp), actions: [{ label: 'Done', value: null, primary: true }] });
    inp.select();
  }
  function setMovieWatched(w) {
    const m = current();
    store.putProgress(setWatched(store.getProgress(id, null), { mediaId: id, episodeId: null, watched: w, now: Date.now(), meta: { type: m.type, name: m.name, poster: m.poster } }));
  }
  async function markMany(eps, watched, what) {
    if (watched && eps.length > 3 && !(await confirmDialog('Mark as watched?', `Mark ${what} as watched?`, 'Mark watched'))) return;
    const m = current(); const now = Date.now();
    eps.forEach((e, i) => store.putProgress(setWatched(store.getProgress(id, e.id), { mediaId: id, episodeId: e.id, watched, now: now + i, meta: { type: m.type, name: m.name, poster: m.poster } })));
  }

  function episodes(videos, getRec) {
    const wrap = h('section', {}, h('h2', { class: 'sec', text: 'Episodes' }));
    if (!videos.length) { wrap.append(h('p', { class: 'mute', text: state === 'loading' ? 'Loading episodes…' : 'No episodes listed by your sources.' })); return wrap; }
    const seasons = [...new Set(videos.map((v) => v.season))].sort((a, b) => (a === 0 ? 1e9 : a) - (b === 0 ? 1e9 : b));
    const target = playTarget({ type: 'series', videos, getRec, threshold: store.settings().threshold, mediaId: id });
    let sel = seasonChoice.get(id);
    if (sel === undefined || !seasons.includes(sel)) sel = target.episode?.season ?? seasons.find((s) => s !== 0) ?? seasons[0];
    const eps = videos.filter((v) => v.season === sel);
    const watchedCount = eps.filter((e) => getRec(e.id)?.watched).length;
    const sbar = h('div', { class: 'seasons' });
    sbar.append(h('label', { class: 'sr', for: 'season-sel', text: 'Season' }), h('select', { class: 'inp seasel', id: 'season-sel', onChange: (e) => { seasonChoice.set(id, Number(e.target.value)); draw(); document.getElementById('season-sel')?.focus(); } },
      seasons.map((s) => h('option', { value: s, selected: s === sel, text: s === 0 ? 'Specials' : `Season ${s}` }))));
    sbar.append(h('span', { class: 'mute', text: `${watchedCount}/${eps.length} watched` }));
    sbar.append(h('button', { class: 'linkbtn', type: 'button', text: sortDesc ? 'Newest first' : 'Oldest first', onClick: () => { sortDesc = !sortDesc; draw(); } }));
    const released = eps.filter((e) => isReleased(e));
    const allW = released.length && released.every((e) => getRec(e.id)?.watched);
    sbar.append(h('button', { class: 'linkbtn', type: 'button', text: allW ? 'Mark season unwatched' : 'Mark season watched', onClick: () => markMany(released, !allW, 'this season') }));
    wrap.append(sbar);
    const list = h('div', { class: 'eps' });
    const orderedAll = sortDesc ? [...eps].reverse() : eps;
    // always include the "up next" episode in the visible window
    const nextIdx = orderedAll.findIndex((e) => e.id === target.episode?.id);
    const limit = Math.max(epShown.get(sel) || EP_CHUNK, nextIdx >= 0 ? nextIdx + 6 : 0);
    const ordered = orderedAll.slice(0, limit);
    for (const e of ordered) {
      const rec = getRec(e.id);
      const future = !isReleased(e);
      const r = ratioOf(rec);
      const th = imgUrl(e.thumbnail);
      const date = e.released && !Number.isNaN(Date.parse(e.released)) ? new Date(e.released).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '';
      const isNext = target.episode?.id === e.id && !rec?.watched;
      const row = h('div', { class: `ep${rec?.watched ? ' watched' : ''}${future ? ' future' : ''}${isNext ? ' next' : ''}` },
        h('div', { class: 'th' }, h('span', { 'aria-hidden': 'true', text: `E${Number.isFinite(e.episode) ? e.episode : '?'}` }), th ? artImg(th, '') : null,
          rec?.watched ? h('span', { class: 'chk', title: 'Watched' }, icon('check', 14)) : null,
          isInProgress(rec, store.settings().threshold) ? h('div', { class: 'pbar', style: { '--p': Math.round(r * 100) + '%' } }, h('i')) : null),
        h('div', { class: 'info' },
          h('div', { class: 't' }, h('b', { text: `S${e.season} E${Number.isFinite(e.episode) ? e.episode : '?'}` }), h('span', { text: e.title || `Episode ${e.episode}` }), isNext ? h('span', { class: 'badge nx', text: 'Up next' }) : rec?.watched ? h('span', { class: 'badge ok wk', text: 'Watched' }) : null),
          h('div', { class: 'd', text: [date, e.runtime, future ? 'Not released yet' : ''].filter(Boolean).join(' · ') }),
          e.overview ? h('div', { class: 'o', text: e.overview }) : null),
        h('button', { class: 'ibtn' + (rec?.watched ? ' on' : ''), type: 'button', 'aria-label': rec?.watched ? `Mark ${e.title || 'episode'} unwatched` : `Mark ${e.title || 'episode'} watched`, 'aria-pressed': String(!!rec?.watched), title: rec?.watched ? 'Mark unwatched' : 'Mark watched',
          onClick: () => markMany([e], !rec?.watched, 'episode') }, icon('check')));
      if (future) row.append(h('button', { class: 'main', type: 'button', 'aria-label': `${e.title || 'Episode'} is not released yet`, onClick: () => toast(`Not released yet${date ? ' — ' + date : ''}`) }));
      else row.append(h('a', { class: 'main', href: streamsHref('series', id, e.id), 'aria-label': `Play ${e.title || 'episode ' + e.episode}, season ${e.season} episode ${e.episode}` }));
      list.append(row);
    }
    wrap.append(list);
    if (orderedAll.length > ordered.length) {
      const left = orderedAll.length - ordered.length;
      wrap.append(h('button', { class: 'btn more-eps', type: 'button', onClick: () => { epShown.set(sel, ordered.length + EP_CHUNK * 2); draw(); } }, `Show ${Math.min(left, EP_CHUNK * 2)} more episodes`, h('span', { class: 'mute', text: ` (${left} left)` })));
    }
    return wrap;
  }

  fetchIt();
  return { destroy() { ctl.abort(); unsub(); } };
}
