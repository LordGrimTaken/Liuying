// Local-first state. Everything lives in memory and is mirrored to IndexedDB (localStorage fallback).
// Mutations mark "dirty" markers that sync.js drains when signed in.
import { progressKey } from './progress.js';
import { shortId, debounce } from './util.js';

export const DEFAULT_SETTINGS = {
  theme: 'dark', reduceMotion: false, posterSize: 'm', titlesUnder: true, showBadges: true,
  showContinue: true, showUpNext: true, hero: true, heroCatalog: '', hideAdult: true, hiddenCatalogs: {}, homeOrder: [], accent: 'vermilion', fontScale: 'm',
  resume: 'auto', threshold: 0.9, autoNext: true, countdown: 10, seekStep: 10, speed: 1, autoSelect: false,
  streamGroup: 'source', streamSort: 'default', hideUnplayable: false, patient: false,
  subLangs: ['en'], subMode: 'on', subBold: false, subSize: 100, subColor: '#ffffff', subBg: 'shadow', subBgOpacity: 0.6, subOffset: 8,
  libSort: 'added', libView: 'grid', saveHistory: true, recentSearches: true,
};

const DB = 'liuying-web';
const KEYS = ['sources', 'library', 'progress', 'settings', 'meta', 'recent', 'sync', 'prefs'];

const state = {
  sources: [], library: [], progress: {}, settings: { ...DEFAULT_SETTINGS }, meta: {}, recent: [],
  sync: { sources: false, settings: false, libAdd: {}, libRemove: [], progress: {}, pulledAt: 0 },
  prefs: { lastStream: {}, subLang: {}, dismissedBanner: false },
};
let db = null;
let persistOk = true;
const listeners = new Set();
const lsKey = (k) => `liuying.web.${k}`;

function openDb() {
  return new Promise((resolve) => {
    try {
      const req = indexedDB.open(DB, 1);
      req.onupgradeneeded = () => req.result.createObjectStore('kv');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
      req.onblocked = () => resolve(null);
    } catch { resolve(null); }
  });
}
const idbGet = (k) => new Promise((res) => {
  try {
    const r = db.transaction('kv').objectStore('kv').get(k);
    r.onsuccess = () => res(r.result); r.onerror = () => res(undefined);
  } catch { res(undefined); }
});
const idbPut = (k, v) => new Promise((res) => {
  try {
    const tx = db.transaction('kv', 'readwrite');
    tx.objectStore('kv').put(v, k);
    tx.oncomplete = () => res(true); tx.onerror = () => res(false); tx.onabort = () => res(false);
  } catch { res(false); }
});

export async function init() {
  db = await openDb();
  for (const k of KEYS) {
    let rec;
    if (db) rec = await idbGet(k);
    // a synchronous localStorage copy is written on page hide; use whichever is newer
    let ls;
    try { const t = localStorage.getItem(lsKey(k)); if (t) ls = JSON.parse(t); } catch { /* ignore */ }
    if (ls && (!rec || (ls.t || 0) > (rec.t || 0))) { rec = ls; dirtyKeys.add(k); }
    else if (ls) { try { localStorage.removeItem(lsKey(k)); } catch { /* ignore */ } }
    const v = rec?.v;
    if (v !== undefined && v !== null) {
      state[k] = Array.isArray(state[k]) ? (Array.isArray(v) ? v : state[k])
        : (typeof state[k] === 'object' ? { ...state[k], ...v } : v);
    }
  }
  state.settings = { ...DEFAULT_SETTINGS, ...state.settings };
  persistOk = !!db;
  if (dirtyKeys.size) flushSoon();
}

const dirtyKeys = new Set();
let flushing = Promise.resolve();
function flushNow({ sync = false } = {}) {
  const keys = [...dirtyKeys]; dirtyKeys.clear();
  const t = Date.now();
  const jobs = keys.map((k) => {
    const rec = { t, v: JSON.parse(JSON.stringify(state[k])) };
    if (sync) { try { localStorage.setItem(lsKey(k), JSON.stringify(rec)); } catch { /* quota */ } }
    if (!db) { if (!sync) { try { localStorage.setItem(lsKey(k), JSON.stringify(rec)); } catch { /* ignore */ } } return Promise.resolve(); }
    return idbPut(k, rec).then((ok) => {
      if (ok) { if (!sync) { try { localStorage.removeItem(lsKey(k)); } catch { /* ignore */ } } return; }
      try { localStorage.setItem(lsKey(k), JSON.stringify(rec)); } catch { /* ignore */ }
    });
  });
  flushing = Promise.all(jobs);
  return flushing;
}
const flushSoon = debounce(() => flushNow(), 200);
export const flush = () => { flushSoon.cancel(); return flushNow(); };
export const persistent = () => persistOk;

function touch(key, detail) {
  dirtyKeys.add(key);
  flushSoon();
  for (const fn of [...listeners]) { try { fn(key, detail); } catch (e) { console.error(e); } }
}
export function subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); }
if (typeof addEventListener === 'function') {
  addEventListener('pagehide', () => { flushSoon.cancel(); flushNow({ sync: true }); });
  document.addEventListener?.('visibilitychange', () => { if (document.visibilityState === 'hidden') { flushSoon.cancel(); flushNow({ sync: true }); } });
}

// ---------- settings ----------
export const settings = () => state.settings;
export function setSettings(patch, { fromSync = false } = {}) {
  state.settings = { ...state.settings, ...patch };
  if (!fromSync) state.sync.settings = true;
  touch('settings'); touch('sync');
}
export function replaceSettings(obj) { state.settings = { ...DEFAULT_SETTINGS, ...obj }; touch('settings'); }

// ---------- sources ----------
export const sources = () => state.sources;
export const sourceById = (id) => state.sources.find((s) => shortId(s.url) === id);
export const sourceId = (s) => shortId(s.url);

export function setSources(list, { fromSync = false } = {}) {
  state.sources = list;
  if (!fromSync) state.sync.sources = true;
  touch('sources'); touch('sync');
}
export function addSource(url, manifest) {
  const existing = state.sources.find((s) => s.url === url);
  if (existing) {
    existing.manifest = manifest; existing.fetchedAt = Date.now(); existing.error = null;
    setSources([...state.sources]);
    return existing;
  }
  const s = { url, enabled: true, manifest, fetchedAt: Date.now(), addedAt: Date.now(), error: null };
  setSources([...state.sources, s]);
  return s;
}
export function updateSource(url, patch, { sync = false } = {}) {
  const s = state.sources.find((x) => x.url === url);
  if (!s) return;
  Object.assign(s, patch);
  if (sync) state.sync.sources = true;
  touch('sources'); if (sync) touch('sync');
}
export const removeSource = (url) => setSources(state.sources.filter((s) => s.url !== url));
export function moveSource(url, delta) {
  const i = state.sources.findIndex((s) => s.url === url);
  const j = i + delta;
  if (i < 0 || j < 0 || j >= state.sources.length) return;
  const a = [...state.sources];
  [a[i], a[j]] = [a[j], a[i]];
  setSources(a);
}
export const enabledSources = () => state.sources.filter((s) => s.enabled && s.manifest);

// ---------- library ----------
export const library = () => state.library;
export const inLibrary = (id) => state.library.some((i) => i.id === id);
export function addToLibrary(item) {
  if (inLibrary(item.id)) return;
  const it = { id: item.id, type: item.type, name: item.name || item.id, poster: item.poster || '', background: item.background || '', year: item.year || '', addedAt: Date.now() };
  state.library = [it, ...state.library];
  state.sync.libAdd[it.id] = it;
  state.sync.libRemove = state.sync.libRemove.filter((i) => i !== it.id);
  touch('library'); touch('sync');
}
export function removeFromLibrary(id) {
  state.library = state.library.filter((i) => i.id !== id);
  delete state.sync.libAdd[id];
  if (!state.sync.libRemove.includes(id)) state.sync.libRemove.push(id);
  touch('library'); touch('sync');
}
export function setLibrary(list) { state.library = list; touch('library'); }

// ---------- progress ----------
export const allProgress = () => Object.values(state.progress);
export const getProgress = (mediaId, episodeId) => state.progress[progressKey(mediaId, episodeId)];
export function putProgress(rec, { fromSync = false } = {}) {
  const k = progressKey(rec.mediaId, rec.episodeId);
  state.progress[k] = rec;
  if (!fromSync) state.sync.progress[k] = true;
  touch('progress', { key: k }); if (!fromSync) touch('sync');
}
export function setAllProgress(list) {
  state.progress = {};
  for (const r of list) state.progress[progressKey(r.mediaId, r.episodeId)] = r;
  touch('progress');
}
export function clearProgress() { state.progress = {}; state.sync.progress = {}; touch('progress'); touch('sync'); }

// ---------- meta cache ----------
export const cachedMeta = (id) => state.meta[id]?.meta || null;
export function cacheMeta(id, meta) {
  state.meta[id] = { meta, at: Date.now() };
  const ids = Object.keys(state.meta);
  if (ids.length > 150) {
    ids.sort((a, b) => state.meta[a].at - state.meta[b].at).slice(0, ids.length - 120).forEach((k) => delete state.meta[k]);
  }
  dirtyKeys.add('meta'); flushSoon();
}
export function clearMetaCache() { state.meta = {}; touch('meta'); }
export const metaCacheSize = () => JSON.stringify(state.meta).length;

// ---------- recent searches ----------
export const recentSearches = () => state.recent;
export function pushRecent(q) {
  state.recent = [q, ...state.recent.filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, 10);
  touch('recent');
}
export const removeRecent = (q) => { state.recent = state.recent.filter((x) => x !== q); touch('recent'); };
export const clearRecent = () => { state.recent = []; touch('recent'); };

// ---------- prefs (device-local) ----------
export const prefs = () => state.prefs;
export function setPref(k, v) { state.prefs = { ...state.prefs, [k]: v }; touch('prefs'); }
export function setPrefMap(name, key, value) {
  const m = { ...(state.prefs[name] || {}) };
  if (value === undefined) delete m[key]; else m[key] = value;
  setPref(name, m);
}

// ---------- sync bookkeeping ----------
export const syncState = () => state.sync;
export function patchSync(fn) { fn(state.sync); touch('sync'); }

export async function resetAll() {
  for (const k of KEYS) {
    if (k === 'settings') state.settings = { ...DEFAULT_SETTINGS };
    else if (k === 'sync') state.sync = { sources: false, settings: false, libAdd: {}, libRemove: [], progress: {}, pulledAt: 0 };
    else if (k === 'prefs') state.prefs = { lastStream: {}, subLang: {}, dismissedBanner: false };
    else state[k] = Array.isArray(state[k]) ? [] : {};
    dirtyKeys.add(k);
  }
  await flush();
  for (const fn of [...listeners]) fn('reset');
}

export const _state = state; // for tests/debugging
