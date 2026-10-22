// Pure merge logic for local <-> server sync. Last-writer-wins for progress; union for library.
import { progressKey } from './progress.js';

/** Progress: newest updatedAt wins per key. Local-only fields (name, poster, type) survive when remote wins. */
export function mergeProgress(local, remote) {
  const map = new Map();
  for (const r of local) map.set(progressKey(r.mediaId, r.episodeId), r);
  const toPush = [];
  const seen = new Set();
  let changed = false;
  for (const r of remote) {
    const k = progressKey(r.mediaId, r.episodeId);
    seen.add(k);
    const cur = map.get(k);
    if (!cur) { map.set(k, { ...r, episodeId: r.episodeId || null }); changed = true; continue; }
    if ((r.updatedAt || 0) > (cur.updatedAt || 0)) {
      map.set(k, { ...cur, ...r, episodeId: r.episodeId || null });
      changed = true;
    } else if ((cur.updatedAt || 0) > (r.updatedAt || 0)) toPush.push(cur);
  }
  for (const [k, r] of map) if (!seen.has(k)) {
    // exists locally, never seen remotely -> push
    if (!toPush.includes(r)) toPush.push(r);
  }
  return { merged: [...map.values()], toPush, changed };
}

/** Library: union of both sides minus ids removed locally and not yet synced. Newest-added first. */
export function mergeLibrary(local, remote, pendingRemovals = new Set()) {
  const map = new Map();
  for (const it of remote) if (!pendingRemovals.has(it.id)) map.set(it.id, it);
  const toAdd = [];
  for (const it of local) {
    if (pendingRemovals.has(it.id)) continue;
    const r = map.get(it.id);
    if (!r) { map.set(it.id, it); toAdd.push(it); }
    else map.set(it.id, { ...r, ...Object.fromEntries(Object.entries(it).filter(([k, v]) => v && !(k in r && r[k]))) });
  }
  const merged = [...map.values()].sort((a, b) => (b.addedAt || 0) - (a.addedAt || 0));
  const toRemove = [...pendingRemovals];
  return { merged, toAdd, toRemove };
}

/**
 * Sources: server list wins (order and membership) unless local has unsynced changes; then merge by url,
 * server order first, local additions appended, local enabled flags preserved.
 */
export function mergeSources(local, remote, localDirty) {
  const byUrl = new Map(local.map((s) => [s.url, s]));
  if (!localDirty) {
    return remote.map((r) => ({ ...(byUrl.get(r.url) || { url: r.url }), url: r.url, enabled: r.enabled !== false }));
  }
  const out = [];
  const used = new Set();
  for (const r of remote) {
    const l = byUrl.get(r.url);
    out.push(l ? { ...l } : { url: r.url, enabled: r.enabled !== false });
    used.add(r.url);
  }
  for (const l of local) if (!used.has(l.url)) out.push({ ...l });
  return out;
}

/** The shape sent to PUT /api/sync/sources. */
export const sourcesPayload = (sources) => sources.map((s) => ({ url: s.url, enabled: s.enabled !== false }));

export function sameSources(a, b) {
  if (a.length !== b.length) return false;
  return a.every((s, i) => s.url === b[i].url && (s.enabled !== false) === (b[i].enabled !== false));
}

/** Settings: server values apply unless local has unsynced edits (then local keys win). */
export function mergeSettings(local, remote, localDirty) {
  if (!remote || typeof remote !== 'object') return { ...local };
  return localDirty ? { ...remote, ...local } : { ...local, ...remote };
}

/** Convert a local progress record to the wire format. positionMs is clamped to durationMs. */
export function progressWire(r) {
  const durationMs = Math.max(0, Math.round(r.durationMs || 0));
  return {
    mediaId: r.mediaId,
    episodeId: r.episodeId || null,
    positionMs: Math.min(Math.max(0, Math.round(r.positionMs || 0)), durationMs),
    durationMs,
    watched: !!r.watched,
    dismissed: !!r.dismissed,
    updatedAt: Math.round(r.updatedAt || 0),
  };
}

export function chunk(arr, n) {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
}

/** Exponential backoff delay for retry number n (0-based), capped. */
export const backoffMs = (n, base = 2000, cap = 60000) => Math.min(cap, base * 2 ** Math.max(0, n));
