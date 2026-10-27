// Pure plugin logic shared by the page code and the tests: repository manifests, which titles a plugin is asked for, how a
// plugin's answer is read, and how failures are grouped. A port of Android's PluginManifest.kt, PluginContract.kt
// (PluginTargets, parseResult), PluginFailures.kt and PluginUrlSafety. No DOM, no network.
import { LIMITS, blockedHostReason } from './rules.js';

export const MAX_PLUGINS = 200;
export const SAMPLE_MOVIE = { tmdbId: '603', mediaType: 'movie' }; // The Matrix, like Android's Test plugin
export const SAMPLE_TV = { tmdbId: '1396', mediaType: 'tv', season: 1, episode: 1 };

export const RISK_NOTICE = 'Risk notice: plugins are third-party code written by other people, not by Liuying. Liuying does not review, host or endorse them or the ' +
  'content they point to. Anything a plugin does is decided by its author, and using plugins is entirely at your own risk. ' +
  'Only add repositories from people you trust, and make sure you are allowed to access what they link to.';

export const ALWAYS = [
  'Run for at most 20 seconds, with limited memory and at most 30 web requests',
  'Cannot read your files, accounts or other apps',
  'Cannot see your library, progress or other sources',
];

const str = (v) => (typeof v === 'string' ? v : undefined);
const strings = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string').map((x) => x.trim()).filter(Boolean) : []);

/** Absolute http(s) URL of `ref` relative to `base`, or null. */
export function resolveUrl(base, ref) {
  try {
    const u = new URL(String(ref).trim(), base);
    return (u.protocol === 'http:' || u.protocol === 'https:') && u.hostname ? u.href : null;
  } catch { return null; }
}

/** Repo id from the manifest URL, e.g. raw.githubusercontent.com/owner/repo/... -> "raw.githubusercontent.com/owner/repo". */
export function deriveId(manifestUrl) {
  try {
    const u = new URL(manifestUrl);
    const parts = u.pathname.split('/').filter(Boolean);
    const base = u.hostname.endsWith('githubusercontent.com') || u.hostname === 'github.com' ? parts.slice(0, 2) : parts.slice(0, -1);
    return [u.hostname, ...base].filter(Boolean).join('/');
  } catch { return null; }
}

function parsePlugin(manifestUrl, o) {
  if (!o || typeof o !== 'object') return null;
  const id = str(o.id)?.trim();
  const script = resolveUrl(manifestUrl, str(o.scriptUrl) ?? str(o.script) ?? '');
  if (!id || !script) return null;
  return { id, name: str(o.name)?.trim() || id, version: str(o.version) || '', description: str(o.description) || '', scriptUrl: script, types: strings(o.types), permissions: [...new Set(strings(o.permissions))] };
}

/** "Scrapers" format: filename is the script path, supportedTypes the types. */
function parseScraper(manifestUrl, o) {
  if (!o || typeof o !== 'object') return null;
  const file = str(o.filename)?.trim();
  const script = file ? resolveUrl(manifestUrl, file) : null;
  if (!file || !script) return null;
  const id = str(o.id)?.trim() || file.split('/').pop().replace(/\.[^.]*$/, '');
  if (!id) return null;
  const desc = str(o.description) || '';
  const by = str(o.author)?.trim();
  return {
    id, name: str(o.name)?.trim() || id, version: str(o.version) || '',
    description: by && desc.trim() ? `${desc} (by ${by})` : (desc.trim() || (by ? `By ${by}` : '')),
    scriptUrl: script, types: [...new Set(strings(o.supportedTypes).map((t) => t.toLowerCase()))], permissions: [],
  };
}

/** Repository manifest: `{ok:true, manifest}` or `{ok:false, error}`. Tolerant: bad plugin entries are skipped. */
export function parseManifest(manifestUrl, body) {
  let root;
  try { root = typeof body === 'string' ? JSON.parse(body) : body; } catch { return { ok: false, error: 'The repository did not return valid JSON.' }; }
  if (!root || typeof root !== 'object' || Array.isArray(root)) return { ok: false, error: 'The repository manifest should be a JSON object.' };
  const scrapers = Array.isArray(root.scrapers) ? root.scrapers : null;
  const name = str(root.name)?.trim();
  const id = str(root.id)?.trim() || (scrapers ? deriveId(manifestUrl) : null);
  if (!id || !name) return { ok: false, error: 'The manifest is missing an id or a name.' };
  const seen = new Set();
  const entries = [...(Array.isArray(root.plugins) ? root.plugins : []).map((e) => parsePlugin(manifestUrl, e)), ...(scrapers || []).map((e) => parseScraper(manifestUrl, e))].filter(Boolean);
  const plugins = entries.filter((p) => (seen.has(p.id) ? false : (seen.add(p.id), true))).slice(0, MAX_PLUGINS);
  return { ok: true, manifest: { id, name, version: str(root.version) || '', description: str(root.description) || '', author: str(root.author) || null, plugins } };
}

/** A title from the streams page (addon type + video id) as the plugin contract wants it, or null when it is not for plugins. */
export function parseTarget(type, videoId) {
  const parts = String(videoId || '').split(':');
  const head = parts[0];
  let raw;
  if (/^tt\d{5,12}$/.test(head)) raw = head;
  else if (head === 'tmdb' && /^\d+$/.test(parts[1] || '')) raw = parts[1];
  else return null;
  if (type === 'movie') return { rawId: raw, mediaType: 'movie', season: null, episode: null, isImdb: raw.startsWith('tt') };
  if (type === 'series') {
    const off = head === 'tmdb' ? 2 : 1;
    const s = Number.parseInt(parts[off], 10), e = Number.parseInt(parts[off + 1], 10);
    if (!Number.isInteger(s) || !Number.isInteger(e)) return null;
    return { rawId: raw, mediaType: 'tv', season: s, episode: e, isImdb: raw.startsWith('tt') };
  }
  return null;
}

/** Whether the plugin's declared types (addon "movie"/"series" or scraper "movie"/"tv") cover this media type. Empty = any. */
export function supportsType(declared, mediaType) {
  if (!declared || !declared.length) return true;
  const d = declared.map((x) => String(x).toLowerCase());
  return mediaType === 'movie' ? d.includes('movie') : d.includes('tv') || d.includes('series');
}

/** What "Test plugin" asks for: the TV sample when the plugin is TV-only. */
export function sampleFor(types) {
  const d = (types || []).map((x) => String(x).toLowerCase());
  return d.length && !d.includes('movie') ? SAMPLE_TV : SAMPLE_MOVIE;
}

/** Whether a stream link from a plugin may be shown: public http(s) address, no credentials. */
export function streamUrlSafe(raw) {
  if (typeof raw !== 'string' || raw.length > LIMITS.MAX_URL_LENGTH) return false;
  let u;
  try { u = new URL(raw.trim()); } catch { return false; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
  if (u.username || u.password) return false;
  return !blockedHostReason(u.hostname, [], { relay: false });
}

const trimmed = (v, max) => {
  if (typeof v === 'number' && Number.isFinite(v)) v = String(v);
  if (typeof v !== 'string') return null;
  const s = v.trim().slice(0, max);
  return s || null;
};

/**
 * Reads what a plugin resolved with (JSON text). Accepts an array, or an object with a `streams` array. Entries without a public
 * http(s) url are dropped; text is trimmed; at most 100 are kept. Returns `{ok:true, streams}` or `{ok:false, error}`.
 */
export function parseResult(json, pluginName) {
  let root;
  try { root = JSON.parse(json); } catch { return { ok: false, error: 'The plugin returned something that is not JSON.' }; }
  const arr = Array.isArray(root) ? root : root === null ? [] : root && typeof root === 'object' && Array.isArray(root.streams) ? root.streams : null;
  if (!arr) return { ok: false, error: 'getStreams must return an array of streams.' };
  const streams = [];
  for (const o of arr) {
    if (streams.length >= LIMITS.MAX_STREAMS) break;
    if (!o || typeof o !== 'object' || Array.isArray(o)) continue;
    const url = trimmed(o.url, 4096);
    if (!url || !streamUrlSafe(url)) continue;
    const headers = {};
    let n = 0;
    if (o.headers && typeof o.headers === 'object' && !Array.isArray(o.headers)) {
      for (const [k, v] of Object.entries(o.headers)) {
        const key = k.trim().slice(0, 100);
        if (typeof v !== 'string') continue;
        const value = v.slice(0, 2000);
        if (!key || /[\u0000-\u001f\u007f:]/.test(key) || /[\r\n]/.test(value)) continue;
        if (n++ >= 20) break;
        headers[key] = value;
      }
    }
    streams.push({ name: trimmed(o.name, 200) || pluginName, title: trimmed(o.title, 500), url, quality: trimmed(o.quality, 40), headers });
  }
  return { ok: true, streams };
}

/** A plugin stream as the Stremio-style object the streams page already understands. Streams that need request headers cannot play in a browser. */
export function toStremioStream(s) {
  const needsHeaders = Object.keys(s.headers || {}).length > 0;
  return {
    url: s.url, name: s.name,
    description: [s.title, s.quality].filter(Boolean).join('\n') || undefined,
    behaviorHints: needsHeaders ? { notWebReady: true } : {},
    pluginHeaders: needsHeaders ? s.headers : null,
  };
}

// "Could not reach host (...)", also when the plugin wraps it ("TypeError: fetch failed: Could not reach host ...")
const REACH = /(?:^|:\s)Could not reach (\S+)/;
export const dnsHost = (message) => REACH.exec(message)?.[1] ?? null;

/** Groups [name, message] pairs by what went wrong; unreachable hosts group by host, everything else by identical text. */
export function groupFailures(failures) {
  const byKey = new Map();
  for (const f of failures) {
    const h = dnsHost(f[1]);
    const key = h ? `dns:${h}` : `msg:${f[1].trim()}`;
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push(f);
  }
  return [...byKey].map(([key, list]) => {
    const names = [...new Set(list.map((x) => x[0]))];
    const message = list[0][1];
    const headline = names.length === 1 ? `Plugin ${names[0]} failed: ${message}`
      : key.startsWith('dns:') ? `${names.length} plugins could not reach ${key.slice(4)}` : `${names.length} plugins failed: ${message}`;
    return { headline, message, names };
  });
}

/** Plain-language line for the consent dialog. */
export function describePermission(p) {
  if (p === 'network') return 'Connect to any website';
  if (p.startsWith('network:')) return `Connect to ${p.slice(8)}`;
  if (p === 'storage') return 'Keep a small amount of its own data';
  return `Unknown permission "${p}" (will be denied)`;
}
