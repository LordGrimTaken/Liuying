// Network layer for addons: fetch with timeouts, error classification, per-source isolation.
import {
  buildResourceUrl, buildCatalogUrl, normalizeManifestUrl, validateManifest, resourceApplies, isSearchable,
} from './protocol.js';

export class AddonError extends Error {
  constructor(kind, message, extra = {}) { super(message); this.name = 'AddonError'; this.kind = kind; Object.assign(this, extra); }
}

export const TIMEOUT = { manifest: 12000, catalog: 15000, meta: 15000, stream: 15000, subtitles: 12000 };
let timeoutScale = 1;
export const setTimeoutScale = (n) => { timeoutScale = n > 0 ? n : 1; };

/** Human text for an error, explaining CORS honestly. */
export function describeError(e) {
  if (!e) return 'Unknown error';
  switch (e.kind) {
    case 'cors': case 'network':
      if (typeof navigator !== 'undefined' && navigator.onLine === false) return "You're offline. Reconnect and try again.";
      return 'This source did not answer. It may be offline, or it does not allow web access (browsers require the source to permit it).';
    case 'timeout': return 'This source took too long to respond.';
    case 'http': return `This source answered with an error (HTTP ${e.status}).`;
    case 'json': return 'This source did not return valid data.';
    case 'invalid': return e.message;
    case 'abort': return 'Cancelled.';
    default: return e.message || 'Unknown error';
  }
}

/** In the desktop shell, addon requests go through the local proxy so a server without CORS headers still works. */
function viaDesktopProxy(url) {
  const base = globalThis.liuyingDesktop?.proxy;
  if (!base || typeof url !== 'string' || !/^https?:\/\//i.test(url)) return url;
  return base + encodeURIComponent(url);
}

/** Fetch JSON with timeout + external abort signal. Lenient about content type. */
export async function fetchJson(url, { timeout = 15000, signal, fetchImpl } = {}) {
  const f = fetchImpl || globalThis.fetch.bind(globalThis);
  const target = fetchImpl ? url : viaDesktopProxy(url);
  const ctl = new AbortController();
  let timedOut = false;
  const t = setTimeout(() => { timedOut = true; ctl.abort(); }, timeout * timeoutScale);
  const onAbort = () => ctl.abort();
  if (signal) { if (signal.aborted) ctl.abort(); else signal.addEventListener('abort', onAbort, { once: true }); }
  try {
    let res;
    const attempt = (u) => f(u, { signal: ctl.signal, credentials: 'omit', redirect: 'follow', headers: { Accept: 'application/json, */*;q=0.5' } });
    try {
      res = await attempt(target);
    } catch (e) {
      if (timedOut) throw new AddonError('timeout', 'timeout');
      if (signal?.aborted) throw new AddonError('abort', 'aborted');
      // The desktop proxy can fail for its own reasons; the addon may still answer directly.
      if (target !== url) {
        try { res = await attempt(url); } catch { throw new AddonError('network', 'network error'); }
      } else {
        throw new AddonError('network', 'network error');
      }
    }
    if (!res.ok) throw new AddonError('http', `HTTP ${res.status}`, { status: res.status });
    let text;
    try { text = await res.text(); } catch { throw new AddonError(timedOut ? 'timeout' : 'network', 'read failed'); }
    try { return JSON.parse(text); } catch { throw new AddonError('json', 'invalid json'); }
  } finally {
    clearTimeout(t);
    signal?.removeEventListener?.('abort', onAbort);
  }
}

export async function fetchManifest(input, opts = {}) {
  const url = normalizeManifestUrl(input);
  const json = await fetchJson(url, { timeout: TIMEOUT.manifest, ...opts });
  let manifest;
  try { manifest = validateManifest(json); } catch (e) { throw new AddonError('invalid', e.message); }
  return { url, manifest };
}

const arr = (x) => (Array.isArray(x) ? x : []);

export async function fetchCatalog(source, cat, extra, opts = {}) {
  const j = await fetchJson(buildCatalogUrl(source.url, cat.type, cat.id, extra), { timeout: TIMEOUT.catalog, ...opts });
  return arr(j?.metas).filter((m) => m && m.id);
}

export async function fetchMeta(source, type, id, opts = {}) {
  const j = await fetchJson(buildResourceUrl(source.url, 'meta', type, id), { timeout: TIMEOUT.meta, ...opts });
  if (!j?.meta || typeof j.meta !== 'object') throw new AddonError('invalid', 'No metadata for this title.');
  return j.meta;
}

export async function fetchStreamsFrom(source, type, id, opts = {}) {
  const j = await fetchJson(buildResourceUrl(source.url, 'stream', type, id), { timeout: TIMEOUT.stream, ...opts });
  return arr(j?.streams);
}

export async function fetchSubtitlesFrom(source, type, id, extra, opts = {}) {
  const j = await fetchJson(buildResourceUrl(source.url, 'subtitles', type, id, extra), { timeout: TIMEOUT.subtitles, ...opts });
  return arr(j?.subtitles).filter((s) => s && typeof s.url === 'string');
}

/** Enabled sources that have a manifest and declare the resource for (type,id). */
export function sourcesFor(sources, resource, type, id) {
  return sources.filter((s) => s.enabled && s.manifest && resourceApplies(s.manifest, resource, type, id));
}

/**
 * Run `task(source)` on many sources in parallel; each reports separately.
 * onResult({source, ok, value|error}) fires as each finishes. Returns Promise that settles when all are done.
 */
export function perSource(list, task, onResult, signal) {
  return Promise.all(list.map(async (source) => {
    try {
      const value = await task(source);
      if (!signal?.aborted) onResult({ source, ok: true, value });
    } catch (error) {
      if (error?.kind === 'abort' || signal?.aborted) return;
      onResult({ source, ok: false, error });
    }
  }));
}

/** Merge metas: first wins, later fill gaps ("merge, don't overwrite"). */
export function mergeMetas(metas) {
  const out = {};
  for (const m of metas) {
    for (const [k, v] of Object.entries(m || {})) {
      const empty = v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);
      const cur = out[k];
      const curEmpty = cur === undefined || cur === null || cur === '' || (Array.isArray(cur) && cur.length === 0);
      if (curEmpty && !empty) out[k] = v;
    }
  }
  return out;
}

export const searchableCatalogs = (sources) => {
  const out = [];
  for (const s of sources) {
    if (!s.enabled || !s.manifest) continue;
    for (const cat of s.manifest.catalogs) if (isSearchable(cat)) out.push({ source: s, cat });
  }
  return out;
};
