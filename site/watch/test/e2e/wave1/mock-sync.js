// Throwaway mock of the Liuying site + sync API. Port 4181. Serves /watch, /vendor from site/ with the web-player CSP.
const http = require('http'), fs = require('fs'), path = require('path');
const PORT = +process.env.SYNC_PORT || 4181;
const SITE = '/home/claude/liuying/site';
const CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob: https:; font-src 'self'; connect-src 'self' https:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; media-src 'self' blob: https:; worker-src 'self' blob:";
const STRICT = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json', '.png': 'image/png', '.txt': 'text/plain', '.webmanifest': 'application/manifest+json' };
let db;
const fresh = () => ({ sources: [], library: [], progress: new Map(), settings: {}, log: [] });
db = fresh();
const users = { alice: 'pw' };

function body(req) { return new Promise((r) => { let s = ''; req.on('data', (c) => (s += c)); req.on('end', () => { try { r(s ? JSON.parse(s) : {}); } catch { r(null); } }); }); }
const send = (res, code, obj, extra = {}) => { res.writeHead(code, { 'Content-Type': 'application/json', ...extra }); res.end(JSON.stringify(obj)); };

http.createServer(async (req, res) => {
  const u = new URL(req.url, `http://localhost:${PORT}`);
  const p = u.pathname;
  if (p.startsWith('/api/') || p.startsWith('/__')) {
    const signed = /(?:^|; )sid=alice/.test(req.headers.cookie || '');
    if (p === '/__state') return send(res, 200, { ...db, progress: [...db.progress.values()] });
    if (p === '/__reset') { db = fresh(); return send(res, 200, { ok: true }); }
    if (p === '/__login') { return send(res, 200, { ok: true }, { 'Set-Cookie': 'sid=alice; Path=/' }); }
    if (p === '/__logout') { return send(res, 200, { ok: true }, { 'Set-Cookie': 'sid=; Path=/; Max-Age=0' }); }
    if (req.method !== 'GET' && !/application\/json/.test(req.headers['content-type'] || '')) { db.log.push(`415 ${req.method} ${p}`); return send(res, 415, { error: { message: 'Content-Type must be application/json' } }); }
    if (p === '/api/auth/me') return signed ? send(res, 200, { user: { username: 'alice' } }) : send(res, 401, { error: { message: 'Not signed in' } });
    if (!signed) return send(res, 401, { error: { message: 'Not signed in' } });
    const b = req.method === 'GET' ? {} : await body(req);
    if (b === null) return send(res, 400, { error: { message: 'bad json' } });
    db.log.push(`${req.method} ${p}`);
    if (p === '/api/sync/state' && req.method === 'GET') return send(res, 200, { sources: db.sources, library: db.library, progress: [...db.progress.values()], settings: db.settings, serverTime: Date.now() });
    if (p === '/api/sync/sources' && req.method === 'PUT') { db.sources = b.sources; return send(res, 200, { count: db.sources.length }); }
    if (p === '/api/sync/library' && req.method === 'POST') {
      for (const id of b.remove || []) db.library = db.library.filter((i) => i.id !== id);
      for (const it of b.add || []) { db.library = db.library.filter((i) => i.id !== it.id); db.library.unshift({ ...it, addedAt: Date.now() }); }
      return send(res, 200, { count: db.library.length });
    }
    if (p === '/api/sync/progress' && req.method === 'POST') {
      if (!Array.isArray(b.items) || b.items.length > 200) return send(res, 400, { error: { message: 'bad items' } });
      let n = 0;
      for (const it of b.items) {
        if (it.positionMs > it.durationMs) return send(res, 400, { error: { message: 'positionMs exceeds durationMs' } });
        const k = it.mediaId + '\u0000' + (it.episodeId || ''); const cur = db.progress.get(k);
        if (!cur || cur.updatedAt < it.updatedAt) { db.progress.set(k, { ...it, episodeId: it.episodeId ?? null }); n++; }
      }
      return send(res, 200, { accepted: n });
    }
    if (p === '/api/sync/settings' && req.method === 'PUT') { db.settings = b.settings; return send(res, 200, {}); }
    if (p === '/api/sync/all' && req.method === 'DELETE') { db = { ...fresh(), log: db.log }; return send(res, 200, {}); }
    return send(res, 404, { error: { message: 'nope' } });
  }
  // static
  let file;
  if (p === '/watch' || p === '/watch/') file = path.join(SITE, 'watch/index.html');
  else if (p.startsWith('/watch/') || p.startsWith('/vendor/') || p.startsWith('/assets/')) {
    file = path.join(SITE, decodeURIComponent(p));
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = p.startsWith('/watch/') ? path.join(SITE, 'watch/index.html') : file;
  } else if (p === '/') file = path.join(SITE, 'index.html');
  else if (p === '/login') file = path.join(SITE, 'login.html');
  else if (p === '/account') file = path.join(SITE, 'account.html');
  else file = path.join(SITE, decodeURIComponent(p));
  if (!file.startsWith(SITE) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end('not found'); }
  const isWatch = p === '/watch' || p.startsWith('/watch/') || p.startsWith('/vendor/');
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Content-Security-Policy': isWatch ? CSP : STRICT, 'Cache-Control': 'no-store' });
  fs.createReadStream(file).pipe(res);
}).listen(PORT, () => console.log('mock sync on', PORT));
