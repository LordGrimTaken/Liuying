// POST /api/relay: a narrow, authenticated HTTP relay for the web player's plugin sandbox.
//
// A browser cannot fetch arbitrary sites (CORS), so plugin traffic goes through the Worker. This is deliberately NOT an open
// proxy: signed-in users only, public DNS names only (IP literals, local names, odd ports and the site's own hosts are refused),
// GET/HEAD/POST, no cookies or Authorization header ever forwarded, the user's session cookie never leaves the Worker, redirects
// followed by hand with every hop re-checked, a 64 KB request cap, a 5 MB streamed response cap, 15 s timeout and per-user limits.
// I/O only: no parsing of the upstream body (Workers Free allows 10 ms CPU per request).
// All accept/refuse decisions live in site/watch/plugins/rules.js (shared with the browser).
import { ApiError } from './errors.js';
import {
  ALLOWED_METHODS, LIMITS, checkTarget, filterResponseHeaders, isRedirectStatus, isTextual, redirectPlan, sanitizeRequestHeaders,
} from '../../site/watch/plugins/rules.js';

export const RELAY_UA = 'Liuying/1.0 (Web plugin relay; +https://www.liuyingapp.com/watch/)';
const enc = new TextEncoder();

const bad = (message, code = 'bad_request', status = 400) => new ApiError(status, code, message);

/** Validates the JSON the browser sent. Returns a normalised spec or throws ApiError(400/413). */
export function parseRelaySpec(b) {
  if (!b || typeof b !== 'object' || Array.isArray(b)) throw bad('Request body must be a JSON object.');
  const method = String(b.method ?? 'GET').toUpperCase();
  if (!ALLOWED_METHODS.has(method)) throw bad(`HTTP method ${method.slice(0, 12)} is not allowed.`, 'method_not_allowed', 405);
  if (typeof b.url !== 'string') throw bad('url is required.');
  let body = null;
  if (b.body !== undefined && b.body !== null) {
    if (typeof b.body !== 'string') throw bad('body must be a string.');
    if (method !== 'POST') throw bad(`A ${method} request cannot have a body.`);
    if (enc.encode(b.body).length > LIMITS.MAX_REQUEST_BODY_BYTES) throw new ApiError(413, 'too_large', 'The request body is larger than 64 KB.');
    body = b.body;
  }
  const { headers } = sanitizeRequestHeaders(b.headers);
  return { url: b.url, method, headers, body };
}

/**
 * Performs the relayed request. `fetchImpl` and `ownHosts` are injectable for tests. Returns the JSON object for the browser:
 * `{status,statusText,url,headers,body,binary,hops}`. Throws ApiError for refusals and upstream failures.
 */
export async function relayFetch(spec, { fetchImpl = globalThis.fetch, ownHosts = [], timeoutMs = LIMITS.REQUEST_TIMEOUT_MS } = {}) {
  let { method, body, headers } = spec;
  let target = checkTarget(spec.url, { ownHosts });
  if (!target.ok) throw new ApiError(400, 'blocked_target', target.reason);
  let url = target.url;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    let hops = 0;
    for (;;) {
      hops++;
      const h = new Headers(headers);
      if (!h.has('user-agent')) h.set('user-agent', RELAY_UA);
      if (!h.has('accept')) h.set('accept', '*/*');
      let res;
      try {
        res = await fetchImpl(url.href, { method, headers: h, body: method === 'POST' ? (body ?? '') : undefined, redirect: 'manual', signal: ctl.signal });
      } catch (e) {
        if (ctl.signal.aborted) throw new ApiError(504, 'timeout', 'The server did not answer in time.');
        throw new ApiError(502, 'upstream_failed', `Could not reach ${url.hostname} (the connection failed).`);
      }
      const loc = res.headers.get('location');
      if (isRedirectStatus(res.status) && loc) {
        res.body?.cancel().catch(() => {});
        if (hops > LIMITS.MAX_REDIRECTS) throw new ApiError(502, 'too_many_redirects', 'Too many redirects.');
        let next;
        try { next = new URL(loc.trim(), url); } catch { throw new ApiError(502, 'blocked_target', 'Redirect refused: the address is not valid.'); }
        const chk = checkTarget(next.href, { ownHosts });
        if (!chk.ok) throw new ApiError(502, 'blocked_target', `Redirect refused: ${chk.reason}`);
        const plan = redirectPlan(res.status, method);
        method = plan.method;
        if (!plan.keepBody) { body = null; const { 'content-type': _ct, ...rest } = headers; headers = rest; }
        url = chk.url;
        continue;
      }
      const ctype = res.headers.get('content-type') || '';
      const binary = !isTextual(ctype);
      const cap = binary ? LIMITS.MAX_BINARY_BYTES : LIMITS.MAX_RESPONSE_BYTES;
      const len = Number(res.headers.get('content-length'));
      const tooBig = () => new ApiError(502, 'too_large', binary ? 'The response is a binary file larger than 512 KB.' : 'The response is larger than 5 MB.');
      if (Number.isFinite(len) && len > cap) { res.body?.cancel().catch(() => {}); throw tooBig(); }
      let text = '';
      if (method !== 'HEAD' && res.body) {
        const reader = res.body.getReader();
        const chunks = []; let size = 0;
        for (;;) {
          let r;
          try { r = await reader.read(); } catch { throw ctl.signal.aborted ? new ApiError(504, 'timeout', 'The server did not answer in time.') : new ApiError(502, 'upstream_failed', `Could not reach ${url.hostname} (the connection broke).`); }
          if (r.done) break;
          size += r.value.byteLength;
          if (size > cap) { reader.cancel().catch(() => {}); throw tooBig(); }
          chunks.push(r.value);
        }
        const all = new Uint8Array(size);
        let o = 0; for (const c of chunks) { all.set(c, o); o += c.byteLength; }
        if (binary) text = toBase64(all);
        else text = new TextDecoder(charsetOf(ctype)).decode(all);
      }
      return {
        status: res.status, statusText: res.statusText || '', url: url.href, headers: filterResponseHeaders(res.headers.entries()),
        body: text, binary, hops,
      };
    }
  } finally { clearTimeout(timer); }
}

function charsetOf(ctype) {
  const m = /charset\s*=\s*"?([\w.:-]+)/i.exec(ctype);
  if (!m) return 'utf-8';
  try { new TextDecoder(m[1]); return m[1]; } catch { return 'utf-8'; }
}
function toBase64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
