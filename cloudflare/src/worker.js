// Liuying on Cloudflare Workers: static site (Workers Assets) + JSON API (D1).
// Routing: wrangler.jsonc `assets.run_worker_first` sends only /api/*, /health and the /watch SPA routes here;
// everything else is served straight from the asset store (free and uncounted) with headers from _headers.
import { handleApi } from './api.js';
import { NOT_FOUND_HTML } from './notFoundPage.js';
import { respond, securityHeaders } from './http.js';

function notFound(request, watch = false) {
  const accept = request.headers.get('accept') || '';
  const wantsHtml = request.method === 'GET' && /text\/html/.test(accept); // browsers get the branded page
  if (wantsHtml && !/\.[^/]+$/.test(new URL(request.url).pathname)) {
    return respond(404, NOT_FOUND_HTML, { 'Content-Type': 'text/html; charset=utf-8' }, { watch });
  }
  return respond(404, 'Not found', { 'Content-Type': 'text/plain; charset=utf-8' }, { watch });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const lower = path.toLowerCase();

    if (lower === '/api' || lower.startsWith('/api/')) return handleApi(request, env, ctx);

    if (lower === '/health') {
      try {
        await env.DB.prepare('SELECT 1').first();
        return respond(200, 'ok', { 'Content-Type': 'text/plain; charset=utf-8' });
      } catch (err) {
        console.error(JSON.stringify({ level: 'error', msg: 'health check failed', error: String(err?.message ?? err) }));
        return respond(503, 'database unavailable', { 'Content-Type': 'text/plain; charset=utf-8' });
      }
    }

    if (lower === '/watch' || lower.startsWith('/watch/')) {
      if (request.method !== 'GET' && request.method !== 'HEAD') return notFound(request, true);
      if (lower === '/watch/test' || lower.startsWith('/watch/test/')) return notFound(request, true); // unit tests are not part of the product
      if (/\.[^/]+$/.test(path)) return notFound(request, true); // missing asset, not an app route
      // SPA shell: /watch, /watch/ and any extension-less deep link serve the player's index.html (200).
      const shell = await env.ASSETS.fetch(new Request(new URL('/watch/', url), request));
      return shell;
    }

    return env.ASSETS.fetch(request);
  },
};
