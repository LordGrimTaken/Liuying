// Tiny hash router: '#/path/seg?x=y'. Routes are patterns with ':param' segments.
const routes = [];
let current = null;
let prev = null;
let hashChanges = 0;
let cleanup = null;
const scrollMemo = new Map();
const afterHooks = new Set();
export const onRoute = (fn) => { afterHooks.add(fn); return () => afterHooks.delete(fn); };

export function route(pattern, handler, name) {
  const keys = [];
  const re = new RegExp('^' + pattern.replace(/:([a-z]+)\*?/gi, (_, k) => { keys.push(k); return '([^/]+)'; }) + '/?$');
  routes.push({ re, keys, handler, name: name || pattern });
}

export function parseHash(hash = location.hash) {
  const raw = hash.replace(/^#/, '') || '/';
  const [path, qs = ''] = raw.split('?');
  const query = Object.fromEntries(new URLSearchParams(qs));
  return { path: path || '/', query, raw };
}

export const href = (path, query) => {
  const q = query ? new URLSearchParams(Object.entries(query).filter(([, v]) => v !== undefined && v !== null && v !== '')).toString() : '';
  return `#${path}${q ? '?' + q : ''}`;
};
export const enc = encodeURIComponent;
export const navigate = (path, query, { replace = false } = {}) => {
  const h = href(path, query);
  if (replace) { history.replaceState(null, '', h); render(); } else location.hash = h;
};

export const currentRoute = () => current;
/** The route shown before the current one (only meaningful right after an in-app navigation). */
export const previousRoute = () => prev;
/** True once the viewer has moved inside the app, so history.back() stays on this site. */
export const canGoBack = () => hashChanges > 0 && history.length > 1;
/** Go back when the previous page is one of ours (and, optionally, matches `pred`); otherwise open `fallbackHref`. */
export function goBack(fallbackHref, pred) {
  if (canGoBack() && prev && (!pred || pred(prev))) { history.back(); return true; }
  location.hash = fallbackHref.replace(/^#?/, '#');
  return false;
}

export async function render() {
  const { path, query, raw } = parseHash();
  const changed = !current || current.raw !== raw;
  if (current) scrollMemo.set(current.raw, window.scrollY);
  if (cleanup) { try { cleanup(); } catch (e) { console.error(e); } cleanup = null; }
  for (const r of routes) {
    const m = path.match(r.re);
    if (!m) continue;
    const params = {};
    r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
    if (current && current.raw !== raw) prev = current;
    current = { name: r.name, params, query, raw, path };
    const token = current;
    document.body.dataset.route = r.name;
    const result = await r.handler(current);
    if (token !== current) { try { result?.destroy?.(); } catch { /* ignore */ } return; }
    cleanup = result?.destroy || null;
    if (changed && r.name !== 'play') document.getElementById('view')?.firstElementChild?.classList.add('route-in');
    afterHooks.forEach((f) => { try { f(current); } catch (e) { console.error(e); } });
    const y = scrollMemo.get(raw);
    window.scrollTo(0, y || 0);
    return;
  }
  navigate('/', undefined, { replace: true });
}

export function start() {
  addEventListener('hashchange', () => { hashChanges++; render(); });
  return render();
}
export const restoreScroll = () => { const y = scrollMemo.get(current?.raw); if (y) window.scrollTo(0, y); };
