import { ApiError } from './errors.js';

export const CSP_BASE = {
  'default-src': ["'self'"],
  'script-src': ["'self'"],
  'style-src': ["'self'"],
  'img-src': ["'self'", 'data:'],
  'font-src': ["'self'"],
  'connect-src': ["'self'"],
  'object-src': ["'none'"],
  'base-uri': ["'self'"],
  'form-action': ["'self'"],
  'frame-ancestors': ["'none'"],
};
// The web player under /watch fetches manifests and streams from arbitrary https addon hosts.
export const CSP_WATCH = {
  ...CSP_BASE,
  'connect-src': ["'self'", 'https:'],
  'img-src': ["'self'", 'data:', 'blob:', 'https:'],
  'media-src': ["'self'", 'blob:', 'https:'],
  'worker-src': ["'self'", 'blob:'],
};
export const cspString = (d) => [...Object.entries(d).map(([k, v]) => `${k} ${v.join(' ')}`), 'upgrade-insecure-requests'].join(';');

// helmet defaults as emitted by the Express server (production).
export function securityHeaders(watch = false) {
  return {
    'Content-Security-Policy': cspString(watch ? CSP_WATCH : CSP_BASE),
    'Cross-Origin-Opener-Policy': 'same-origin',
    'Cross-Origin-Resource-Policy': 'same-origin',
    'Origin-Agent-Cluster': '?1',
    'Referrer-Policy': 'no-referrer',
    'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
    'X-Content-Type-Options': 'nosniff',
    'X-DNS-Prefetch-Control': 'off',
    'X-Download-Options': 'noopen',
    'X-Frame-Options': 'DENY',
    'X-Permitted-Cross-Domain-Policies': 'none',
    'X-XSS-Protection': '0',
  };
}

export function respond(status, body, headers = {}, { watch = false, setCookies = [] } = {}) {
  const h = new Headers({ ...securityHeaders(watch), ...headers });
  for (const c of setCookies) h.append('Set-Cookie', c);
  return new Response(status === 204 || status === 304 ? null : body, { status, headers: h });
}
export const json = (status, obj, opts = {}, headers = {}) =>
  respond(status, JSON.stringify(obj), { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers }, opts);

/** Read the request body with a hard byte cap (works for chunked bodies too). */
export async function readBody(request, limit) {
  const declared = Number(request.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > limit) throw new ApiError(413, 'too_large', 'Request body is too large.');
  if (!request.body) return '';
  const reader = request.body.getReader();
  const chunks = []; let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) { reader.cancel().catch(() => {}); throw new ApiError(413, 'too_large', 'Request body is too large.'); }
    chunks.push(value);
  }
  const all = new Uint8Array(size);
  let o = 0; for (const c of chunks) { all.set(c, o); o += c.byteLength; }
  return new TextDecoder().decode(all);
}

/** express.json semantics: empty body -> {}, strict (objects/arrays only), size-limited. */
export async function parseJsonBody(request, limit, { syncRoute = false } = {}) {
  if (!/^application\/json\s*(;|$)/i.test(request.headers.get('content-type') || '')) return {};
  let text;
  try { text = await readBody(request, limit); } catch (e) {
    if (e instanceof ApiError && e.code === 'too_large') {
      throw syncRoute ? new ApiError(413, 'payload_too_large', 'Request body is too large.')
        : new ApiError(400, 'bad_request', 'Request body is too large.');
    }
    throw e;
  }
  if (!text.trim()) return {};
  if (!/^\s*[{[]/.test(text)) throw new ApiError(400, 'bad_request', 'Request body is not valid JSON.');
  try { return JSON.parse(text); } catch { throw new ApiError(400, 'bad_request', 'Request body is not valid JSON.'); }
}
