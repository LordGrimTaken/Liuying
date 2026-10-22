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
    quality: (s) => RES_RANK[s.res] || 0,
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
