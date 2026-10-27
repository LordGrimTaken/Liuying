// Pure rules shared by the browser (pre-checks) and the Worker relay (cloudflare/src/relay.js imports this file).
// Everything the relay accepts or refuses is decided here, so the two sides cannot drift apart.

export const LIMITS = {
  RUN_TIMEOUT_MS: 20_000, // whole run: script download, execution, waiting
  MAX_REQUESTS: 30, // web requests per run (redirect hops count)
  REQUEST_TIMEOUT_MS: 15_000,
  MAX_RESPONSE_BYTES: 5 * 1024 * 1024,
  MAX_BINARY_BYTES: 512 * 1024, // base64 on the Worker is CPU work; text is only decoded
  MAX_REQUEST_BODY_BYTES: 64 * 1024,
  MAX_REDIRECTS: 5,
  MAX_URL_LENGTH: 4096,
  MAX_SCRIPT_BYTES: 2 * 1024 * 1024,
  MAX_STREAMS: 100,
  MAX_TIMER_MS: 5_000,
  MAX_HEADERS: 30,
  PER_MINUTE: 120, // relay calls per user
  PER_DAY: 3000,
};

/** URL port ('' means the scheme default, i.e. 80 or 443). */
export const ALLOWED_PORTS = new Set(['', '80', '443', '8080', '8443']);
export const ALLOWED_METHODS = new Set(['GET', 'HEAD', 'POST']);

// Refused for every purpose (relay targets and stream links).
const LOCAL_SUFFIXES = [
  'localhost', 'local', 'internal', 'lan', 'home', 'home.arpa', 'arpa', 'intranet', 'corp', 'localdomain', 'private',
  'test', 'example', 'invalid', 'onion',
  // public wildcard DNS names that resolve to whatever address is written in them
  'nip.io', 'sslip.io', 'xip.io', 'localtest.me', 'lvh.me', 'vcap.me', 'traefik.me', 'local.gd',
];
// Additionally refused as relay targets: the site itself and Workers hosts (a relay loop through our own Worker).
const RELAY_ONLY_SUFFIXES = ['workers.dev', 'liuyingapp.com'];
const LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const TLD = /^(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})$/;

/** Host-name level rule. Returns a reason string when the host is refused, otherwise ''. `host` is a URL.hostname. */
export function blockedHostReason(host, extraBlocked = [], { relay = true } = {}) {
  let h = String(host || '').trim().toLowerCase();
  if (h.endsWith('.')) h = h.slice(0, -1);
  if (!h) return 'The address has no host name.';
  if (h.includes(':') || h.startsWith('[')) return 'IP addresses are not allowed; use a host name.';
  if (h.length > 253) return 'The host name is too long.';
  const labels = h.split('.');
  if (labels.length < 2) return 'Single-label host names are not allowed.';
  if (!labels.every((l) => LABEL.test(l))) return 'The host name contains characters that are not allowed.';
  // URL parsing turns 2130706433, 0x7f.1 and 017700000001 into dotted quads; any all-numeric host is an IP literal
  if (labels.every((l) => /^\d+$/.test(l)) || /^\d/.test(labels[labels.length - 1])) return 'IP addresses are not allowed; use a host name.';
  if (!TLD.test(labels[labels.length - 1])) return 'The host name is not a public name.';
  for (const s of [...LOCAL_SUFFIXES, ...(relay ? RELAY_ONLY_SUFFIXES : []), ...extraBlocked.map((x) => String(x).toLowerCase())]) {
    if (h === s || h.endsWith('.' + s)) return 'That host is not allowed.';
  }
  return '';
}

/** Parses and checks a target. `{ ok: true, url }` or `{ ok: false, reason }`. `ownHosts` are extra refused names (the Worker's own). */
export function checkTarget(raw, { ownHosts = [] } = {}) {
  if (typeof raw !== 'string' || !raw) return { ok: false, reason: 'The address is missing.' };
  if (raw.length > LIMITS.MAX_URL_LENGTH) return { ok: false, reason: 'The address is too long.' };
  let url;
  try { url = new URL(raw.trim()); } catch { return { ok: false, reason: 'Only http and https addresses are allowed.' }; }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return { ok: false, reason: 'Only http and https addresses are allowed.' };
  if (url.username || url.password) return { ok: false, reason: 'Addresses with embedded credentials are not allowed.' };
  if (!ALLOWED_PORTS.has(url.port)) return { ok: false, reason: 'That port is not allowed (80, 443, 8080 and 8443 only).' };
  const bad = blockedHostReason(url.hostname, ownHosts);
  if (bad) return { ok: false, reason: bad };
  return { ok: true, url };
}

const TOKEN = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
const DROP_EXACT = new Set([
  'host', 'cookie', 'cookie2', 'authorization', 'proxy-authorization', 'content-length', 'connection', 'keep-alive', 'transfer-encoding',
  'te', 'trailer', 'upgrade', 'expect', 'accept-encoding', 'forwarded', 'via', 'x-real-ip', 'true-client-ip', 'x-client-ip', 'x-cluster-client-ip',
  'cdn-loop', 'max-forwards', 'dnt', 'x-csrf-token', 'x-xsrf-token',
]);
const DROP_PREFIX = ['cf-', 'x-cf-', 'x-forwarded-', 'proxy-', 'sec-', 'x-amzn-', 'x-envoy-'];

/**
 * Request headers a plugin may ask the relay to send. Cookie, Authorization, Host, Cloudflare and forwarding headers are always
 * dropped (the plugin never gets to act with the user's credentials or to spoof the proxy chain). Returns `{ headers, dropped }`.
 */
export function sanitizeRequestHeaders(input) {
  const headers = {};
  const dropped = [];
  if (!input || typeof input !== 'object' || Array.isArray(input)) return { headers, dropped };
  let n = 0;
  for (const [rawName, rawValue] of Object.entries(input)) {
    const name = String(rawName).trim().toLowerCase();
    if (!name || name.length > 100 || !TOKEN.test(name)) { dropped.push(name.slice(0, 40)); continue; }
    if (DROP_EXACT.has(name) || DROP_PREFIX.some((p) => name.startsWith(p))) { dropped.push(name); continue; }
    if (typeof rawValue !== 'string' && typeof rawValue !== 'number') { dropped.push(name); continue; }
    const value = String(rawValue);
    if (value.length > 4096 || /[\u0000-\u0008\u000a-\u001f\u007f]/.test(value)) { dropped.push(name); continue; }
    if (++n > LIMITS.MAX_HEADERS) { dropped.push(name); continue; }
    headers[name] = value.trim();
  }
  return { headers, dropped };
}

/** Response headers handed back to the plugin: never any Set-Cookie. Lower-cased, duplicates joined. */
export function filterResponseHeaders(entries) {
  const out = {};
  let n = 0;
  for (const [k, v] of entries) {
    const name = String(k).toLowerCase();
    if (name === 'set-cookie' || name === 'set-cookie2') continue;
    if (!(name in out) && ++n > 100) break;
    out[name] = name in out ? `${out[name]}, ${v}` : String(v);
    if (out[name].length > 8192) out[name] = out[name].slice(0, 8192);
  }
  return out;
}

/** Whether a content type is read as text (everything else is returned as base64). */
export function isTextual(contentType) {
  const ct = String(contentType || '').toLowerCase().split(';')[0].trim();
  if (!ct) return true;
  if (ct.startsWith('text/')) return true;
  if (/^application\/(json|xml|xhtml\+xml|javascript|x-javascript|ecmascript|x-www-form-urlencoded|rss\+xml|atom\+xml|vnd\.apple\.mpegurl|x-mpegurl|dash\+xml|manifest\+json|x-ndjson|graphql-response\+json)$/.test(ct)) return true;
  return /\+(json|xml)$/.test(ct);
}

/** Next request after a redirect: 301/302/303 turn a body request into a GET (HEAD stays HEAD); 307/308 keep method and body. */
export function redirectPlan(status, method) {
  if (status === 307 || status === 308) return { method, keepBody: true };
  return { method: method === 'HEAD' ? 'HEAD' : 'GET', keepBody: false };
}
export const isRedirectStatus = (s) => s === 301 || s === 302 || s === 303 || s === 307 || s === 308;
