import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { setup, post, closeAll } from './helpers.js';

after(() => closeAll());

const PW = 'correct horse battery';
const jsonReq = (r) => r.set('Content-Type', 'application/json');
const put = (t, url, body) => jsonReq(t.put(url)).send(JSON.stringify(body));

async function user(ctx, name = 'Alice_01') {
  const a = ctx.agent();
  const r = await post(a, '/api/auth/signup', { username: name, password: PW });
  assert.equal(r.status, 201);
  return a;
}
const prog = (o = {}) => ({
  mediaId: 'tt1', episodeId: null, positionMs: 1000, durationMs: 5000,
  watched: false, dismissed: false, updatedAt: Date.now(), ...o,
});

test('all sync routes require a session', async () => {
  const ctx = await setup();
  const a = ctx.agent();
  const calls = [
    () => a.get('/api/sync/state'),
    () => put(a, '/api/sync/sources', { sources: [] }),
    () => post(a, '/api/sync/library', { add: [], remove: [] }),
    () => post(a, '/api/sync/progress', { items: [] }),
    () => put(a, '/api/sync/settings', { settings: {} }),
    () => a.delete('/api/sync/all'),
  ];
  for (const c of calls) {
    const r = await c();
    assert.equal(r.status, 401);
    assert.equal(r.body.error, 'unauthenticated');
  }
  // after logout the old cookie stops working
  const u = await user(ctx);
  await post(u, '/api/auth/logout', {});
  assert.equal((await u.get('/api/sync/state')).status, 401);
});

test('empty state shape', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  const r = await u.get('/api/sync/state');
  assert.equal(r.status, 200);
  assert.deepEqual({ ...r.body, serverTime: 0 }, { sources: [], library: [], progress: [], settings: {}, serverTime: 0 });
  assert.equal(typeof r.body.serverTime, 'number');
  assert.equal(r.headers['cache-control'], 'no-store');
});

test('sources: ordered replace, dedupe, validation', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  const r = await put(u, '/api/sync/sources', { sources: [
    { url: 'https://b.example/manifest.json', enabled: true },
    { url: 'http://a.example/x', enabled: false },
    { url: 'https://b.example/manifest.json', enabled: false },
  ] });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.sources, [
    { url: 'https://b.example/manifest.json', enabled: true },
    { url: 'http://a.example/x', enabled: false },
  ]);
  assert.deepEqual((await u.get('/api/sync/state')).body.sources, r.body.sources);
  // replace, reorder
  await put(u, '/api/sync/sources', { sources: [{ url: 'http://a.example/x', enabled: true }] });
  assert.deepEqual((await u.get('/api/sync/state')).body.sources, [{ url: 'http://a.example/x', enabled: true }]);

  for (const sources of [
    'x', [{ url: 'ftp://x/y', enabled: true }], [{ url: 'javascript:alert(1)' }], [{ url: 'not a url' }],
    [{ url: 'https://x.example/' + 'a'.repeat(2048), enabled: true }], [{ url: 'https://x.example', enabled: 'yes' }], [null],
  ]) {
    const e = await put(u, '/api/sync/sources', { sources });
    assert.equal(e.status, 400, JSON.stringify(sources).slice(0, 60));
    assert.equal(e.body.error, 'bad_request');
  }
  const many = Array.from({ length: 101 }, (_, i) => ({ url: `https://h${i}.example`, enabled: true }));
  const e = await put(u, '/api/sync/sources', { sources: many });
  assert.equal(e.status, 400);
  assert.equal(e.body.error, 'too_many_items');
  assert.equal((await put(u, '/api/sync/sources', { sources: many.slice(0, 100) })).status, 200);
  // failed requests left the stored list intact
  assert.equal((await u.get('/api/sync/state')).body.sources.length, 100);
});

test('library: add, upsert keeps addedAt, remove, validation', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  ctx.clock.t = Date.parse('2026-01-01T00:00:00Z');
  let r = await post(u, '/api/sync/library', { add: [
    { id: 'tt1', type: 'movie', name: 'One', poster: 'https://img.example/1.jpg' },
    { id: 'tt2', type: 'series', name: 'Two', poster: null },
  ], remove: [] });
  assert.deepEqual(r.body, { count: 2 });
  ctx.clock.t = Date.parse('2026-02-01T00:00:00Z');
  r = await post(u, '/api/sync/library', { add: [{ id: 'tt1', type: 'movie', name: 'One v2' }], remove: ['tt2', 'nope'] });
  assert.deepEqual(r.body, { count: 1 });
  const lib = (await u.get('/api/sync/state')).body.library;
  assert.deepEqual(lib, [{ id: 'tt1', type: 'movie', name: 'One v2', poster: null, addedAt: '2026-01-01T00:00:00.000Z' }]);

  const item = { id: 'x', type: 'movie', name: 'n', poster: null };
  for (const add of [
    [{ ...item, type: 'game' }], [{ ...item, id: '' }], [{ ...item, id: 'a'.repeat(129) }],
    [{ ...item, name: 'n'.repeat(301) }], [{ ...item, name: '' }], [{ ...item, poster: 'javascript:1' }],
    [{ ...item, poster: 'https://x/' + 'a'.repeat(2048) }], ['str'], 'nope',
  ]) {
    assert.equal((await post(u, '/api/sync/library', { add })).status, 400);
  }
  assert.equal((await post(u, '/api/sync/library', { remove: [5] })).status, 400);
  assert.equal((await post(u, '/api/sync/library', { add: [{ ...item, id: 'a'.repeat(128), name: 'n'.repeat(300) }] })).status, 200);
  for (const type of ['channel', 'tv', 'other']) {
    assert.equal((await post(u, '/api/sync/library', { add: [{ ...item, id: type, type }] })).status, 200);
  }
});

test('library: per-call and total caps', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  const mk = (from, n) => Array.from({ length: n }, (_, i) => ({ id: `m${from + i}`, type: 'movie', name: 'x' }));
  let r = await post(u, '/api/sync/library', { add: mk(0, 5001) });
  assert.equal(r.status, 400);
  assert.equal(r.body.error, 'too_many_items');
  r = await post(u, '/api/sync/library', { add: mk(0, 5000) });
  assert.deepEqual(r.body, { count: 5000 });
  r = await post(u, '/api/sync/library', { add: mk(5000, 1) });
  assert.equal(r.status, 409);
  assert.equal(r.body.error, 'too_many_items');
  assert.equal((await u.get('/api/sync/state')).body.library.length, 5000);
  // re-adding existing and swapping one in/one out still works at the cap
  r = await post(u, '/api/sync/library', { add: mk(5000, 1), remove: ['m0'] });
  assert.deepEqual(r.body, { count: 5000 });
});

test('progress: store, null episode, last-writer-wins', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  const t0 = Date.now();
  let r = await post(u, '/api/sync/progress', { items: [
    prog({ updatedAt: t0 }),
    prog({ mediaId: 'tt2', episodeId: 'tt2:1:1', positionMs: 10, durationMs: 100, updatedAt: t0 }),
  ] });
  assert.deepEqual(r.body, { accepted: 2 });
  // older and equal are ignored
  r = await post(u, '/api/sync/progress', { items: [prog({ positionMs: 4000, updatedAt: t0 - 1 }), prog({ positionMs: 3000, updatedAt: t0 })] });
  assert.deepEqual(r.body, { accepted: 0 });
  let p = (await u.get('/api/sync/state')).body.progress;
  assert.equal(p.find((x) => x.mediaId === 'tt1').positionMs, 1000);
  // newer wins
  r = await post(u, '/api/sync/progress', { items: [prog({ positionMs: 4000, watched: true, dismissed: true, updatedAt: t0 + 5 })] });
  assert.deepEqual(r.body, { accepted: 1 });
  p = (await u.get('/api/sync/state')).body.progress;
  const movie = p.find((x) => x.mediaId === 'tt1');
  assert.deepEqual(movie, { mediaId: 'tt1', episodeId: null, positionMs: 4000, durationMs: 5000, watched: true, dismissed: true, updatedAt: t0 + 5 });
  assert.equal(p.find((x) => x.mediaId === 'tt2').episodeId, 'tt2:1:1');
  assert.equal(p.length, 2);
  // movie (null episode) and episode rows of same media are distinct keys
  await post(u, '/api/sync/progress', { items: [prog({ episodeId: 'e1', updatedAt: t0 })] });
  assert.equal((await u.get('/api/sync/state')).body.progress.length, 3);
  // duplicates within one call: highest updatedAt wins regardless of order
  await post(u, '/api/sync/progress', { items: [
    prog({ mediaId: 'd', positionMs: 2, updatedAt: t0 + 10 }), prog({ mediaId: 'd', positionMs: 1, updatedAt: t0 + 1 })] });
  assert.equal((await u.get('/api/sync/state')).body.progress.find((x) => x.mediaId === 'd').positionMs, 2);
});

test('progress: validation', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  const now = ctx.clock.t;
  const cases = [
    prog({ positionMs: -1 }), prog({ durationMs: -1 }), prog({ positionMs: 1.5 }), prog({ positionMs: '1' }),
    prog({ positionMs: 70000, durationMs: 5000 }), prog({ mediaId: '' }), prog({ mediaId: 5 }),
    prog({ mediaId: 'a'.repeat(129) }), prog({ episodeId: '' }), prog({ episodeId: 7 }),
    prog({ watched: 'true' }), prog({ dismissed: 1 }), prog({ updatedAt: now + 2 * 86400000 }),
    prog({ updatedAt: -5 }), prog({ updatedAt: 'x' }), prog({ updatedAt: undefined }), 'str',
  ];
  for (const c of cases) {
    const r = await post(u, '/api/sync/progress', { items: [c] });
    assert.equal(r.status, 400, JSON.stringify(c));
    assert.equal(r.body.error, 'bad_request');
  }
  assert.equal((await post(u, '/api/sync/progress', {})).status, 400);
  // tolerance edge and slightly-future timestamps are fine; a bad item rejects the whole call
  assert.equal((await post(u, '/api/sync/progress', { items: [prog({ positionMs: 65000, durationMs: 5000, updatedAt: now + 3600000 })] })).status, 200);
  const r = await post(u, '/api/sync/progress', { items: [prog({ mediaId: 'ok', updatedAt: now }), prog({ positionMs: -1 })] });
  assert.equal(r.status, 400);
  assert.equal((await u.get('/api/sync/state')).body.progress.some((x) => x.mediaId === 'ok'), false);
  // per-call cap
  const many = Array.from({ length: 201 }, (_, i) => prog({ mediaId: `m${i}`, updatedAt: now }));
  const e = await post(u, '/api/sync/progress', { items: many });
  assert.equal(e.status, 400);
  assert.equal(e.body.error, 'too_many_items');
  assert.deepEqual((await post(u, '/api/sync/progress', { items: many.slice(0, 200) })).body, { accepted: 200 });
});

test('progress: 50,000 row cap per user', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  // seed directly (fast) to just under the cap
  const id = (await ctx.db.query('SELECT id FROM users')).rows[0].id;
  const vals = [];
  const params = [id];
  for (let i = 0; i < 49999; i++) { params.push(`m${i}`); vals.push(`($1, $${params.length}, '', 0, 0, false, false, 1)`); }
  for (let i = 0; i < vals.length; i += 5000) {
    const slice = vals.slice(i, i + 5000);
    // re-number placeholders per chunk
    const p = [id]; const v = slice.map((_, j) => { p.push(params[i + j + 1]); return `($1, $${p.length}, '', 0, 0, false, false, 1)`; });
    await ctx.db.query(`INSERT INTO user_progress (user_id, media_id, episode_id, position_ms, duration_ms, watched, dismissed, updated_at) VALUES ${v.join(',')}`, p);
  }
  const t = ctx.clock.t;
  assert.deepEqual((await post(u, '/api/sync/progress', { items: [prog({ mediaId: 'last', updatedAt: t })] })).body, { accepted: 1 });
  const r = await post(u, '/api/sync/progress', { items: [prog({ mediaId: 'over', updatedAt: t })] });
  assert.equal(r.status, 409);
  assert.equal(r.body.error, 'too_many_items');
  // updating an existing row still works at the cap
  assert.deepEqual((await post(u, '/api/sync/progress', { items: [prog({ mediaId: 'last', positionMs: 5, updatedAt: t + 1 })] })).body, { accepted: 1 });
  assert.equal(Number((await ctx.db.query('SELECT count(*) AS c FROM user_progress')).rows[0].c), 50000);
});

test('settings: object only, size caps, last write wins', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  const s1 = { theme: 'dark', nested: { a: [1, 2, { b: null }] }, __proto__x: 1 };
  let r = await put(u, '/api/sync/settings', { settings: s1 });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body, { settings: s1 });
  r = await put(u, '/api/sync/settings', { settings: { lang: 'en' } });
  assert.deepEqual((await u.get('/api/sync/state')).body.settings, { lang: 'en' });
  for (const settings of [null, [], 'x', 5, undefined]) {
    assert.equal((await put(u, '/api/sync/settings', { settings })).status, 400);
  }
  // body > 16kb -> 413
  r = await put(u, '/api/sync/settings', { settings: { blob: 'x'.repeat(17 * 1024) } });
  assert.equal(r.status, 413);
  assert.equal(r.body.error, 'payload_too_large');
  // just under 16kb fits
  r = await put(u, '/api/sync/settings', { settings: { blob: 'x'.repeat(16 * 1024 - 100) } });
  assert.equal(r.status, 200);
  // multi-byte content counted in bytes: 16000 x 3-byte chars > 16kb of JSON but small enough as body? still rejected
  r = await put(u, '/api/sync/settings', { settings: { blob: '€'.repeat(6000) } });
  assert.ok([413].includes(r.status));
});

test('body limits: sync 256kb, auth stays 2kb', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  // > 2kb is fine on sync routes
  const items = Array.from({ length: 100 }, (_, i) => prog({ mediaId: `a-long-media-id-${i}`, updatedAt: ctx.clock.t }));
  assert.ok(JSON.stringify({ items }).length > 2048);
  assert.equal((await post(u, '/api/sync/progress', { items })).status, 200);
  // > 256kb is 413
  const big = await post(u, '/api/sync/library', { add: [], remove: ['x'.repeat(300 * 1024)] });
  assert.equal(big.status, 413);
  assert.equal(big.body.error, 'payload_too_large');
  // auth routes keep the 2kb limit
  const a = await post(ctx.agent(), '/api/auth/login', { username: 'a'.repeat(3000), password: 'x' });
  assert.equal(a.status, 400);
  assert.equal(a.body.error, 'bad_request');
  // malformed JSON
  const bad = await jsonReq(u.post('/api/sync/library')).send('{nope');
  assert.equal(bad.status, 400);
  assert.equal(bad.body.error, 'bad_request');
});

test('per-user isolation', async () => {
  const ctx = await setup();
  const a = await user(ctx, 'Alice_01');
  const b = await user(ctx, 'Bob_0001');
  await put(a, '/api/sync/sources', { sources: [{ url: 'https://a.example', enabled: true }] });
  await post(a, '/api/sync/library', { add: [{ id: 'shared', type: 'movie', name: 'A copy', poster: null }] });
  await post(a, '/api/sync/progress', { items: [prog({ mediaId: 'shared', updatedAt: ctx.clock.t })] });
  await put(a, '/api/sync/settings', { settings: { who: 'alice' } });

  const empty = (await b.get('/api/sync/state')).body;
  assert.deepEqual([empty.sources, empty.library, empty.progress, empty.settings], [[], [], [], {}]);

  // B writes the same keys; A is untouched
  await put(b, '/api/sync/sources', { sources: [{ url: 'https://b.example', enabled: false }] });
  await post(b, '/api/sync/library', { add: [{ id: 'shared', type: 'series', name: 'B copy', poster: null }] });
  await post(b, '/api/sync/progress', { items: [prog({ mediaId: 'shared', positionMs: 9, updatedAt: ctx.clock.t + 100 })] });
  await put(b, '/api/sync/settings', { settings: { who: 'bob' } });
  // B cannot remove A's library entry (removal only touches B's own rows)
  await post(b, '/api/sync/library', { remove: ['shared'] });

  const sa = (await a.get('/api/sync/state')).body;
  assert.deepEqual(sa.sources, [{ url: 'https://a.example', enabled: true }]);
  assert.equal(sa.library[0].name, 'A copy');
  assert.equal(sa.progress[0].positionMs, 1000);
  assert.deepEqual(sa.settings, { who: 'alice' });
  const sb = (await b.get('/api/sync/state')).body;
  assert.deepEqual([sb.library.length, sb.progress.length], [0, 1]);

  // B's DELETE /all does not touch A
  assert.equal((await b.delete('/api/sync/all')).status, 204);
  assert.equal((await a.get('/api/sync/state')).body.library.length, 1);
});

test('DELETE /all removes synced data but not the account', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  await put(u, '/api/sync/sources', { sources: [{ url: 'https://a.example', enabled: true }] });
  await post(u, '/api/sync/library', { add: [{ id: 'x', type: 'movie', name: 'X' }] });
  await post(u, '/api/sync/progress', { items: [prog({ updatedAt: ctx.clock.t })] });
  await put(u, '/api/sync/settings', { settings: { a: 1 } });
  const r = await u.delete('/api/sync/all').set('Origin', 'http://127.0.0.1');
  assert.equal(r.status, 403); // wrong origin
  assert.equal((await u.delete('/api/sync/all')).status, 204);
  const s = (await u.get('/api/sync/state')).body;
  assert.deepEqual([s.sources, s.library, s.progress, s.settings], [[], [], [], {}]);
  assert.equal((await u.get('/api/auth/me')).status, 200);
  for (const t of ['user_sources', 'user_library', 'user_progress', 'user_settings']) {
    assert.equal(Number((await ctx.db.query(`SELECT count(*) AS c FROM ${t}`)).rows[0].c), 0);
  }
});

test('deleting a user cascades to synced data', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  await post(u, '/api/sync/library', { add: [{ id: 'x', type: 'movie', name: 'X' }] });
  await put(u, '/api/sync/settings', { settings: { a: 1 } });
  await ctx.db.query('DELETE FROM users');
  for (const t of ['user_sources', 'user_library', 'user_progress', 'user_settings', 'sessions']) {
    assert.equal(Number((await ctx.db.query(`SELECT count(*) AS c FROM ${t}`)).rows[0].c), 0, t);
  }
});

test('CSRF: content type and Origin on non-GET sync routes', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  const host = new URL((await u.get('/api/sync/state')).request.url).host;
  for (const m of ['post', 'put']) {
    const url = m === 'post' ? '/api/sync/library' : '/api/sync/sources';
    const noType = await u[m](url).set('Content-Type', 'text/plain').send('{}');
    assert.equal(noType.status, 415);
    assert.equal(noType.body.error, 'unsupported_media_type');
    const evil = await jsonReq(u[m](url)).set('Origin', 'https://evil.example').send('{}');
    assert.equal(evil.status, 403);
    assert.equal(evil.body.error, 'forbidden');
    const good = await jsonReq(u[m](url)).set('Host', host).set('Origin', `http://${host}`).send(m === 'post' ? '{}' : '{"sources":[]}');
    assert.equal(good.status, 200);
  }
  const evilDel = await u.delete('/api/sync/all').set('Origin', 'https://evil.example');
  assert.equal(evilDel.status, 403);
  // evil GET Origin is irrelevant (reads only), but nothing was changed by rejected writes
  assert.equal((await u.delete('/api/sync/all').set('Host', host).set('Origin', `http://${host}`)).status, 204);
});

test('rate limit: 60 requests/min per user, separate per user, recovers', async () => {
  const ctx = await setup();
  const a = await user(ctx, 'Alice_01');
  const b = await user(ctx, 'Bob_0001');
  for (let i = 0; i < 60; i++) assert.equal((await a.get('/api/sync/state')).status, 200);
  const r = await a.get('/api/sync/state');
  assert.equal(r.status, 429);
  assert.equal(r.body.error, 'rate_limited');
  assert.ok(Number(r.headers['retry-after']) >= 1);
  assert.equal((await b.get('/api/sync/state')).status, 200);
  ctx.clock.t += 61000;
  assert.equal((await a.get('/api/sync/state')).status, 200);
});

test('expired sessions are rejected', async () => {
  const ctx = await setup();
  const u = await user(ctx);
  ctx.clock.t += 31 * 24 * 3600 * 1000;
  assert.equal((await u.get('/api/sync/state')).status, 401);
});

// ---- web player: routing and CSP ----
function siteWith({ watch = true } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'liuying-site-'));
  fs.writeFileSync(path.join(dir, 'index.html'), '<h1>home</h1>');
  fs.mkdirSync(path.join(dir, 'vendor'));
  fs.writeFileSync(path.join(dir, 'vendor', 'lib.js'), 'window.lib=1');
  if (watch) {
    fs.mkdirSync(path.join(dir, 'watch'));
    fs.writeFileSync(path.join(dir, 'watch', 'index.html'), '<title>player</title>');
    fs.writeFileSync(path.join(dir, 'watch', 'player.js'), 'window.p=1');
    fs.writeFileSync(path.join(dir, 'watch', 'sw.js'), 'self.v=1');
    fs.writeFileSync(path.join(dir, 'watch', 'manifest.webmanifest'), '{"name":"x"}');
  }
  return dir;
}

test('/watch routing serves the SPA shell and static files', async () => {
  const ctx = await setup({ siteDir: siteWith() });
  const t = ctx.request();
  for (const p of ['/watch', '/watch/', '/watch/movie/tt1', '/watch/a/b/c', '/WATCH/x', '/watch/index.html']) {
    const r = await t.get(p);
    assert.equal(r.status, 200, p);
    assert.match(r.text, /<title>player<\/title>/);
    assert.match(r.headers['content-type'], /html/);
  }
  const js = await t.get('/watch/player.js');
  assert.equal(js.status, 200);
  assert.equal(js.text, 'window.p=1');
  assert.equal((await t.get('/vendor/lib.js')).text, 'window.lib=1');
  assert.equal((await t.get('/watch/missing.js')).status, 404);
  assert.equal((await t.head('/watch/x')).status, 200);
  assert.equal((await t.get('/')).text, '<h1>home</h1>');
  assert.equal((await t.get('/watchfoo')).status, 404);
});

test('/watch serves the service worker and manifest with the right headers', async () => {
  const ctx = await setup({ siteDir: siteWith() });
  const sw = await ctx.request().get('/watch/sw.js');
  assert.equal(sw.status, 200);
  assert.match(sw.headers['content-type'], /javascript/);
  assert.equal(sw.headers['service-worker-allowed'], '/watch/');
  assert.equal(sw.headers['cache-control'], 'no-cache');
  const mf = await ctx.request().get('/watch/manifest.webmanifest');
  assert.match(mf.headers['content-type'], /application\/manifest\+json/);
});

test('/watch returns a graceful 404 when the player is not built', async () => {
  const ctx = await setup({ siteDir: siteWith({ watch: false }) });
  for (const p of ['/watch', '/watch/x/y']) {
    const r = await ctx.request().get(p);
    assert.equal(r.status, 404, p);
    assert.equal(r.text, 'Not found');
  }
});

test('CSP split: relaxed only for /watch', async () => {
  const ctx = await setup({ siteDir: siteWith() });
  const t = ctx.request();
  const parse = (h) => Object.fromEntries(h.split(';').map((d) => d.trim().split(/\s+(.*)/s).slice(0, 2)));
  const w = parse((await t.get('/watch/movie/x')).headers['content-security-policy']);
  assert.deepEqual(w, {
    'default-src': "'self'", 'script-src': "'self'", 'style-src': "'self'",
    'connect-src': "'self' https:", 'img-src': "'self' data: blob: https:",
    'media-src': "'self' blob: https:", 'font-src': "'self'", 'worker-src': "'self' blob:",
    'object-src': "'none'", 'base-uri': "'self'", 'form-action': "'self'", 'frame-ancestors': "'none'",
  });
  assert.equal(parse((await t.get('/watch/player.js')).headers['content-security-policy'])['connect-src'], "'self' https:");
  for (const p of ['/', '/vendor/lib.js', '/api/auth/me', '/health', '/nothing', '/watchfoo']) {
    const csp = parse((await t.get(p)).headers['content-security-policy']);
    assert.equal(csp['connect-src'], "'self'", p);
    assert.equal(csp['img-src'], "'self' data:", p);
    assert.equal(csp['media-src'], undefined, p);
    assert.equal(csp['worker-src'], undefined, p);
  }
});

test('CSP in production keeps upgrade-insecure-requests on both policies', async () => {
  const ctx = await setup({ siteDir: siteWith(), production: true });
  for (const p of ['/', '/watch']) {
    const h = (await ctx.request().get(p)).headers['content-security-policy'];
    assert.match(h, /upgrade-insecure-requests/);
  }
});
