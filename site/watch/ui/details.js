import { h, icon, clear, toast, confirmDialog } from './dom.js';
import * as store from '../store.js';
import { loadMeta } from '../addon/service.js';
import { describeError } from '../addon/client.js';
import { normalizeVideos, playTarget, isReleased, setWatched, ratioOf, remainingMinutes, isInProgress } from '../progress.js';
import { artImg, errorCard, toggleLibrary } from './components.js';
import { streamsHref } from './continue.js';
import { imgUrl, yearOf, fmtDuration } from '../util.js';
import { safeHref } from '../addon/streams.js';

const arrayOf = (x) => (Array.isArray(x) ? x : x ? [x] : []);
const seasonChoice = new Map();

export function details({ params, query }) {
  const { type, id } = params;
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const page = h('div', { class: 'page det' });
  clear(view).append(page);
  const origin = query.src ? store.sourceById(query.src) : null;
  const snap = store.library().find((i) => i.id === id);
  const prog = store.getProgress(id, null);
  let meta = store.cachedMeta(id) || null;
  let state = 'loading'; // loading | ok | error | missing
  let errors = [];
  let expanded = false;
  let sortDesc = false;

  const hint = { id, type, name: meta?.name || snap?.name || prog?.name || '', poster: meta?.poster || snap?.poster || prog?.poster || '' };
  const unsub = store.subscribe((k) => { if (k === 'progress' || k === 'library') draw(); });

  function current() { return { ...hint, ...(meta || {}), id, type: meta?.type || type }; }

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
  }

  function draw() {
    if (ctl.signal.aborted) return;
    const m = current();
    const bgUrl = imgUrl(m.background);
    const name = m.name || id;
    const isSeries = m.type === 'series' || (meta && normalizeVideos(meta.videos).length > 1 && type === 'series');
    const videos = meta ? normalizeVideos(meta.videos) : [];
    const getRec = (epId) => store.getProgress(id, epId || null);
    const target = playTarget({ type: m.type, videos, getRec, threshold: store.settings().threshold, mediaId: id });
    const inLib = store.inLibrary(id);
    const root = h('div', {});

    // hero
    const back = h('a', { class: 'ibtn back', href: '#/', 'aria-label': 'Back', onClick: (e) => { if (history.length > 1) { e.preventDefault(); history.back(); } } }, icon('back'));
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
      icon(inLib ? 'bookmarkOn' : 'bookmark', 18), inLib ? 'On shelf' : 'Add to shelf'));
    if (m.type !== 'series') {
      const rec = getRec(null);
      acts.append(h('button', { class: 'btn', type: 'button', 'aria-pressed': String(!!rec?.watched), onClick: () => setMovieWatched(!rec?.watched) }, icon('check', 18), rec?.watched ? 'Watched' : 'Mark watched'));
    } else if (videos.length) {
      const allW = videos.filter((v) => isReleased(v)).every((v) => getRec(v.id)?.watched);
      acts.append(h('button', { class: 'btn', type: 'button', onClick: () => markMany(videos.filter((v) => isReleased(v) && v.season !== 0), !allW, 'all episodes') }, icon('check', 18), allW ? 'Mark all unwatched' : 'Mark all watched'));
    }
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
    clear(page).append(root);
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
    if (seasons.length > 8) {
      sbar.append(h('select', { class: 'inp', style: { width: 'auto' }, 'aria-label': 'Season', onChange: (e) => { seasonChoice.set(id, Number(e.target.value)); draw(); } },
        seasons.map((s) => h('option', { value: s, selected: s === sel, text: s === 0 ? 'Specials' : `Season ${s}` }))));
    } else {
      sbar.append(h('div', { class: 'chips', role: 'tablist', 'aria-label': 'Seasons' }, seasons.map((s) => h('button', { class: 'chip', type: 'button', role: 'tab', 'aria-selected': String(s === sel), onClick: () => { seasonChoice.set(id, s); draw(); } }, s === 0 ? 'Specials' : `Season ${s}`))));
    }
    sbar.append(h('span', { class: 'mute', text: `${watchedCount}/${eps.length} watched` }));
    sbar.append(h('button', { class: 'linkbtn', type: 'button', text: sortDesc ? 'Newest first' : 'Oldest first', onClick: () => { sortDesc = !sortDesc; draw(); } }));
    const released = eps.filter((e) => isReleased(e));
    const allW = released.length && released.every((e) => getRec(e.id)?.watched);
    sbar.append(h('button', { class: 'linkbtn', type: 'button', text: allW ? 'Mark season unwatched' : 'Mark season watched', onClick: () => markMany(released, !allW, 'this season') }));
    wrap.append(sbar);
    const list = h('div', { class: 'eps' });
    const ordered = sortDesc ? [...eps].reverse() : eps;
    for (const e of ordered) {
      const rec = getRec(e.id);
      const future = !isReleased(e);
      const r = ratioOf(rec);
      const th = imgUrl(e.thumbnail);
      const date = e.released && !Number.isNaN(Date.parse(e.released)) ? new Date(e.released).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '';
      const row = h('div', { class: `ep${rec?.watched ? ' watched' : ''}${future ? ' future' : ''}` },
        h('div', { class: 'th' }, h('span', { 'aria-hidden': 'true', text: `E${Number.isFinite(e.episode) ? e.episode : '?'}` }), th ? artImg(th, '') : null,
          rec?.watched ? h('span', { class: 'chk', title: 'Watched' }, icon('check', 14)) : null,
          isInProgress(rec, store.settings().threshold) ? h('div', { class: 'pbar', style: { '--p': Math.round(r * 100) + '%' } }, h('i')) : null),
        h('div', { class: 'info' },
          h('div', { class: 't' }, h('b', { text: `S${e.season} E${Number.isFinite(e.episode) ? e.episode : '?'}` }), h('span', { text: e.title || `Episode ${e.episode}` })),
          h('div', { class: 'd', text: [date, e.runtime, future ? 'Not released yet' : ''].filter(Boolean).join(' · ') }),
          e.overview ? h('div', { class: 'o', text: e.overview }) : null),
        h('button', { class: 'ibtn' + (rec?.watched ? ' on' : ''), type: 'button', 'aria-label': rec?.watched ? `Mark ${e.title || 'episode'} unwatched` : `Mark ${e.title || 'episode'} watched`, 'aria-pressed': String(!!rec?.watched), title: rec?.watched ? 'Mark unwatched' : 'Mark watched',
          onClick: () => markMany([e], !rec?.watched, 'episode') }, icon('check')));
      if (future) row.append(h('button', { class: 'main', type: 'button', 'aria-label': `${e.title || 'Episode'} is not released yet`, onClick: () => toast(`Not released yet${date ? ' — ' + date : ''}`) }));
      else row.append(h('a', { class: 'main', href: streamsHref('series', id, e.id), 'aria-label': `Play ${e.title || 'episode ' + e.episode}, season ${e.season} episode ${e.episode}` }));
      list.append(row);
    }
    wrap.append(list);
    return wrap;
  }

  fetchIt();
  return { destroy() { ctl.abort(); unsub(); } };
}
