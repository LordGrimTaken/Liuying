// High-level data access used by screens: combines store + protocol + client.
import * as store from '../store.js';
import { fetchMeta, fetchStreamsFrom, fetchSubtitlesFrom, fetchCatalog, fetchManifest, perSource, sourcesFor, mergeMetas, setTimeoutScale } from './client.js';
import { describeStream } from './streams.js';

export const applyTimeoutSetting = () => setTimeoutScale(store.settings().patient ? 2.5 : 1);

/** Meta from all matching sources, progressively merged (first in source order is primary). */
export function loadMeta(type, id, { signal, onUpdate, originUrl } = {}) {
  let list = sourcesFor(store.sources(), 'meta', type, id);
  if (!list.length && originUrl) {
    const o = store.sources().find((s) => s.url === originUrl && s.enabled);
    if (o) list = [o];
  }
  const results = new Array(list.length).fill(null);
  const errors = [];
  const merged = () => {
    const metas = results.filter(Boolean);
    return metas.length ? mergeMetas(metas) : null;
  };
  const done = perSource(list, (s) => fetchMeta(s, type, id, { signal }), (r) => {
    if (r.ok) { results[list.indexOf(r.source)] = r.value; const m = merged(); if (m) onUpdate?.(m); }
    else errors.push({ source: r.source, error: r.error });
  }, signal).then(() => {
    const m = merged();
    if (m) store.cacheMeta(id, m);
    return { meta: m, errors, sourcesTried: list.length };
  });
  return done;
}

/** Streams from all matching sources in parallel; calls onResult for each source as it settles. */
export function loadStreams(type, id, { signal, onSource, onResult }) {
  const list = sourcesFor(store.sources(), 'stream', type, id);
  list.forEach((s, i) => onSource?.(s, i));
  const opts = { pageSecure: true }; // CSP only allows https media/connect targets, so plain http is always blocked
  return perSource(list, (s) => fetchStreamsFrom(s, type, id, { signal }), (r) => {
    if (r.ok) {
      const streams = r.value.map((raw, i) => describeStream(raw, r.source, i, opts));
      onResult({ source: r.source, ok: true, streams });
    } else onResult({ source: r.source, ok: false, error: r.error });
  }, signal).then(() => list);
}

export function loadSubtitles(type, id, extra, { signal, onResult }) {
  const list = sourcesFor(store.sources(), 'subtitles', type, id);
  return perSource(list, (s) => fetchSubtitlesFrom(s, type, id, extra, { signal }), onResult, signal).then(() => list);
}

export const loadCatalog = (source, cat, extra, opts) => fetchCatalog(source, cat, extra, opts);

/** Refresh manifests at most every 12 h per source. Failures are remembered, never fatal. */
export async function refreshManifests({ force = false } = {}) {
  const now = Date.now();
  let n = 0;
  await Promise.all(store.sources().map(async (s) => {
    if (!force && s.manifest && now - (s.fetchedAt || 0) < 12 * 3600e3) return;
    n++;
    try {
      const { manifest } = await fetchManifest(s.url);
      store.updateSource(s.url, { manifest, fetchedAt: Date.now(), error: null });
    } catch (e) {
      store.updateSource(s.url, { error: { kind: e.kind || 'unknown', message: e.message, at: Date.now() } });
    }
  }));
  return n;
}
