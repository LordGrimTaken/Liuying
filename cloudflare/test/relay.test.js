// Plugin relay: URL validator, header stripping, redirect handling, caps, auth and rate limits (node --test).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup } from './helpers.js';
import { handleApi } from '../src/api.js';
import { relayFetch, parseRelaySpec, RELAY_UA } from '../src/relay.js';
import { checkTarget, sanitizeRequestHeaders, filterResponseHeaders, isTextual, blockedHostReason, LIMITS } from '../../site/watch/plugins/rules.js';
import { checkRelay, DAY_BASE } from '../src/rateLimit.js';

const PW = 'correct horse battery';

test('checkTarget accepts public https/http names on the allowed ports', () => {
  for (const u of ['https://example.org/a?b=1', 'http://api.example.co.uk/x', 'https://sub.domain.example.com:8443/p', 'http://example.net:8080/', 'https://example.org:443/', 'https://xn--bcher-kva.com/', 'HTTPS://Example.ORG./']) {
    assert.equal(checkTarget(u).ok, true, u);
  }
});

test('checkTarget refuses everything that is not a public DNS name', () => {
  const refused = [
    'ftp://example.org/', 'file:///etc/passwd', 'javascript:alert(1)', 'data:text/plain,hi', 'blob:https://example.org/x', 'ws://example.org/', 'not a url', '',
    'http://localhost/', 'http://LOCALHOST:8080/', 'http://foo.localhost/', 'http://127.0.0.1/', 'http://127.1/', 'http://2130706433/', 'http://0x7f.1/', 'http://017700000001/',
    'http://10.0.0.1/', 'http://192.168.1.1/', 'http://169.254.169.254/latest/meta-data/', 'http://[::1]/', 'http://[::ffff:127.0.0.1]/', 'http://[2001:db8::1]/', 'http://1.2.3.4/', 'https://8.8.8.8/',
    'http://printer.local/', 'http://db.internal/', 'http://router.lan/', 'http://nas.home.arpa/', 'http://intranet/', 'http://single/', 'http://corp.corp/',
    'https://example.org:22/', 'https://example.org:25/', 'https://example.org:3306/', 'https://example.org:9999/', 'http://example.org:0/',
    'https://user:pass@example.org/', 'https://user@example.org/',
    'https://www.liuyingapp.com/api/auth/me', 'https://liuyingapp.com/', 'https://evil.liuyingapp.com/', 'https://liuying.liuyingapp.workers.dev/', 'https://x.workers.dev/',
    'https://127.0.0.1.nip.io/', 'https://10.0.0.1.sslip.io/', 'https://localtest.me/',
    'https://exa_mple.org/', 'https://-bad.example.org/', 'https://example.123/', 'https://a..b.example/',
  ];
  for (const u of refused) assert.equal(checkTarget(u).ok, false, `should refuse ${u}`);
  assert.equal(checkTarget('https://' + 'a'.repeat(64) + '.example.org/').ok, false);
  assert.equal(checkTarget('https://example.org/' + 'a'.repeat(5000)).ok, false);
});

test('checkTarget refuses the Worker own hostnames passed in', () => {
  assert.equal(checkTarget('https://myapp.example.net/', { ownHosts: ['myapp.example.net'] }).ok, false);
  assert.equal(checkTarget('https://x.myapp.example.net/', { ownHosts: ['myapp.example.net'] }).ok, false);
  assert.equal(checkTarget('https://other.example.net/', { ownHosts: ['myapp.example.net'] }).ok, true);
  assert.match(blockedHostReason('8.8.8.8'), /IP/);
});

test('request headers: cookie, authorization, host, cf-*, forwarding and hop-by-hop headers are dropped', () => {
  const { headers, dropped } = sanitizeRequestHeaders({
    Cookie: 'liuying_sid=secret', Authorization: 'Bearer x', Host: 'evil.test', 'CF-Connecting-IP': '1.2.3.4', 'cf-worker': 'x', 'X-Forwarded-For': '1.1.1.1',
    'x-forwarded-host': 'a', Forwarded: 'for=1.1.1.1', Via: '1.1 x', 'X-Real-IP': '1.1.1.1', 'True-Client-IP': '1.1.1.1', 'Proxy-Authorization': 'x', 'Content-Length': '5',
    Connection: 'keep-alive', 'Transfer-Encoding': 'chunked', Upgrade: 'websocket', 'Accept-Encoding': 'br', 'Sec-Fetch-Site': 'same-origin', Cookie2: 'a',
    Referer: 'https://example.org/', 'User-Agent': 'Plugin/1', 'X-Api-Key': 'abc', 'Content-Type': 'application/json', Origin: 'https://example.org',
  });
  assert.deepEqual(Object.keys(headers).sort(), ['content-type', 'origin', 'referer', 'user-agent', 'x-api-key']);
  for (const n of ['cookie', 'authorization', 'host', 'cf-connecting-ip', 'cf-worker', 'x-forwarded-for', 'forwarded', 'via', 'x-real-ip', 'content-length', 'transfer-encoding', 'sec-fetch-site']) {
    assert.ok(dropped.includes(n), `${n} reported as dropped`);
  }
});

test('request headers: bad names and values are dropped, count is capped', () => {
  const { headers } = sanitizeRequestHeaders({ 'bad name': 'x', 'ok': 'line1\r\nInjected: 1', 'also-ok': 'fine', ['x'.repeat(101)]: 'v', num: 5, obj: {}, nul: 'a\u0000b' });
  assert.deepEqual(headers, { 'also-ok': 'fine', num: '5' });
  const many = Object.fromEntries(Array.from({ length: 50 }, (_, i) => [`x-h${i}`, 'v']));
  assert.equal(Object.keys(sanitizeRequestHeaders(many).headers).length, LIMITS.MAX_HEADERS);
  assert.deepEqual(sanitizeRequestHeaders(null).headers, {});
  assert.deepEqual(sanitizeRequestHeaders(['a']).headers, {});
});

test('response headers: Set-Cookie never reaches the plugin; duplicates are joined', () => {
  const h = new Headers();
  h.append('Set-Cookie', 'a=1'); h.append('Set-Cookie', 'b=2'); h.append('X-Thing', 'one'); h.append('X-Thing', 'two'); h.set('Content-Type', 'text/html');
  const out = filterResponseHeaders(h.entries());
  assert.equal(out['set-cookie'], undefined);
  assert.equal(out['x-thing'], 'one, two');
  assert.equal(out['content-type'], 'text/html');
});

test('isTextual', () => {
  for (const t of ['', 'text/html; charset=utf-8', 'application/json', 'application/vnd.api+json', 'application/xml', 'application/vnd.apple.mpegurl', 'application/javascript']) assert.equal(isTextual(t), true, t);
  for (const t of ['image/png', 'video/mp4', 'application/octet-stream', 'application/zip', 'audio/mpeg']) assert.equal(isTextual(t), false, t);
});

test('parseRelaySpec: methods, body rules and the 64 KB cap', () => {
  assert.equal(parseRelaySpec({ url: 'https://example.org/' }).method, 'GET');
  assert.throws(() => parseRelaySpec({ url: 'https://example.org/', method: 'PUT' }), (e) => e.status === 405);
  assert.throws(() => parseRelaySpec({ url: 'https://example.org/', method: 'DELETE' }), (e) => e.status === 405);
  assert.throws(() => parseRelaySpec({ url: 'https://example.org/', method: 'GET', body: 'x' }), (e) => e.status === 400);
  assert.throws(() => parseRelaySpec({ url: 'https://example.org/', method: 'POST', body: 'x'.repeat(65537) }), (e) => e.status === 413);
  assert.throws(() => parseRelaySpec({ url: 'https://example.org/', method: 'POST', body: 'é'.repeat(33000) }), (e) => e.status === 413); // bytes, not characters
  assert.equal(parseRelaySpec({ url: 'https://example.org/', method: 'post', body: 'x'.repeat(65536) }).body.length, 65536);
  assert.throws(() => parseRelaySpec({ url: 5 }), (e) => e.status === 400);
  assert.throws(() => parseRelaySpec([]), (e) => e.status === 400);
  assert.throws(() => parseRelaySpec({ url: 'https://example.org/', method: 'POST', body: { a: 1 } }), (e) => e.status === 400);
});

// ---- relayFetch with a scripted fetch ---------------------------------------------------------------------------------
const script = (handlers) => {
  const calls = [];
  const fn = async (url, init) => { calls.push({ url, init }); return handlers[Math.min(calls.length, handlers.length) - 1](url, init); };
  fn.calls = calls;
  return fn;
};
const resp = (body, init = {}) => new Response(body, init);
const redirect = (status, location) => new Response(null, { status, headers: { location } });

test('relayFetch: sends a Liuying UA, sanitised headers, manual redirects, returns text and strips Set-Cookie', async () => {
  const f = script([async () => resp('hello', { status: 200, headers: { 'content-type': 'text/plain; charset=utf-8', 'set-cookie': 'sid=1', 'x-a': 'b' } })]);
  const spec = parseRelaySpec({ url: 'https://example.org/p?q=1', headers: { Cookie: 'liuying_sid=x', Referer: 'https://example.org/', Authorization: 'Bearer z' } });
  const r = await relayFetch(spec, { fetchImpl: f });
  assert.deepEqual([r.status, r.body, r.binary, r.hops, r.url], [200, 'hello', false, 1, 'https://example.org/p?q=1']);
  assert.equal(r.headers['set-cookie'], undefined);
  assert.equal(r.headers['x-a'], 'b');
  const sent = f.calls[0].init;
  assert.equal(sent.redirect, 'manual');
  assert.equal(sent.headers.get('user-agent'), RELAY_UA);
  assert.equal(sent.headers.get('referer'), 'https://example.org/');
  assert.equal(sent.headers.get('cookie'), null);
  assert.equal(sent.headers.get('authorization'), null);
});

test('relayFetch: a plugin-chosen User-Agent is honoured, the Liuying one is the default', async () => {
  const f = script([async () => resp('x')]);
  await relayFetch(parseRelaySpec({ url: 'https://example.org/', headers: { 'User-Agent': 'Mozilla/5.0 X' } }), { fetchImpl: f });
  assert.equal(f.calls[0].init.headers.get('user-agent'), 'Mozilla/5.0 X');
});

test('relayFetch: follows redirects by hand, re-validating every hop', async () => {
  const f = script([async () => redirect(302, '/b'), async () => redirect(301, 'https://other.example.net/c'), async () => resp('done')]);
  const r = await relayFetch(parseRelaySpec({ url: 'https://example.org/a' }), { fetchImpl: f });
  assert.deepEqual([r.body, r.url, r.hops], ['done', 'https://other.example.net/c', 3]);
  assert.deepEqual(f.calls.map((c) => c.url), ['https://example.org/a', 'https://example.org/b', 'https://other.example.net/c']);
});

test('relayFetch: a redirect to a private or own address is refused', async () => {
  for (const loc of ['http://127.0.0.1/admin', 'http://169.254.169.254/', 'https://www.liuyingapp.com/api/auth/me', 'http://localhost:8787/', 'ftp://example.org/', 'https://example.org:22/', 'http://[::1]/']) {
    const f = script([async () => redirect(302, loc), async () => resp('LEAK')]);
    await assert.rejects(relayFetch(parseRelaySpec({ url: 'https://example.org/a' }), { fetchImpl: f }), (e) => e.status === 502 && e.code === 'blocked_target' && /Redirect refused/.test(e.message), loc);
    assert.equal(f.calls.length, 1, `${loc} was never requested`);
  }
});

test('relayFetch: at most 5 redirects', async () => {
  const f = script([async () => redirect(302, '/next')]);
  await assert.rejects(relayFetch(parseRelaySpec({ url: 'https://example.org/a' }), { fetchImpl: f }), (e) => e.code === 'too_many_redirects');
  assert.equal(f.calls.length, 6);
});

test('relayFetch: 302 turns POST into GET without body; 307 keeps POST and body', async () => {
  let f = script([async () => redirect(302, '/b'), async () => resp('ok')]);
  await relayFetch(parseRelaySpec({ url: 'https://example.org/a', method: 'POST', body: 'k=v', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }), { fetchImpl: f });
  assert.equal(f.calls[1].init.method, 'GET');
  assert.equal(f.calls[1].init.body, undefined);
  assert.equal(f.calls[1].init.headers.get('content-type'), null);
  f = script([async () => redirect(307, '/b'), async () => resp('ok')]);
  await relayFetch(parseRelaySpec({ url: 'https://example.org/a', method: 'POST', body: 'k=v' }), { fetchImpl: f });
  assert.deepEqual([f.calls[1].init.method, f.calls[1].init.body], ['POST', 'k=v']);
});

test('relayFetch: refuses a blocked first target without calling fetch', async () => {
  const f = script([async () => resp('LEAK')]);
  await assert.rejects(relayFetch(parseRelaySpec({ url: 'http://10.0.0.5/' }), { fetchImpl: f }), (e) => e.status === 400 && e.code === 'blocked_target');
  assert.equal(f.calls.length, 0);
});

test('relayFetch: response over 5 MB is aborted while streaming (no Content-Length to trust)', async () => {
  let pulled = 0; let cancelled = false;
  const stream = new ReadableStream({
    pull(c) { pulled++; c.enqueue(new Uint8Array(1024 * 1024)); },
    cancel() { cancelled = true; },
  });
  const f = script([async () => new Response(stream, { headers: { 'content-type': 'text/plain' } })]);
  await assert.rejects(relayFetch(parseRelaySpec({ url: 'https://example.org/big' }), { fetchImpl: f }), (e) => e.code === 'too_large' && /5 MB/.test(e.message));
  assert.ok(pulled <= 8, `stopped reading early (pulled ${pulled})`);
  assert.equal(cancelled, true);
});

test('relayFetch: exactly 5 MB is fine, a declared larger Content-Length is refused without reading', async () => {
  let f = script([async () => resp(new Uint8Array(5 * 1024 * 1024).fill(97), { headers: { 'content-type': 'text/plain' } })]);
  assert.equal((await relayFetch(parseRelaySpec({ url: 'https://example.org/' }), { fetchImpl: f })).body.length, 5 * 1024 * 1024);
  let read = false;
  f = script([async () => ({ status: 200, statusText: 'OK', headers: new Headers({ 'content-length': String(6 * 1024 * 1024), 'content-type': 'text/plain' }), body: { getReader() { read = true; }, cancel() { return Promise.resolve(); } } })]);
  await assert.rejects(relayFetch(parseRelaySpec({ url: 'https://example.org/' }), { fetchImpl: f }), (e) => e.code === 'too_large');
  assert.equal(read, false);
});

test('relayFetch: binary content is returned as base64 with the flag, larger than 512 KB is refused', async () => {
  let f = script([async () => resp(new Uint8Array([0, 1, 2, 250, 255]), { headers: { 'content-type': 'application/octet-stream' } })]);
  const r = await relayFetch(parseRelaySpec({ url: 'https://example.org/f.bin' }), { fetchImpl: f });
  assert.equal(r.binary, true);
  assert.deepEqual([...Buffer.from(r.body, 'base64')], [0, 1, 2, 250, 255]);
  f = script([async () => resp(new Uint8Array(600 * 1024), { headers: { 'content-type': 'image/png' } })]);
  await assert.rejects(relayFetch(parseRelaySpec({ url: 'https://example.org/f.png' }), { fetchImpl: f }), (e) => e.code === 'too_large');
});

test('relayFetch: charset from Content-Type is used; HEAD returns no body', async () => {
  const latin = new Uint8Array([0x63, 0x61, 0x66, 0xe9]);
  let f = script([async () => resp(latin, { headers: { 'content-type': 'text/html; charset=iso-8859-1' } })]);
  assert.equal((await relayFetch(parseRelaySpec({ url: 'https://example.org/' }), { fetchImpl: f })).body, 'café');
  f = script([async () => resp('IGNORED', { headers: { 'content-type': 'text/html' } })]);
  assert.equal((await relayFetch(parseRelaySpec({ url: 'https://example.org/', method: 'HEAD' }), { fetchImpl: f })).body, '');
});

test('relayFetch: network failures say which host; a slow upstream times out', async () => {
  let f = script([async () => { throw new TypeError('Network connection lost.'); }]);
  await assert.rejects(relayFetch(parseRelaySpec({ url: 'https://down.example.org/' }), { fetchImpl: f }), (e) => e.status === 502 && /^Could not reach down\.example\.org/.test(e.message));
  f = script([(url, init) => new Promise((_, rej) => init.signal.addEventListener('abort', () => rej(new Error('aborted'))))]);
  await assert.rejects(relayFetch(parseRelaySpec({ url: 'https://slow.example.org/' }), { fetchImpl: f, timeoutMs: 30 }), (e) => e.status === 504 && e.code === 'timeout');
});

// ---- /api/relay through handleApi ----------------------------------------------------------------------------------------
async function signedIn() {
  const ctx = setup();
  const c = new ctx.Client();
  assert.equal((await c.post('/api/auth/signup', { username: 'relayer', password: PW })).status, 201);
  return { ...ctx, c };
}
const withFetch = async (impl, fn) => { const old = globalThis.fetch; globalThis.fetch = impl; try { return await fn(); } finally { globalThis.fetch = old; } };

test('/api/relay requires a signed-in session and the usual CSRF rules', async () => {
  const { Client, c } = await signedIn();
  const f = script([async () => resp('x')]);
  await withFetch(f, async () => {
    assert.equal((await new Client().post('/api/relay', { url: 'https://example.org/' })).status, 401);
    assert.equal((await c.get('/api/relay')).status, 405);
    assert.equal((await c.req('POST', '/api/relay', { url: 'https://example.org/' }, { 'sec-fetch-site': 'cross-site' })).status, 403);
    assert.equal((await c.req('POST', '/api/relay', { url: 'https://example.org/' }, { origin: 'https://evil.example' })).status, 403);
    assert.equal((await c.req('POST', '/api/relay', { url: 'https://example.org/' }, { 'content-type': 'text/plain' })).status, 415);
    assert.equal(f.calls.length, 0);
    const ok = await c.post('/api/relay', { url: 'https://example.org/' });
    assert.equal(ok.status, 200);
    assert.equal(ok.data.body, 'x');
    assert.match(ok.headers.get('cache-control'), /no-store/);
  });
});

test('/api/relay never forwards the session cookie or any client-supplied credential header', async () => {
  const { c } = await signedIn();
  const f = script([async () => resp('{}', { headers: { 'content-type': 'application/json', 'set-cookie': 'tracker=1' } })]);
  await withFetch(f, async () => {
    const r = await c.post('/api/relay', { url: 'https://example.org/', headers: { Cookie: 'liuying_sid=stolen', Authorization: 'Bearer t', 'X-Forwarded-For': '9.9.9.9', Accept: 'application/json' } });
    assert.equal(r.status, 200);
    assert.equal(r.data.headers['set-cookie'], undefined);
    assert.equal(r.headers.getSetCookie().length, 0);
  });
  const h = f.calls[0].init.headers;
  assert.deepEqual([h.get('cookie'), h.get('authorization'), h.get('x-forwarded-for'), h.get('accept')], [null, null, null, 'application/json']);
  assert.ok(![...h.entries()].some(([k, v]) => /liuying_sid|cf-/i.test(k + v)));
});

test('/api/relay refuses blocked targets with 400 and does not call fetch', async () => {
  const { c } = await signedIn();
  const f = script([async () => resp('LEAK')]);
  await withFetch(f, async () => {
    for (const url of ['http://127.0.0.1/', 'http://localhost/', 'https://liuying.test/api/auth/me', 'https://www.liuyingapp.com/', 'http://[::1]/', 'http://10.1.1.1/', 'ftp://example.org/']) {
      const r = await c.post('/api/relay', { url });
      assert.equal(r.status, 400, url);
      assert.equal(r.data.error, 'blocked_target');
    }
    assert.equal((await c.post('/api/relay', { url: 'https://example.org/', method: 'DELETE' })).status, 405);
    assert.equal((await c.post('/api/relay', { url: 'https://example.org/', method: 'POST', body: 'x'.repeat(70000) })).status, 413);
    assert.equal((await c.post('/api/relay', {})).status, 400);
  });
  assert.equal(f.calls.length, 0);
});

test('/api/relay limits: 120 per minute and 3000 per day per user, other users unaffected', async () => {
  const { c, Client, clock } = await signedIn();
  const other = new Client();
  await other.post('/api/auth/signup', { username: 'other_one', password: PW });
  const f = async () => resp('x');
  await withFetch(f, async () => {
    for (let i = 0; i < 120; i++) assert.equal((await c.post('/api/relay', { url: 'https://example.org/' })).status, 200, `call ${i}`);
    const blocked = await c.post('/api/relay', { url: 'https://example.org/' });
    assert.equal(blocked.status, 429);
    assert.equal(blocked.data.error, 'rate_limited');
    assert.ok(Number(blocked.headers.get('retry-after')) >= 1);
    assert.equal((await other.post('/api/relay', { url: 'https://example.org/' })).status, 200);
    clock.t += 61_000;
    assert.equal((await c.post('/api/relay', { url: 'https://example.org/' })).status, 200);
  });
});

test('daily counter: one row per user and day, blocks at the limit, resets next UTC day, survives housekeeping', async () => {
  const { db } = setup();
  const t = Date.UTC(2026, 9, 27, 12, 0, 0);
  for (let i = 0; i < 5; i++) assert.equal((await checkRelay(db, 'u1', 5, t + i)).allowed, true);
  const sixth = await checkRelay(db, 'u1', 5, t + 10);
  assert.deepEqual([sixth.allowed, sixth.reason], [false, 'day']);
  assert.ok(sixth.retryAfterSec > 0 && sixth.retryAfterSec <= 12 * 3600);
  assert.equal((await checkRelay(db, 'u2', 5, t)).allowed, true);
  const rows = db.sqlite.prepare("SELECT k, ts FROM rate_hits WHERE k LIKE 'relayDay:%' ORDER BY k").all();
  assert.deepEqual(rows.map((r) => [r.k, r.ts]), [['relayDay:2026-10-27:u1', DAY_BASE + 5], ['relayDay:2026-10-27:u2', DAY_BASE + 1]]);
  db.sqlite.prepare('DELETE FROM rate_hits WHERE ts <= ?').run(t); // what the housekeeping does
  assert.equal(db.sqlite.prepare("SELECT count(*) n FROM rate_hits WHERE k LIKE 'relayDay:%'").get().n, 2);
  assert.equal((await checkRelay(db, 'u1', 5, t + 24 * 3600_000)).allowed, true);
});
