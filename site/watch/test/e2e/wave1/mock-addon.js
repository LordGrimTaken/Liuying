// Throwaway mock Stremio addon (HTTPS, self-signed, permissive CORS). Port 4180.
const https = require('https'), http = require('http'), fs = require('fs'), path = require('path');
const PORT = +process.env.ADDON_PORT || 4180;
const ORIGIN = `https://localhost:${PORT}`;
const dir = __dirname;
const tls = { key: fs.readFileSync(path.join(dir, 'key.pem')), cert: fs.readFileSync(path.join(dir, 'cert.pem')) };
const hits = [];

const GENRES = ['Action', 'Drama', 'Comedy', 'Sci-Fi'];
const hue = (s) => [...s].reduce((a, c) => (a * 31 + c.charCodeAt(0)) % 360, 7);
const svg = (w, h, id, label) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="hsl(${hue(id)},45%,28%)"/><stop offset="1" stop-color="hsl(${(hue(id) + 40) % 360},50%,10%)"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><circle cx="${w * 0.72}" cy="${h * 0.3}" r="${Math.min(w, h) * 0.18}" fill="hsl(${hue(id)},60%,60%)" opacity=".35"/><text x="50%" y="${h * 0.86}" font-family="Georgia,serif" font-size="${Math.round(w / 9)}" fill="#f2e9da" text-anchor="middle">${label}</text></svg>`;

const movies = Array.from({ length: 30 }, (_, i) => ({
  id: `mock:m${i + 1}`, type: 'movie', name: i === 0 ? 'The Moon Gate' : `Mock Movie ${i + 1}`, year: String(2000 + i), genres: [GENRES[i % 4]],
  description: 'A mock film about ink, seals and the flow of shadows across a quiet city. '.repeat(3), imdbRating: (6 + (i % 30) / 10).toFixed(1),
  runtime: '2h 10min', cast: ['Lin Mei', 'Hao Wen', 'Ada Reyes'], director: ['Chen Yu'],
}));
const series = Array.from({ length: 5 }, (_, i) => ({
  id: `mock:s${i + 1}`, type: 'series', name: i === 0 ? 'Flowing Shadows' : `Mock Series ${i + 1}`, year: String(2015 + i), genres: [GENRES[i % 4]],
  description: 'A mock serial in several chapters. '.repeat(5), imdbRating: '8.1', cast: ['Wen Qing', 'Marc Rivet'],
}));
const withArt = (m) => ({ ...m, poster: `${ORIGIN}/art/poster/${encodeURIComponent(m.id)}.svg`, background: `${ORIGIN}/art/bg/${encodeURIComponent(m.id)}.svg`, posterShape: 'poster' });
function videos(id) {
  const v = []; const n = +id.replace(/\D/g, '') || 1; const DAY = 864e5;
  const day = (off) => new Date(Math.floor(Date.now() / DAY) * DAY + off * DAY).toISOString();
  for (let s = 1; s <= 2; s++) for (let e = 1; e <= 3; e++) {
    let rel = `2020-0${s}-0${e}T00:00:00.000Z`;
    if (s === 2 && e === 3) rel = day(n === 1 ? 3 : n * 2 + 1);
    if (s === 2 && e === 2) rel = day(n === 1 ? 0 : n - 6);
    if (s === 2 && e === 1 && n === 1) rel = day(-5);
    v.push({ id: `${id}:${s}:${e}`, title: `Chapter ${s}.${e}`, season: s, episode: e, released: rel, overview: `Episode ${e} of season ${s}.`, thumbnail: `${ORIGIN}/art/bg/${encodeURIComponent(id + s + e)}.svg` });
  }
  return v;
}

const manifest = (name, base, mode) => ({
  id: base ? 'org.mock.broken' : mode ? 'org.mock.' + mode : 'org.mock.addon', version: '1.2.3', name, description: 'A throwaway addon used to test the Liuying web player.',
  logo: `${ORIGIN}/art/poster/logo.svg`,
  resources: [{ name: 'catalog' }, { name: 'meta', types: ['movie', 'series'], idPrefixes: ['mock:'] }, { name: 'stream', types: ['movie', 'series'], idPrefixes: ['mock:'] }, 'subtitles'].map((r) => (typeof r === 'object' && !r.types ? r.name : r)),
  types: ['movie', 'series'], idPrefixes: ['mock:'],
  catalogs: [
    { type: 'movie', id: 'top', name: 'Popular Films', extra: [{ name: 'genre', options: GENRES }, { name: 'skip' }, { name: 'search' }] },
    { type: 'series', id: 'shows', name: 'Binge Series', extra: [{ name: 'search' }, { name: 'skip' }] },
  ],
  behaviorHints: { configurable: true },
});

function json(res, status, body) { res.writeHead(status, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }); res.end(JSON.stringify(body)); }

function serveFile(req, res, file, type) {
  const st = fs.statSync(file);
  const h = { 'Content-Type': type, 'Access-Control-Allow-Origin': '*', 'Accept-Ranges': 'bytes' };
  const r = req.headers.range && /bytes=(\d*)-(\d*)/.exec(req.headers.range);
  if (r) {
    const start = r[1] ? +r[1] : 0, end = r[2] ? +r[2] : st.size - 1;
    res.writeHead(206, { ...h, 'Content-Range': `bytes ${start}-${end}/${st.size}`, 'Content-Length': end - start + 1 });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else { res.writeHead(200, { ...h, 'Content-Length': st.size }); fs.createReadStream(file).pipe(res); }
}

function srt() {
  const out = [];
  for (let i = 0; i < 40; i++) {
    const s = 2 + i * 4, e = s + 2.5;
    const f = (t) => { const ms = Math.round((t % 1) * 1000), sec = Math.floor(t) % 60, m = Math.floor(t / 60); return `00:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')},${String(ms).padStart(3, '0')}`; };
    out.push(`${i + 1}\r\n${f(s)} --> ${f(e)}\r\nMock subtitle line ${i + 1}\r\n<i>second line</i>\r\n`);
  }
  return out.join('\r\n');
}

const server = https.createServer(tls, (req, res) => {
  const u = new URL(req.url, ORIGIN);
  let p = decodeURIComponent(u.pathname);
  hits.push(`${req.method} ${p}`);
  if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*' }); return res.end(); }
  if (p === '/__hits') return json(res, 200, hits);
  if (p === '/nocors/manifest.json') { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify(manifest('No CORS Addon'))); }
  if (p === '/adult/manifest.json') return json(res, 200, { ...manifest('Adult Addon'), id: 'org.mock.adult', behaviorHints: { adult: true, configurationRequired: true } });
  if (p.startsWith('/media/hls/')) { const f = path.join(dir, 'media/hls', path.basename(p)); return serveFile(req, res, f, p.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : p.endsWith('.mp4') ? 'video/mp4' : 'video/iso.segment'); }
  const broken = p.startsWith('/broken/');
  if (broken) p = p.slice('/broken'.length);
  const mode = (p.match(/^\/(huge|slow|nostream)\//) || [])[1];
  if (mode) p = p.slice(mode.length + 1);
  if (mode === 'slow' && p !== '/manifest.json') return setTimeout(() => route(req, res, p, u, broken, mode), 5000);
  return route(req, res, p, u, broken, mode);
});
function route(req, res, p, u, broken, mode) {
  if (p === '/manifest.json') return json(res, 200, manifest(broken ? 'Broken Addon' : mode ? 'Mode ' + mode : 'Mock Addon', broken, mode));
  if (p.startsWith('/art/')) {
    const [, , kind, rest] = p.split('/');
    const id = rest.replace(/\.svg$/, '');
    const label = (movies.concat(series).find((m) => m.id === id) || { name: id === 'logo' ? 'M' : '' }).name;
    res.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'max-age=60' });
    return res.end(kind === 'poster' ? svg(300, 450, id, label) : svg(900, 506, id, label));
  }
  if (p === '/media/clip.webm') return serveFile(req, res, path.join(dir, 'media/clip.webm'), 'video/webm');
  if (p === '/media/clip.mp4') return serveFile(req, res, path.join(dir, 'media/clip.mp4'), 'video/mp4');
  if (p === '/media/clip.mpd') return json(res, 404, {});
  if (p.startsWith('/subs/')) { res.writeHead(200, { 'Content-Type': 'application/x-subrip', 'Access-Control-Allow-Origin': '*' }); return res.end(srt()); }
  if (p === '/configure') { res.writeHead(200, { 'Content-Type': 'text/html' }); return res.end('<h1>Configure mock</h1>'); }
  const m = p.match(/^\/(catalog|meta|stream|subtitles)\/([^/]+)\/([^/]+?)(?:\/(.+?))?\.json$/);
  if (!m) return json(res, 404, { error: 'not found' });
  const [, resource, type, id, extraStr] = m;
  if (broken) return json(res, 500, { error: 'boom' });
  const extra = Object.fromEntries(new URLSearchParams(extraStr || ''));
  if (resource === 'catalog') {
    let list = (type === 'movie' ? movies : series).map(withArt);
    if (extra.genre) list = list.filter((x) => x.genres.includes(extra.genre));
    if (extra.search) list = list.filter((x) => x.name.toLowerCase().includes(extra.search.toLowerCase()));
    if (mode === 'huge') { const skip = +extra.skip || 0; const N = 5000; return json(res, 200, { metas: Array.from({ length: Math.min(1500, N - skip) }, (_, i) => withArt({ id: `mock:h${skip + i}`, type, name: `Huge Title ${skip + i}`, year: '2020', genres: ['Drama'], description: 'x' })) }); }
    const skip = +extra.skip || 0;
    return json(res, 200, { metas: list.slice(skip, skip + 20).map(({ cast, director, description, ...rest }) => ({ ...rest, description })) });
  }
  if (resource === 'meta') {
    const all = movies.concat(series);
    const found = all.find((x) => x.id === id);
    if (!found) return json(res, 200, { meta: null });
    const meta = withArt(found);
    meta.logo = undefined;
    if (found.type === 'series') meta.videos = videos(found.id);
    return json(res, 200, { meta });
  }
  if (resource === 'stream') {
    if (mode === 'nostream') return json(res, 200, { streams: [] });
    return json(res, 200, { streams: [
      { name: 'Mock\n1080p', title: 'The.Movie.2020.1080p.WEB-DL.HDR.x265-GRP\n\u{1F464} 120 \u{1F4BE} 2.1 GB', url: `${ORIGIN}/media/clip.webm`, behaviorHints: { bingeGroup: 'mock-1080', filename: 'clip.webm' } },
      { name: 'Mock 720p', description: '720p x264 AAC 5.1 900 MB', url: `${ORIGIN}/media/clip.webm`, behaviorHints: { bingeGroup: 'mock-720' } },
      { name: 'Mock HLS', title: '720p HLS adaptive', url: `${ORIGIN}/media/hls/index.m3u8` },
      { name: 'Torrent 4K', title: '2160p Dolby Vision Atmos 14.2 GB \u{1F464} 55', infoHash: 'c0ffee0123456789c0ffee0123456789c0ffee01' },
      { name: 'External site', title: 'Watch on the web', externalUrl: 'https://example.com/watch' },
      { name: 'Insecure HTTP', title: '480p plain http', url: `http://localhost:${PORT}/media/clip.webm` },
      { name: 'Broken link', title: '1080p dead url', url: `${ORIGIN}/media/missing.webm` },
    ] });
  }
  if (resource === 'subtitles') {
    return json(res, 200, { subtitles: [
      { id: 'en1', url: `${ORIGIN}/subs/en.srt`, lang: 'eng' }, { id: 'es1', url: `${ORIGIN}/subs/es.srt`, lang: 'spa' }, { id: 'fr1', url: `${ORIGIN}/subs/fr.srt`, lang: 'fre' },
    ] });
  }
  json(res, 404, {});
}
server.listen(PORT, () => console.log('mock addon on', ORIGIN));
