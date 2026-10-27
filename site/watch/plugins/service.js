// Plugin repositories and runs for the web player. Repositories live in this browser's prefs (never synced); every download and
// every plugin request goes through the Liuying relay (/api/relay), so it needs a signed-in account and the master switch.
import * as store from '../store.js';
import * as sync from '../sync.js';
import { LIMITS } from './rules.js';
import { parseManifest, parseTarget, supportsType, sampleFor, toStremioStream, groupFailures } from './contract.js';
import { runInSandbox, loadAsset, MODULE_FILES, RelayError } from './host.js';

export const MAX_CONCURRENT_RUNS = 3;
export const MAX_CONCURRENT_DOWNLOADS = 4;
const SCRIPT_TTL_MS = 10 * 60 * 1000;
const MANIFEST_MAX = 1024 * 1024;

// ---------- settings and repositories (device-local prefs) ----------
export const masterOn = () => store.prefs().pluginsEnabled === true;
export const setMaster = (on) => store.setPref('pluginsEnabled', !!on);
export const tmdbKey = () => (typeof store.prefs().tmdbKey === 'string' ? store.prefs().tmdbKey : '');
export const setTmdbKey = (k) => store.setPref('tmdbKey', String(k || '').trim().slice(0, 600));
/** Saved repositories. Entries from the older "saved link" stub have no manifest yet (plugins: []). */
export const repos = () => (Array.isArray(store.prefs().plugins) ? store.prefs().plugins.filter((r) => r && typeof r.url === 'string') : []);
const saveRepos = (list) => store.setPref('plugins', list);
const patchRepo = (url, fn) => saveRepos(repos().map((r) => (r.url === url ? fn(r) : r)));

/** Why plugins cannot run right now, or '' when they can. */
export function blockedReason() {
  if (!masterOn()) return 'off';
  if (!sync.getStatus().user) return 'signedout';
  return '';
}

// ---------- relay ----------
export async function relayRequest(spec, { signal, fetchImpl = fetch } = {}) {
  let res;
  try {
    res = await fetchImpl('/api/relay', { method: 'POST', credentials: 'same-origin', cache: 'no-store', referrerPolicy: 'no-referrer', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(spec), signal });
  } catch {
    throw new RelayError(signal?.aborted ? 'The server did not answer in time.' : 'Could not reach the Liuying relay (network error).', 'network');
  }
  let data = null;
  try { data = await res.json(); } catch { /* not JSON */ }
  if (res.ok && data && typeof data.status === 'number') return data;
  if (res.status === 401) throw new RelayError('Sign in to Liuying to run plugins.', 'auth');
  const msg = (data && typeof data.message === 'string' && data.message) || `The relay answered HTTP ${res.status}.`;
  if (res.status === 429) { const s = res.headers.get('retry-after'); throw new RelayError(`${msg}${s ? ` (retry in ${s} s)` : ''}`, 'rate'); }
  throw new RelayError(msg, data?.error || 'relay');
}

class Gate {
  constructor(n) { this.n = n; this.active = 0; this.q = []; }
  async run(fn) {
    if (this.active >= this.n) await new Promise((r) => this.q.push(r));
    this.active++;
    try { return await fn(); } finally { this.active--; this.q.shift()?.(); }
  }
}
const runGate = new Gate(MAX_CONCURRENT_RUNS);
const downloadGate = new Gate(MAX_CONCURRENT_DOWNLOADS);

// ---------- scripts and manifests (through the relay) ----------
const scriptCache = new Map(); // url -> {at, promise}
export function clearScriptCache() { scriptCache.clear(); }
async function relayText(url, { signal, maxBytes, what }) {
  const res = await downloadGate.run(() => relayRequest({ url, method: 'GET', headers: { Accept: '*/*' } }, { signal }));
  if (res.status < 200 || res.status >= 300) throw new RelayError(`${what} answered HTTP ${res.status}.`);
  if (res.binary || typeof res.body !== 'string') throw new RelayError(`${what} is not text.`);
  if (res.body.length > maxBytes) throw new RelayError(`${what} is too large.`);
  return res.body;
}
export function fetchScript(url, { signal } = {}) {
  const hit = scriptCache.get(url);
  if (hit && Date.now() - hit.at < SCRIPT_TTL_MS) return hit.promise;
  const promise = relayText(url, { signal, maxBytes: LIMITS.MAX_SCRIPT_BYTES, what: 'The plugin script' }).catch((e) => { scriptCache.delete(url); throw e; });
  scriptCache.set(url, { at: Date.now(), promise });
  return promise;
}

/** Adds a repository by manifest URL. Plugins start disabled; consent is asked later. */
export async function addRepo(url, { signal } = {}) {
  if (repos().some((r) => r.url === url && r.manifestLoaded)) throw new Error('That repository is already added.');
  const text = await relayText(url, { signal, maxBytes: MANIFEST_MAX, what: 'The repository' });
  const parsed = parseManifest(url, text);
  if (!parsed.ok) throw new Error(parsed.error);
  const m = parsed.manifest;
  if (repos().some((r) => r.url !== url && r.id === m.id)) throw new Error(`A repository with the same id is already added (${m.id}).`);
  const entry = { url, id: m.id, name: m.name, version: m.version, description: m.description, author: m.author, addedAt: Date.now(), fetchedAt: Date.now(), consented: false, lastError: null, manifestLoaded: true, plugins: m.plugins.map((p) => ({ ...p, enabled: false })) };
  const rest = repos().filter((r) => r.url !== url);
  saveRepos([...rest, entry]);
  return entry;
}

/** Re-reads the manifest. Enabled flags are kept; a plugin that now asks for more permissions is switched off again. */
export async function refreshRepo(url, { signal } = {}) {
  try {
    const text = await relayText(url, { signal, maxBytes: MANIFEST_MAX, what: 'The repository' });
    const parsed = parseManifest(url, text);
    if (!parsed.ok) throw new Error(parsed.error);
    const m = parsed.manifest;
    patchRepo(url, (r) => {
      const old = new Map((r.plugins || []).map((p) => [p.id, p]));
      return {
        ...r, id: m.id, name: m.name, version: m.version, description: m.description, author: m.author, fetchedAt: Date.now(), lastError: null, manifestLoaded: true,
        plugins: m.plugins.map((p) => {
          const prev = old.get(p.id);
          const wider = prev && p.permissions.some((x) => !(prev.permissions || []).includes(x));
          return { ...p, enabled: !!prev?.enabled && !wider };
        }),
      };
    });
    clearScriptCache();
    return { ok: true };
  } catch (e) {
    patchRepo(url, (r) => ({ ...r, lastError: String(e.message || e).slice(0, 300) }));
    return { ok: false, error: String(e.message || e) };
  }
}
export const removeRepo = (url) => saveRepos(repos().filter((r) => r.url !== url));
export const consentRepo = (url) => patchRepo(url, (r) => ({ ...r, consented: true, consentedAt: Date.now() }));
export function setPluginsEnabled(url, ids, on) {
  const set = new Set(ids);
  patchRepo(url, (r) => ({ ...r, plugins: (r.plugins || []).map((p) => (set.has(p.id) ? { ...p, enabled: !!on } : p)) }));
}

// ---------- ids ----------
const tmdbCache = new Map();
/** IMDb id to TMDB id with the user's own TMDB key (v3 key or v4 read token). Without a key, or on any failure, the IMDb id itself. */
export async function resolveTmdb(imdbId, mediaType, { signal, fetchImpl = fetch } = {}) {
  const key = tmdbKey().trim();
  if (!key) return imdbId;
  const ck = `${mediaType}|${imdbId}`;
  if (tmdbCache.has(ck)) return tmdbCache.get(ck);
  try {
    const bearer = key.startsWith('eyJ');
    const u = `https://api.themoviedb.org/3/find/${encodeURIComponent(imdbId)}?external_source=imdb_id${bearer ? '' : `&api_key=${encodeURIComponent(key)}`}`;
    const r = await fetchImpl(u, { signal, credentials: 'omit', referrerPolicy: 'no-referrer', headers: bearer ? { Authorization: `Bearer ${key}`, Accept: 'application/json' } : { Accept: 'application/json' } });
    if (!r.ok) return imdbId;
    const data = await r.json();
    const id = data?.[mediaType === 'movie' ? 'movie_results' : 'tv_results']?.[0]?.id;
    if (Number.isInteger(id)) { tmdbCache.set(ck, String(id)); return String(id); }
  } catch { /* fall back */ }
  return imdbId;
}

// ---------- running ----------
/** One run of one plugin. `args` = {tmdbId, mediaType, season, episode}. Never throws. */
export async function runPlugin(plugin, args, { signal, now = Date.now } = {}) {
  const startedAt = now();
  try {
    if (signal?.aborted) return { ok: false, kind: 'aborted', error: 'Cancelled.', requests: 0, elapsedMs: 0 };
    const [engine, script] = await Promise.all([loadAsset('/watch/plugins/engine.js'), fetchScript(plugin.scriptUrl, { signal })]);
    if (script.length > LIMITS.MAX_SCRIPT_BYTES) return { ok: false, kind: 'failed', error: 'The plugin script is larger than 2 MB.', requests: 0, elapsedMs: now() - startedAt };
    const modules = {};
    if (/require/.test(script)) {
      await Promise.all(Object.entries(MODULE_FILES).map(async ([name, path]) => { modules[name] = await loadAsset(path); }));
    }
    return await runInSandbox({ script, args, relay: (req, o) => relayRequest(req, o), engine, modules, pluginName: plugin.name, startedAt });
  } catch (e) {
    return { ok: false, kind: e?.kind === 'auth' ? 'auth' : 'failed', error: String(e?.message || e).slice(0, 300), requests: 0, elapsedMs: now() - startedAt };
  }
}

/** Test button: one run with a well-known sample title, regardless of the plugin being enabled. */
export async function testPlugin(repo, plugin) {
  const why = blockedReason();
  if (why === 'off') return { ok: false, streamCount: 0, error: 'Turn on Run plugins first.', elapsedMs: 0 };
  if (why === 'signedout') return { ok: false, streamCount: 0, error: 'Sign in to Liuying to run plugins.', elapsedMs: 0 };
  if (!repo.consented) return { ok: false, streamCount: 0, error: 'Allow this repository first.', elapsedMs: 0 };
  clearScriptCache();
  const t0 = Date.now();
  const r = await runGate.run(() => runPlugin(plugin, sampleFor(plugin.types)));
  return r.ok ? { ok: true, streamCount: r.streams.length, error: null, elapsedMs: Date.now() - t0, streams: r.streams } : { ok: false, streamCount: 0, error: r.error, elapsedMs: Date.now() - t0 };
}

/** Plugins to ask for this title: master switch on, signed in, repository consented, plugin enabled, type supported. */
export function eligible(type, videoId) {
  if (blockedReason()) return { target: null, list: [] };
  const target = parseTarget(type, videoId);
  if (!target) return { target: null, list: [] };
  const list = [];
  for (const repo of repos()) {
    if (!repo.consented) continue;
    for (const plugin of repo.plugins || []) if (plugin.enabled && supportsType(plugin.types, target.mediaType)) list.push({ repo, plugin });
  }
  return { target, list };
}

export const sourceFor = (repo, plugin) => ({ url: `plugin:${repo.id}/${plugin.id}`, isPlugin: true, manifest: { name: plugin.name, id: `plugin:${repo.id}/${plugin.id}` } });

/**
 * Runs the given plugins for a title, at most 3 at a time. Calls onResult({repo, plugin, source, ok, streams|error}) as each settles.
 * `streams` are already Stremio-shaped (see toStremioStream).
 */
export async function runForTitle(items, target, { signal, onStart, onResult }) {
  const tmdbId = target.isImdb ? await resolveTmdb(target.rawId, target.mediaType, { signal }) : target.rawId;
  const args = { tmdbId, mediaType: target.mediaType, season: target.season, episode: target.episode };
  await Promise.all(items.map(({ repo, plugin }) => runGate.run(async () => {
    if (signal?.aborted) return;
    onStart?.({ repo, plugin });
    const r = await runPlugin(plugin, args, { signal });
    if (signal?.aborted) return;
    const source = sourceFor(repo, plugin);
    if (r.ok) onResult({ repo, plugin, source, ok: true, streams: r.streams.map(toStremioStream) });
    else onResult({ repo, plugin, source, ok: false, error: r.error, kind: r.kind });
  })));
}

export { groupFailures };
