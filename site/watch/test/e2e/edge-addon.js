// Edge-case Stremio addon on https 4182
const https = require('https'), fs = require('fs'), path = require('path');
const SP = process.env.CERT_DIR || __dirname; // needs key.pem + cert.pem (openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -subj /CN=localhost -days 30)
const tls = { key: fs.readFileSync(SP + '/key.pem'), cert: fs.readFileSync(SP + '/cert.pem') };
const O = 'https://localhost:4182';
const LONG = 'Supercalifragilisticexpialidocious'.repeat(6);
const LONGSP = 'The Extraordinarily Long and Winding Title of a Film That Nobody Asked For But Everybody Received Anyway: Part Two — The Reckoning Returns Again';
const metas = [
  { id: 'edge:long1', type: 'movie', name: LONG, year: '2001', poster: O + '/p/a.svg', genres: ['Drama'] },
  { id: 'edge:long2', type: 'movie', name: LONGSP, releaseInfo: '2002–2004', poster: O + '/p/b.svg', genres: ['Drama', 'Comedy', 'Romance', 'Thriller', 'Western', 'Documentary'], description: 'Lorem ipsum dolor sit amet. '.repeat(120), imdbRating: '7.3', runtime: '2h 31min' },
  { id: 'edge:cjk', type: 'movie', name: '千と千尋の神隠し 〜 あの夏、いちばん長い夜に起きたこと〜', year: '2001', poster: O + '/p/c.svg', genres: ['アニメ'], description: '十歳の少女千尋は、引っ越しの途中で不思議な町に迷い込む。'.repeat(8) },
  { id: 'edge:rtl', type: 'movie', name: 'فيلم الرحلة الطويلة إلى المنزل في ليلة شتوية', year: '2010', poster: O + '/p/d.svg', genres: ['دراما'], description: 'قصة طويلة عن رحلة. '.repeat(20) },
  { id: 'edge:heb', type: 'movie', name: 'שלום עולם: הסרט', year: '1999', poster: O + '/p/e.svg' },
  { id: 'edge:emoji', type: 'movie', name: '🎬 Emoji 😀 Movie 🍿🍿🍿', year: '2020', poster: O + '/p/f.svg' },
  { id: 'edge:xss', type: 'movie', name: '<img src=x onerror=alert(1)> "quoted" & <b>bold</b>', year: '2020', poster: 'javascript:alert(1)', description: '<script>alert(2)</script>' },
  { id: 'edge:noposter', type: 'movie', name: 'No Poster At All', year: '2020' },
  { id: 'edge:badposter', type: 'movie', name: 'Broken Poster Url', year: '2020', poster: O + '/p/missing404.png' },
  { id: 'edge:httpposter', type: 'movie', name: 'Http Poster', year: '2020', poster: 'http://localhost:4182/p/a.svg' },
  { id: 'edge:noyear', type: 'movie', name: 'No Year No Genre' },
  { id: 'edge:rating', type: 'movie', name: 'Weird Rating', year: '2020', imdbRating: 'N/A', runtime: 'unknown', poster: O + '/p/a.svg' },
  { id: 'edge:empty', type: 'movie', name: '' , poster: O + '/p/a.svg' },
  { id: 'edge:s1', type: 'series', name: 'Specials Only Series', year: '2019', poster: O + '/p/b.svg' },
  { id: 'edge:s2', type: 'series', name: 'Series With Odd Episodes', year: '2019', poster: O + '/p/c.svg' },
  { id: 'edge:s3', type: 'series', name: 'Series Without Videos', year: '2019', poster: O + '/p/c.svg' },
  { id: 'edge:s4', type: 'series', name: 'Thousand Episodes', year: '2005', poster: O + '/p/d.svg', background: O + '/p/bg.svg' },
];
const videos = {
  's1': [0, 1, 2, 3].map((e) => ({ id: `edge:s1:0:${e + 1}`, title: `Special ${e + 1}`, season: 0, episode: e + 1, released: '2019-01-01T00:00:00.000Z' })),
  's2': [
    { id: 'edge:s2:1:1', title: LONGSP, season: 1, episode: 1, released: '2020-01-01T00:00:00.000Z', overview: 'Short.', thumbnail: O + '/p/missing.png' },
    { id: 'edge:s2:1:2', season: 1, episode: 2, released: 'not-a-date' },
    { id: 'edge:s2:1:3', title: 'Missing release', season: 1, episode: 3 },
    { id: 'edge:s2:2:1', title: 'Future', season: 2, episode: 1, released: new Date(Date.now() + 40 * 864e5).toISOString() },
    { id: 'edge:s2:3:x', title: 'Weird number', season: 3, episode: 'x' },
    { id: 'edge:s2:1:2', title: 'Duplicate id', season: 1, episode: 2, released: '2020-01-09T00:00:00.000Z' },
    { id: 'edge:s2:-1:1', title: 'Negative season', season: -1, episode: 1 },
  ],
  's4': Array.from({ length: 1800 }, (_, i) => ({ id: `edge:s4:1:${i + 1}`, title: `Episode number ${i + 1} ` + 'with a title'.repeat(i % 5), season: 1, episode: i + 1, released: new Date(Date.UTC(2005, 0, 1 + i)).toISOString(), overview: 'o '.repeat(i % 50) })),
};
const svg = (t, c) => `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="450" viewBox="0 0 300 450"><rect width="300" height="450" fill="${c}"/><text x="150" y="225" fill="#fff" font-size="40" text-anchor="middle">${t}</text></svg>`;
const manifest = { id: 'org.mock.edge', version: '0.0.1', name: 'Edge Addon', description: 'Edge cases', resources: ['catalog', 'meta', { name: 'stream', types: ['movie', 'series'], idPrefixes: ['edge:'] }], types: ['movie', 'series'], idPrefixes: ['edge:'],
  catalogs: [{ type: 'movie', id: 'edge', name: 'Edge ' + LONG.slice(0, 30), extra: [{ name: 'search' }, { name: 'skip' }] }, { type: 'series', id: 'edge', name: 'Edge Series', extra: [{ name: 'search' }] }] };
const send = (res, code, body, ct = 'application/json') => { res.writeHead(code, { 'content-type': ct, 'access-control-allow-origin': '*' }); res.end(typeof body === 'string' ? body : JSON.stringify(body)); };
https.createServer(tls, (req, res) => {
  const u = new URL(req.url, O); const p = decodeURIComponent(u.pathname);
  if (req.method === 'OPTIONS') { res.writeHead(204, { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*' }); return res.end(); }
  if (p === '/manifest.json') return send(res, 200, manifest);
  if (p.startsWith('/p/')) { if (p.includes('missing')) return send(res, 404, 'no', 'text/plain'); return send(res, 200, svg(p.slice(3, 4), '#' + ((p.charCodeAt(3) * 99991) % 0xffffff).toString(16).padStart(6, '0').replace(/^(.{6}).*/, '$1')), 'image/svg+xml'); }
  let m;
  if ((m = p.match(/^\/catalog\/(movie|series)\/edge(?:\/(.*))?\.json$/))) { const q = m[2] || ''; const sm = q.match(/search=(.*)/); let l = metas.filter((x) => x.type === m[1]); if (sm) l = l.filter((x) => (x.name || '').toLowerCase().includes(decodeURIComponent(sm[1]).toLowerCase())); return send(res, 200, { metas: l }); }
  if ((m = p.match(/^\/meta\/(movie|series)\/(.+)\.json$/))) { const id = m[2]; const base = metas.find((x) => x.id === id); if (!base) return send(res, 200, { meta: null }); const k = id.split(':')[1]; return send(res, 200, { meta: { ...base, videos: videos[k], cast: ['A B', 'محمد علي', '山田 太郎', 'Z'], director: ['X'], genres: base.genres || [] } }); }
  if ((m = p.match(/^\/stream\//))) return send(res, 200, { streams: [{ name: 'Edge', title: LONG + '\n4K HDR 12GB', url: 'https://localhost:4182/p/a.svg' }, { name: 'External', title: 'ext', externalUrl: 'https://example.com' }, { name: 'Magnet', infoHash: 'abcdefabcdefabcdefabcdefabcdefabcdefabcd', title: 'torrent only' }] });
  send(res, 404, { error: 'nf' });
}).listen(4182, () => console.log('edge addon on 4182'));
