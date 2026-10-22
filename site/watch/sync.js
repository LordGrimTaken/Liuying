// Account sync: same-origin API, local-first, never blocks the UI.
import * as store from './store.js';
import { mergeProgress, mergeLibrary, mergeSources, mergeSettings, sourcesPayload, progressWire, chunk, backoffMs } from './sync-merge.js';
import { progressKey } from './progress.js';
import { debounce } from './util.js';
import { pendingCount } from './ux.js';

const status = { state: 'checking', user: null, lastSync: 0, error: '', waking: false };
const subs = new Set();
const emit = () => subs.forEach((f) => { try { f(getStatus()); } catch (e) { console.error(e); } });
export const subscribeStatus = (f) => { subs.add(f); f(getStatus()); return () => subs.delete(f); };
export const getStatus = () => ({ ...status, pending: pendingCount(store.syncState()) });
export const isSignedIn = () => !!status.user;

let failures = 0;
let retryTimer = null;
let pushing = false;
let hooks = { onRemoteChange: () => {} };
export const setHooks = (h) => { hooks = { ...hooks, ...h }; };

class ApiError extends Error { constructor(status, message) { super(message); this.status = status; } }

async function api(method, path, body) {
  const init = { method, credentials: 'same-origin', headers: { Accept: 'application/json' } };
  if (method !== 'GET') { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body ?? {}); }
  let res;
  // the free host sleeps when idle: say so instead of looking frozen (L094)
  const wake = setTimeout(() => { status.waking = true; emit(); }, 3000);
  try { res = await fetch(path, init); } catch (e) { throw new ApiError(0, 'offline'); } finally { clearTimeout(wake); if (status.waking) { status.waking = false; emit(); } }
  if (res.status === 401) throw new ApiError(401, 'signed out');
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { const j = await res.json(); msg = j?.error?.message || j?.message || msg; } catch { /* ignore */ }
    throw new ApiError(res.status, msg);
  }
  if (res.status === 204) return {};
  try { return await res.json(); } catch { return {}; }
}

function setSignedOut() {
  status.state = 'signedout'; status.user = null; emit();
}

export async function init() {
  try {
    const me = await api('GET', '/api/auth/me?soft=1');
    status.user = me?.user?.username ? { username: me.user.username } : null;
  } catch (e) {
    if (e.status === 0) { status.state = 'offline'; status.user = null; emit(); return; }
    setSignedOut(); return;
  }
  if (!status.user) { setSignedOut(); return; }
  status.state = 'syncing'; emit();
  await pull();
}

const toMs = (v) => (typeof v === 'number' ? v : (Date.parse(v) || 0));

export async function pull() {
  if (!status.user) return;
  try {
    const st = await api('GET', '/api/sync/state');
    const sync = store.syncState();
    const fingerprint = () => JSON.stringify([store.sources(), store.library().map((i) => i.id), store.allProgress().map((r) => [r.mediaId, r.episodeId, r.updatedAt]), store.settings()]);
    const before = fingerprint();
    // sources
    const remoteSources = Array.isArray(st.sources) ? st.sources : [];
    store.setSources(mergeSources(store.sources(), remoteSources, sync.sources), { fromSync: true });
    // library
    const remoteLib = (Array.isArray(st.library) ? st.library : []).map((i) => ({ ...i, addedAt: toMs(i.addedAt) }));
    // Items this device already synced that the server no longer has were removed elsewhere: drop them here
    // (unless re-added locally and not yet pushed). First sync has no history, so it is a plain union.
    const known = new Set(sync.libSynced || []);
    const remoteIds = new Set(remoteLib.map((i) => i.id));
    const localLib = store.library().filter((i) => !(known.has(i.id) && !remoteIds.has(i.id) && !(i.id in sync.libAdd)));
    const lib = mergeLibrary(localLib, remoteLib, new Set(sync.libRemove));
    store.setLibrary(lib.merged);
    store.patchSync((s) => { for (const it of lib.toAdd) s.libAdd[it.id] = it; s.libSynced = [...remoteIds]; });
    // progress
    const remoteProg = (Array.isArray(st.progress) ? st.progress : []).map((r) => ({ ...r, episodeId: r.episodeId || null, updatedAt: toMs(r.updatedAt) }));
    const pr = mergeProgress(store.allProgress(), remoteProg);
    store.setAllProgress(pr.merged);
    store.patchSync((s) => { for (const r of pr.toPush) s.progress[progressKey(r.mediaId, r.episodeId)] = true; });
    // settings
    if (st.settings && typeof st.settings === 'object' && Object.keys(st.settings).length) {
      store.replaceSettings(mergeSettings(store.settings(), st.settings, sync.settings));
    }
    store.patchSync((s) => { s.pulledAt = Date.now(); });
    status.state = 'idle'; status.lastSync = Date.now(); status.error = ''; failures = 0; emit();
    if (fingerprint() !== before) hooks.onRemoteChange();
    schedulePush.flush();
  } catch (e) { handleFailure(e); }
}

function handleFailure(e) {
  if (e.status === 401) { setSignedOut(); return; }
  failures++;
  status.state = e.status === 0 ? 'offline' : 'error';
  status.error = e.message || '';
  emit();
  clearTimeout(retryTimer);
  retryTimer = setTimeout(() => { if (status.user) { hasPending() ? push() : pull(); } }, backoffMs(failures - 1));
}

export function hasPending() {
  const s = store.syncState();
  return s.sources || s.settings || Object.keys(s.libAdd).length || s.libRemove.length || Object.keys(s.progress).length;
}

export async function push() {
  if (!status.user || pushing) return;
  if (!hasPending()) { return; }
  pushing = true;
  status.state = 'syncing'; emit();
  const s = store.syncState();
  // take snapshots and clear markers up-front; restore on failure
  const take = { sources: s.sources, settings: s.settings, libAdd: s.libAdd, libRemove: s.libRemove, progress: s.progress };
  store.patchSync((x) => { x.sources = false; x.settings = false; x.libAdd = {}; x.libRemove = []; x.progress = {}; });
  const restore = (parts) => store.patchSync((x) => {
    if (parts.sources) x.sources = true;
    if (parts.settings) x.settings = true;
    Object.assign(x.libAdd, parts.libAdd || {});
    for (const id of parts.libRemove || []) if (!x.libRemove.includes(id)) x.libRemove.push(id);
    Object.assign(x.progress, parts.progress || {});
  });
  const rest = { sources: take.sources, settings: take.settings, libAdd: { ...take.libAdd }, libRemove: [...take.libRemove], progress: { ...take.progress } };
  try {
    if (rest.sources) { await api('PUT', '/api/sync/sources', { sources: sourcesPayload(store.sources()) }); rest.sources = false; }
    const adds = Object.values(rest.libAdd);
    if (adds.length || rest.libRemove.length) {
      await api('POST', '/api/sync/library', {
        add: adds.map((i) => ({ id: i.id, type: i.type, name: i.name, poster: i.poster || null })),
        remove: rest.libRemove,
      });
      store.patchSync((x) => {
        const set = new Set(x.libSynced || []);
        for (const i of adds) set.add(i.id);
        for (const id of rest.libRemove) set.delete(id);
        x.libSynced = [...set];
      });
      rest.libAdd = {}; rest.libRemove = [];
    }
    const keys = Object.keys(rest.progress);
    if (keys.length) {
      const items = keys.map((k) => store._state.progress[k]).filter(Boolean).map(progressWire);
      for (const b of chunk(items, 200)) {
        await api('POST', '/api/sync/progress', { items: b });
        b.forEach((r) => delete rest.progress[progressKey(r.mediaId, r.episodeId)]);
      }
      rest.progress = {};
    }
    if (rest.settings) { await api('PUT', '/api/sync/settings', { settings: store.settings() }); rest.settings = false; }
    status.state = 'idle'; status.lastSync = Date.now(); status.error = ''; failures = 0; emit();
  } catch (e) {
    // validation errors (400/413) would fail forever: drop them. Everything else is retried with backoff.
    if (e.status !== 400 && e.status !== 413) restore(rest);
    handleFailure(e);
  } finally {
    pushing = false;
    if (status.state === 'idle' && hasPending()) schedulePush();
  }
}

export const schedulePush = debounce(() => { if (status.user) push(); }, 1500);

export async function deleteRemoteAll() {
  await api('DELETE', '/api/sync/all', {});
}

export function start() {
  // While a video plays, progress is saved every few seconds: push at most every 30 s, and right after playback ends.
  let slowTimer = null;
  store.subscribe((key) => {
    if (key !== 'sync' || !status.user) return;
    if (!document.body.classList.contains('playing')) { schedulePush(); return; }
    if (!slowTimer) slowTimer = setTimeout(() => { slowTimer = null; push(); }, 30000);
  });
  new MutationObserver(() => {
    if (!document.body.classList.contains('playing') && status.user && hasPending()) schedulePush();
  }).observe(document.body, { attributes: true, attributeFilter: ['class'] });
  addEventListener('online', () => { if (status.user) { failures = 0; hasPending() ? push() : pull(); } });
  // Pick up changes made on other devices: when the tab becomes visible again and every few minutes.
  let lastPull = Date.now();
  const refresh = () => {
    if (!status.user || pushing || hasPending() || document.visibilityState === 'hidden') return;
    if (document.body.classList.contains('playing')) return; // a re-render would interrupt playback
    if (Date.now() - lastPull < 30000) return;
    lastPull = Date.now();
    pull();
  };
  document.addEventListener('visibilitychange', refresh);
  setInterval(refresh, 120000);
  addEventListener('pagehide', () => { if (status.user && hasPending()) pushBeacon(); });
}

// Best-effort push on page hide using keepalive fetch (progress only; small).
function pushBeacon() {
  const s = store.syncState();
  const keys = Object.keys(s.progress).slice(0, 50);
  if (!keys.length) return;
  const items = keys.map((k) => store._state.progress[k]).filter(Boolean).map(progressWire);
  try {
    fetch('/api/sync/progress', {
      method: 'POST', credentials: 'same-origin', keepalive: true,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ items }),
    });
  } catch { /* ignore */ }
}
