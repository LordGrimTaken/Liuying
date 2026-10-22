// Watch progress, continue-watching and next-episode logic. Pure functions (no DOM, no storage).

export const DEFAULT_THRESHOLD = 0.9;
export const MIN_START_RATIO = 0.03;
export const MIN_START_MS = 60000;
export const RESUME_MAX_RATIO = 0.92;

export const progressKey = (mediaId, episodeId) => `${mediaId}\u0000${episodeId || ''}`;

export const ratioOf = (r) => (r && r.durationMs > 0 ? Math.min(1, r.positionMs / r.durationMs) : 0);

/** 90% rule: position/duration at or above threshold means watched. */
export function reachedThreshold(positionMs, durationMs, threshold = DEFAULT_THRESHOLD) {
  return durationMs > 0 && positionMs / durationMs >= threshold;
}

/** Next progress record from a playback sample. Crossing the threshold sets watched and clears the resume point. */
export function applySample(prev, { mediaId, episodeId = null, positionMs, durationMs, now, threshold = DEFAULT_THRESHOLD, meta = {} }) {
  const dur = Math.max(0, Math.round(durationMs || 0));
  const pos = Math.max(0, Math.min(Math.round(positionMs || 0), dur || Math.round(positionMs || 0)));
  const crossed = reachedThreshold(pos, dur, threshold);
  return {
    ...(prev || {}),
    ...meta,
    mediaId,
    episodeId: episodeId || null,
    positionMs: crossed ? 0 : pos,
    durationMs: dur,
    watched: crossed ? true : !!prev?.watched,
    dismissed: false,
    updatedAt: now,
  };
}

/** In-progress means started (>=3% and >=60 s), not finished, not watched. */
export function isInProgress(r, threshold = DEFAULT_THRESHOLD) {
  if (!r || r.watched || !(r.durationMs > 0)) return false;
  const ratio = r.positionMs / r.durationMs;
  return r.positionMs >= MIN_START_MS && ratio >= MIN_START_RATIO && ratio < threshold;
}

/** Position (ms) to resume at, or 0. Accepts a record and the new duration (stream may differ). */
export function resumePosition(r, newDurationMs, threshold = DEFAULT_THRESHOLD) {
  if (!r || r.watched || !(r.positionMs > 0)) return 0;
  const ratio = r.durationMs > 0 ? r.positionMs / r.durationMs : 0;
  if (ratio < MIN_START_RATIO || ratio >= Math.min(RESUME_MAX_RATIO, Math.max(threshold, 0.5))) return 0;
  if (newDurationMs > 0 && r.durationMs > 0 && Math.abs(newDurationMs - r.durationMs) / r.durationMs > 0.05) {
    return Math.round(ratio * newDurationMs);
  }
  return r.positionMs;
}

/** Where playback starts, in seconds: the resume point backed up by `rewind` seconds (L037), never below 0. */
export function resumeStartSec(r, newDurationMs, threshold = DEFAULT_THRESHOLD, rewind = 0) {
  const pos = resumePosition(r, newDurationMs, threshold) / 1000;
  return pos > 0 ? Math.max(0, pos - Math.max(0, Number(rewind) || 0)) : 0;
}

// ---------- episodes ----------

const num = (x) => (x === undefined || x === null || x === '' ? NaN : Number(x));

/** Normalise meta.videos into sorted unique episodes {id,season,episode,title,released,thumbnail,overview,runtime}. */
export function normalizeVideos(videos) {
  const map = new Map();
  for (const v of Array.isArray(videos) ? videos : []) {
    if (!v || !v.id) continue;
    const season = Number.isFinite(num(v.season)) ? num(v.season) : 1;
    const episode = Number.isFinite(num(v.episode ?? v.number)) ? num(v.episode ?? v.number) : NaN;
    const ep = {
      id: String(v.id), season, episode,
      title: v.title || v.name || '', released: v.released || '', thumbnail: v.thumbnail || '',
      overview: v.overview || v.description || '', runtime: v.runtime || '',
    };
    const key = Number.isFinite(episode) ? `${season}:${episode}` : `id:${ep.id}`;
    const old = map.get(key);
    const richness = (e) => (e.title ? 1 : 0) + (e.thumbnail ? 1 : 0) + (e.overview ? 1 : 0) + (e.released ? 1 : 0);
    if (!old || richness(ep) > richness(old)) map.set(key, ep);
  }
  return [...map.values()].sort(compareEpisodes);
}

export function compareEpisodes(a, b) {
  const sa = a.season === 0 ? Infinity : a.season; // specials last
  const sb = b.season === 0 ? Infinity : b.season;
  if (sa !== sb) return sa - sb;
  return (Number.isFinite(a.episode) ? a.episode : 1e9) - (Number.isFinite(b.episode) ? b.episode : 1e9);
}

const dayStart = (d) => { const x = new Date(d); x.setHours(0, 0, 0, 0); return x.getTime(); };

/** Episode counts as released when it has no date, an unparseable date, or its local day is today or earlier. */
export function isReleased(ep, now = Date.now()) {
  if (!ep.released) return true;
  const t = Date.parse(ep.released);
  if (Number.isNaN(t)) return true;
  return dayStart(t) <= dayStart(now);
}

/**
 * Next episode to watch: the first released one after the highest watched episode (specials ignored unless watched).
 * getRec(episodeId) returns the progress record. Returns episode, or null when none (all watched / none released).
 */
export function nextEpisode(episodes, getRec, now = Date.now()) {
  let last = -1;
  episodes.forEach((e, i) => { if (e.season !== 0 && getRec(e.id)?.watched) last = Math.max(last, i); });
  for (let i = last + 1; i < episodes.length; i++) {
    const e = episodes[i];
    if (e.season === 0) continue; // specials are never "next"
    if (isReleased(e, now)) return e;
  }
  return null;
}

/** The episode following `epId` in order (for the next-episode overlay), released only. */
export function followingEpisode(episodes, epId, now = Date.now()) {
  const i = episodes.findIndex((e) => e.id === epId);
  if (i < 0) return null;
  for (let j = i + 1; j < episodes.length; j++) {
    if (episodes[j].season === 0) continue;
    if (isReleased(episodes[j], now)) return episodes[j];
    return null;
  }
  return null;
}

/** The episode before `epId` (specials are skipped unless the current one is a special). */
export function previousEpisode(episodes, epId) {
  const i = episodes.findIndex((e) => e.id === epId);
  if (i <= 0) return null;
  const cur = episodes[i];
  for (let j = i - 1; j >= 0; j--) if (cur.season === 0 || episodes[j].season !== 0) return episodes[j];
  return null;
}

/**
 * What should the Play button do?
 * returns {episodeId, label: 'play'|'resume'|'again', rec, episode}
 */
export function playTarget({ type, videos, getRec, threshold = DEFAULT_THRESHOLD, now = Date.now(), mediaId }) {
  const eps = normalizeVideos(videos);
  if (type !== 'series' || !eps.length) {
    const rec = getRec(null);
    if (isInProgress(rec, threshold)) return { episodeId: null, label: 'resume', rec, episode: null };
    if (rec?.watched) return { episodeId: null, label: 'again', rec, episode: null };
    return { episodeId: null, label: 'play', rec, episode: null };
  }
  let best = null;
  for (const e of eps) {
    const r = getRec(e.id);
    if (isInProgress(r, threshold) && (!best || r.updatedAt > best.rec.updatedAt)) best = { e, rec: r };
  }
  if (best) return { episodeId: best.e.id, label: 'resume', rec: best.rec, episode: best.e };
  const anyWatched = eps.some((e) => getRec(e.id)?.watched);
  const next = nextEpisode(eps, (id) => getRec(id), now);
  if (next) return { episodeId: next.id, label: 'play', rec: getRec(next.id), episode: next, started: anyWatched };
  const first = eps.find((e) => e.season !== 0) || eps[0];
  return { episodeId: first.id, label: anyWatched ? 'again' : 'play', rec: getRec(first.id), episode: first };
}

/** Parse "series:S:E" style ids when meta is not available. */
export function parseEpisodeId(id) {
  const m = String(id || '').match(/^(.+):(\d+):(\d+)$/);
  return m ? { mediaId: m[1], season: Number(m[2]), episode: Number(m[3]) } : null;
}

/**
 * Continue Watching cards.
 * progress: array of records; metaFor(mediaId) -> meta|null; options {threshold, now, showUpNext, limit}
 * Returns {cards, needMeta} where needMeta lists series ids whose meta is required to compute up-next.
 */
export function continueWatching(progress, metaFor, { threshold = DEFAULT_THRESHOLD, now = Date.now(), showUpNext = true, limit = 20 } = {}) {
  const byMedia = new Map();
  for (const r of progress) {
    if (!byMedia.has(r.mediaId)) byMedia.set(r.mediaId, []);
    byMedia.get(r.mediaId).push(r);
  }
  const cards = [];
  const needMeta = [];
  for (const [mediaId, recs] of byMedia) {
    const eps = recs.filter((r) => r.episodeId);
    const marker = recs.find((r) => !r.episodeId);
    if (!eps.length) {
      // movie
      const r = marker;
      if (r && !r.dismissed && isInProgress(r, threshold)) {
        cards.push({ mediaId, type: r.type || 'movie', episodeId: null, kind: 'resume', rec: r, updatedAt: r.updatedAt });
      }
      continue;
    }
    const lastPlayed = Math.max(...eps.map((r) => r.updatedAt || 0));
    if (marker?.dismissed && (marker.updatedAt || 0) >= lastPlayed) continue;
    const inProg = eps.filter((r) => isInProgress(r, threshold)).sort((a, b) => b.updatedAt - a.updatedAt)[0];
    if (inProg) {
      cards.push({ mediaId, type: inProg.type || 'series', episodeId: inProg.episodeId, kind: 'resume', rec: inProg, updatedAt: inProg.updatedAt });
      continue;
    }
    if (!showUpNext) continue;
    if (!eps.some((r) => r.watched)) continue;
    const meta = metaFor(mediaId);
    if (!meta) { needMeta.push(mediaId); continue; }
    const videos = normalizeVideos(meta.videos);
    const recMap = new Map(eps.map((r) => [r.episodeId, r]));
    const next = nextEpisode(videos, (id) => recMap.get(id), now);
    if (!next) continue;
    const lastWatched = eps.filter((r) => r.watched).sort((a, b) => b.updatedAt - a.updatedAt)[0];
    cards.push({
      mediaId, type: lastWatched.type || 'series', episodeId: next.id, kind: 'upnext', rec: null, episode: next,
      updatedAt: lastWatched.updatedAt, base: lastWatched,
    });
  }
  cards.sort((a, b) => b.updatedAt - a.updatedAt);
  return { cards: cards.slice(0, limit), needMeta };
}

/** Mark/unmark helper producing a new record. */
export function setWatched(prev, { mediaId, episodeId = null, watched, now, meta = {} }) {
  return {
    ...(prev || { durationMs: 0 }),
    ...meta,
    mediaId, episodeId: episodeId || null,
    positionMs: 0,
    durationMs: prev?.durationMs || 0,
    watched: !!watched,
    dismissed: false,
    updatedAt: now,
  };
}

export const remainingMinutes = (r) => (r && r.durationMs > 0 ? Math.max(1, Math.round((r.durationMs - r.positionMs) / 60000)) : 0);

/** Best-known title/poster for a media id when its details are not cached: cached meta, then the shelf, then the newest progress record (movie or any episode) that carries a name. */
export function titleHint(id, { meta, library = [], progress = [] } = {}) {
  const lib = library.find((i) => i.id === id);
  const recs = progress.filter((r) => r && r.mediaId === id).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  const pick = (k) => meta?.[k] || lib?.[k] || recs.find((r) => r[k])?.[k] || '';
  return { name: pick('name'), poster: pick('poster'), type: meta?.type || lib?.type || recs.find((r) => r.type)?.type || '' };
}
