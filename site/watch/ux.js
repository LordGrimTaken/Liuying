// Pure helpers behind the UX pass: featured picks, type filter, calendar agenda, stream sorting, sharing.
// No DOM access here so everything is unit-testable.
import { RES_RANK } from './addon/streams.js';

/** Items worth featuring: need art; prefer ones with a background and a synopsis. Stable, de-duplicated, max n. */
export function pickFeatured(items, n = 5) {
  const seen = new Set();
  const scored = [];
  (Array.isArray(items) ? items : []).forEach((it, i) => {
    if (!it || !it.id || seen.has(it.id) || !(it.background || it.poster)) return;
    seen.add(it.id);
    scored.push({ it, i, score: (it.background ? 2 : 0) + (it.description ? 1 : 0) });
  });
  scored.sort((a, b) => b.score - a.score || a.i - b.i);
  return scored.slice(0, n).map((x) => x.it);
}

/** 'all' | 'movie' | 'series' | other catalog type. */
export const matchesType = (type, filter) => !filter || filter === 'all' || type === filter;

/** Distinct filter chips for the search overlay: always All, Movies, Series; extra types appended. */
export function typeFilters(types) {
  const base = ['all', 'movie', 'series'];
  const extra = [...new Set(types || [])].filter((t) => t && !base.includes(t));
  return [...base, ...extra];
}
export const typeFilterLabel = (t) => ({ all: 'All', movie: 'Movies', series: 'Series' }[t] || (t ? t[0].toUpperCase() + t.slice(1) : 'Other'));

// ---------- calendar ----------
const pad = (n) => String(n).padStart(2, '0');
const keyOf = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const midnight = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());

/**
 * Local calendar day of an episode release string. Midnight-UTC stamps (what addons send for date-only
 * releases) are read as that calendar date, so viewers west of UTC do not see the previous day.
 * Returns a Date at local midnight, or null when unusable.
 */
export function releaseDay(released) {
  if (!released || typeof released !== 'string') return null;
  const t = Date.parse(released);
  if (Number.isNaN(t)) return null;
  const d = new Date(t);
  if (/^\d{4}-\d{2}-\d{2}(T00:00:00(\.0+)?(Z|\+00:00))?$/.test(released.trim())) return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  return midnight(d);
}

export const dayDiff = (day, now) => Math.round((day.getTime() - midnight(now).getTime()) / 86400000);

export function dayLabel(day, now) {
  const diff = dayDiff(day, now);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff === -1) return 'Yesterday';
  return day.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
}

/**
 * Agenda of episodes for shelf series.
 * series: [{id,name,poster,type}], metaFor(id) -> meta with .videos (already-normalised or raw), normalise(videos) -> episodes.
 * Returns {upcoming:[day], recent:[day], series: n, tracked: n} where day = {key, date, diff, label, items:[{mediaId,name,poster,ep,diff}]}.
 * upcoming: today..futureDays ascending. recent: previous pastDays, newest first.
 */
export function buildAgenda(series, metaFor, normalise, now = new Date(), { pastDays = 7, futureDays = 120 } = {}) {
  const days = new Map();
  let tracked = 0;
  for (const s of series) {
    if (s.type && s.type !== 'series') continue;
    const meta = metaFor(s.id);
    if (!meta) continue;
    tracked++;
    for (const ep of normalise(meta.videos)) {
      if (ep.season === 0 && !ep.released) continue;
      const day = releaseDay(ep.released);
      if (!day) continue;
      const diff = dayDiff(day, now);
      if (diff < -pastDays || diff > futureDays) continue;
      const key = keyOf(day);
      if (!days.has(key)) days.set(key, { key, date: day, diff, label: dayLabel(day, now), items: [] });
      days.get(key).items.push({ mediaId: s.id, name: meta.name || s.name || s.id, poster: meta.poster || s.poster || '', ep, diff });
    }
  }
  const byName = (a, b) => a.name.localeCompare(b.name) || (a.ep.season - b.ep.season) || ((a.ep.episode || 0) - (b.ep.episode || 0));
  const all = [...days.values()];
  all.forEach((d) => d.items.sort(byName));
  return {
    upcoming: all.filter((d) => d.diff >= 0).sort((a, b) => a.diff - b.diff),
    recent: all.filter((d) => d.diff < 0).sort((a, b) => b.diff - a.diff),
    series: series.filter((s) => !s.type || s.type === 'series').length,
    tracked,
  };
}

/** "in 3 days" style status for an agenda item. */
export function airStatus(diff) {
  if (diff === 0) return 'Airs today';
  if (diff === 1) return 'Airs tomorrow';
  if (diff > 1) return `In ${diff} days`;
  if (diff === -1) return 'Aired yesterday';
  return `Aired ${-diff} days ago`;
}

// ---------- streams ----------
export const SORT_DEFAULT_DIR = { quality: 'desc', size: 'desc', source: 'asc' };

/** Click on a column header: same key flips direction, new key starts at its natural direction. */
export function nextSort(cur, key) {
  if (cur && cur.key === key) return { key, dir: cur.dir === 'asc' ? 'desc' : 'asc' };
  return { key, dir: SORT_DEFAULT_DIR[key] || 'desc' };
}

const usability = (s) => (s.cls?.playable ? 0 : s.cls?.openUrl ? 1 : 2);

/** Stable sort of described streams by 'quality' | 'size' | 'source'. Playable streams rank first for quality/size. */
export function sortStreamsBy(list, key, dir = SORT_DEFAULT_DIR[key] || 'desc') {
  const sign = dir === 'asc' ? 1 : -1;
  const val = {
    quality: (s) => (RES_RANK[s.res] || 0) * 1e13 + Math.min(s.size || 0, 9e12), // resolution first, size breaks ties (L064)
    size: (s) => s.size || 0,
    source: (s) => String(s.source?.manifest?.name || '').toLowerCase(),
  }[key];
  if (!val) return [...list];
  return list.map((s, i) => ({ s, i })).sort((a, b) => {
    if (key !== 'source') { const u = usability(a.s) - usability(b.s); if (u) return u; }
    const va = val(a.s), vb = val(b.s);
    const c = typeof va === 'string' ? va.localeCompare(vb) : va - vb;
    return c ? c * sign : a.i - b.i;
  }).map((x) => x.s);
}

/** Show the first n; collapse the rest, unless collapsing would hide a single row. */
export function splitTop(list, n = 5) {
  if (list.length <= n + 1) return { top: list, rest: [] };
  return { top: list.slice(0, n), rest: list.slice(n) };
}

export function fmtBytes(b) {
  if (!Number.isFinite(b) || b <= 0) return '';
  if (b >= 1e9) return `${(b / 1e9).toFixed(b >= 1e10 ? 0 : 1)} GB`;
  return `${Math.round(b / 1e6)} MB`;
}

// ---------- sharing ----------
export function shareUrl(origin, type, id, base = '/watch') {
  return `${String(origin).replace(/\/$/, '')}${base}#/title/${encodeURIComponent(type || 'movie')}/${encodeURIComponent(id)}`;
}

// ---------- home layout ----------
/** Sort rows [{key}] by a saved order of keys; unknown keys keep their relative order after the known ones. */
export function orderRows(rows, order) {
  const pos = new Map((Array.isArray(order) ? order : []).map((k, i) => [k, i]));
  return rows.map((r, i) => ({ r, i })).sort((a, b) => {
    const pa = pos.has(a.r.key) ? pos.get(a.r.key) : Infinity, pb = pos.has(b.r.key) ? pos.get(b.r.key) : Infinity;
    return pa === pb ? a.i - b.i : pa - pb;
  }).map((x) => x.r);
}
/** New saved order after moving `key` by delta within the currently displayed keys. */
export function moveKey(displayKeys, key, delta) {
  const a = [...displayKeys];
  const i = a.indexOf(key), j = i + delta;
  if (i < 0 || j < 0 || j >= a.length) return a;
  [a[i], a[j]] = [a[j], a[i]];
  return a;
}

// ---------- search suggestions ----------
/** Quick suggestions for a partial query: prefix matches first, then word-start, then substring. Case-insensitive, deduped. */
export function suggest(q, pools, limit = 6) {
  const needle = String(q || '').trim().toLowerCase();
  if (!needle) return [];
  const seen = new Set();
  const hits = [];
  let order = 0;
  for (const pool of pools) for (const raw of pool || []) {
    const s = String(raw || '').trim();
    const l = s.toLowerCase();
    if (!s || l === needle || seen.has(l)) continue;
    const idx = l.indexOf(needle);
    if (idx < 0) continue;
    seen.add(l);
    const rank = idx === 0 ? 0 : /[\s\-:]/.test(l[idx - 1]) ? 1 : 2;
    hits.push({ s, rank, order: order++ });
  }
  return hits.sort((a, b) => a.rank - b.rank || a.order - b.order).slice(0, limit).map((x) => x.s);
}

/** Plugin/repository manifest link check for the list-only stub: absolute https URL only. */
export function parsePluginUrl(text) {
  try {
    const u = new URL(String(text || '').trim());
    if (u.protocol !== 'https:' || !u.hostname.includes('.')) return null;
    return u.href;
  } catch { return null; }
}

// ---------- search ranking (L006) ----------
/** Lowercase, accents and punctuation folded, whitespace collapsed: "Alien³" and "alien 3" compare alike. */
export const foldTitle = (s) => String(s ?? '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/** 0 exact title, 1 starts with the query as whole words ("Alien 3"), 2 starts with it ("Aliens"), 3 contains it as whole words, 4 contains it, 5 no textual match (a source may match on other fields). */
export function titleScore(name, query) {
  const n = foldTitle(name), q = foldTitle(query);
  if (!q || !n) return 5;
  if (n === q) return 0;
  if (n.startsWith(q + ' ')) return 1;
  if (n.startsWith(q)) return 2;
  if ((' ' + n + ' ').includes(' ' + q + ' ')) return 3;
  if (n.includes(q)) return 4;
  return 5;
}

/** Stable sort: best title match first, source order kept inside a tier. */
export function rankByQuery(items, query) {
  return (Array.isArray(items) ? items : []).map((it, i) => ({ it, i, s: titleScore(it?.name, query) }))
    .sort((a, b) => a.s - b.s || a.i - b.i).map((x) => x.it);
}

/** Exact-title hits across all result sections, de-duplicated by id: the "Best match" row. */
export function exactMatches(lists, query) {
  const seen = new Set();
  const out = [];
  for (const items of lists) for (const it of items || []) {
    if (!it?.id || seen.has(it.id) || titleScore(it.name, query) !== 0) continue;
    seen.add(it.id); out.push(it);
  }
  return out;
}

/** Concrete suggestions for an empty result: shorter query, no year, fewer words. Never repeats the query. */
export function searchTips(query, { sources = 0, searchable = 0 } = {}) {
  const q = String(query || '').trim();
  const tips = [];
  const noYear = q.replace(/\(?\b(19|20)\d{2}\b\)?/g, '').replace(/\s+/g, ' ').trim();
  if (noYear && noYear !== q) tips.push(`Remove the year: try “${noYear}”.`);
  const words = (noYear || q).split(/\s+/).filter(Boolean);
  if (words.length > 2) tips.push(`Use fewer words: try “${words.slice(0, 2).join(' ')}”.`);
  else if (words.length === 1 && words[0].length > 4) tips.push(`Try a shorter start: “${words[0].slice(0, Math.max(3, words[0].length - 2))}”.`);
  tips.push('Check the spelling, or search the original title.');
  const src = !searchable ? (sources ? 'None of your sources has a searchable catalog. Add one that supports search.' : 'Add a source that supports search.')
    : `Only ${searchable} searchable catalog${searchable === 1 ? '' : 's'} answered. Another source may have it.`;
  return [...tips.slice(0, 2), src];
}

// ---------- surprise me (L010) ----------
/** Random unwatched title (falls back to anything). rng is injectable for tests. */
export function pickSurprise(items, { isWatched = () => false, rng = Math.random, avoid = '' } = {}) {
  const all = (Array.isArray(items) ? items : []).filter((i) => i && i.id);
  if (!all.length) return null;
  let pool = all.filter((i) => !isWatched(i));
  if (!pool.length) pool = all;
  if (pool.length > 1 && avoid) pool = pool.filter((i) => i.id !== avoid);
  return pool[Math.min(pool.length - 1, Math.floor(rng() * pool.length))];
}

// ---------- theme (L099) ----------
export const THEME_CHOICES = ['system', 'dark', 'black', 'paper'];
/** 'system' follows the OS: dark -> Ink, light -> Paper. */
export const resolveTheme = (theme, prefersDark) => (theme === 'system' ? (prefersDark ? 'dark' : 'paper') : (THEME_CHOICES.includes(theme) ? theme : 'dark'));

// ---------- history (L023) and exports (L031) ----------
/** Records that count as "watched or started": hidden entries (position 0, unwatched) are the removed ones. */
export const isHistoryRecord = (r) => !!r && !!r.mediaId && (r.watched || r.positionMs > 0);

/**
 * Chronological history, newest first, grouped by local day.
 * hint(mediaId) -> {name, poster, type}. Returns [{key, label, items:[{rec, name, type, ep:{season,episode}|null}]}].
 */
export function buildHistory(progress, hint, now = new Date(), parseEp = () => null) {
  const recs = (Array.isArray(progress) ? progress : []).filter(isHistoryRecord).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  const days = new Map();
  for (const rec of recs) {
    const d = new Date(rec.updatedAt || 0);
    const key = keyOf(d);
    if (!days.has(key)) days.set(key, { key, label: dayLabel(midnight(d), now), items: [] });
    const h = hint(rec.mediaId) || {};
    const pe = rec.episodeId ? parseEp(rec.episodeId) : null;
    days.get(key).items.push({ rec, name: rec.name || h.name || rec.mediaId, type: rec.type || h.type || (rec.episodeId ? 'series' : 'movie'), ep: pe ? { season: pe.season, episode: pe.episode } : null });
  }
  return [...days.values()];
}

/** RFC 4180 CSV. Cells starting with = + - @ are prefixed with ' so spreadsheets never run them as formulas. */
export function toCsv(headers, rows) {
  const cell = (v) => {
    let s = v === null || v === undefined ? '' : String(v);
    if (/^[=+\-@\t\r]/.test(s)) s = "'" + s;
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return [headers, ...rows].map((r) => r.map(cell).join(',')).join('\r\n') + '\r\n';
}
const iso = (ms) => (ms ? new Date(ms).toISOString() : '');
export const LIBRARY_COLUMNS = ['id', 'type', 'title', 'year', 'added'];
export const libraryCsvRows = (library) => library.map((i) => [i.id, i.type, i.name, i.year || '', iso(i.addedAt)]);
export const HISTORY_COLUMNS = ['title', 'type', 'media_id', 'episode_id', 'season', 'episode', 'watched', 'position_seconds', 'duration_seconds', 'last_played'];
export function historyCsvRows(days) {
  return days.flatMap((d) => d.items.map(({ rec, name, type, ep }) => [name, type, rec.mediaId, rec.episodeId || '', ep?.season ?? '', ep?.episode ?? '', rec.watched ? 'yes' : 'no', Math.round((rec.positionMs || 0) / 1000), Math.round((rec.durationMs || 0) / 1000), iso(rec.updatedAt)]));
}
export const historyJson = (days) => days.flatMap((d) => d.items.map(({ rec, name, type, ep }) => ({ title: name, type, mediaId: rec.mediaId, episodeId: rec.episodeId || null, season: ep?.season ?? null, episode: ep?.episode ?? null, watched: !!rec.watched, positionMs: rec.positionMs || 0, durationMs: rec.durationMs || 0, lastPlayed: iso(rec.updatedAt) })));

// ---------- stream list filters (L065) ----------
/** Distinct qualities present, best first. */
export const qualityOptions = (list) => [...new Set(list.map((s) => s.res).filter(Boolean))].sort((a, b) => (RES_RANK[b] || 0) - (RES_RANK[a] || 0));
/** Distinct languages present (see streamLanguages in addon/streams.js), most common first. */
export function languageOptions(list) {
  const n = new Map();
  for (const s of list) for (const l of s.langs || []) n.set(l, (n.get(l) || 0) + 1);
  return [...n.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([l]) => l);
}
export const filterStreams = (list, { quality = '', lang = '' } = {}) => list.filter((s) => (!quality || s.res === quality) && (!lang || (s.langs || []).includes(lang)));

// ---------- shelf status (L005) ----------
export const SHELF_STATUS = [['all', 'All'], ['planned', 'Planned'], ['watching', 'Watching'], ['completed', 'Completed']];
/** Planned = nothing played, Watching = started, Completed = watched. state: {any, prog}. */
export const shelfStatus = (st) => (st.prog ? 'watching' : st.any ? 'completed' : 'planned');

// ---------- calendar badge (L115) ----------
/** Count for the nav badge: unwatched episodes that aired in the last 7 days or air today. */
export function calendarBadgeCount(agenda, isWatched = () => false) {
  let n = 0;
  for (const d of [...(agenda.recent || []), ...(agenda.upcoming || [])]) {
    if (d.diff > 0) continue;
    for (const it of d.items) if (!isWatched(it.mediaId, it.ep.id)) n++;
  }
  return n;
}
/** Apply the calendar toggles to an agenda without mutating it. */
export function filterAgenda(agenda, { hideWatched = false, isWatched = () => false } = {}) {
  if (!hideWatched) return agenda;
  const f = (days) => days.map((d) => ({ ...d, items: d.items.filter((it) => !isWatched(it.mediaId, it.ep.id)) })).filter((d) => d.items.length);
  return { ...agenda, upcoming: f(agenda.upcoming), recent: f(agenda.recent) };
}

// ---------- sync status (L032) ----------
/** "just now", "3 min ago", "2 h ago", "yesterday" or a date. */
export function relTime(ts, now = Date.now()) {
  if (!ts) return 'never';
  const s = Math.max(0, Math.round((now - ts) / 1000));
  if (s < 45) return 'just now';
  if (s < 90) return '1 min ago';
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.round(h / 24);
  if (d === 1) return 'yesterday';
  if (d < 14) return `${d} days ago`;
  return new Date(ts).toLocaleDateString();
}
/** Number of things waiting to upload, from the store's sync markers. */
export function pendingCount(s) {
  return (s.sources ? 1 : 0) + (s.settings ? 1 : 0) + Object.keys(s.libAdd || {}).length + (s.libRemove || []).length + Object.keys(s.progress || {}).length;
}
/** Colour-independent sync state: a glyph, a short word and a sentence for screen readers (L104). */
export const SYNC_FACE = {
  idle: { glyph: '✓', word: 'Synced', sr: 'Synced' },
  syncing: { glyph: '↻', word: 'Syncing', sr: 'Syncing now' },
  offline: { glyph: '–', word: 'Offline', sr: 'Offline, will retry' },
  error: { glyph: '!', word: 'Problem', sr: 'Sync problem, will retry' },
  waking: { glyph: '…', word: 'Waking', sr: 'Waking the server, this can take up to a minute' },
  checking: { glyph: '…', word: 'Checking', sr: 'Checking sign-in' },
  signedout: { glyph: '', word: 'Signed out', sr: 'Signed out' },
};
export const syncFace = (state, waking = false) => SYNC_FACE[waking && state !== 'idle' ? 'waking' : state] || SYNC_FACE.idle;
