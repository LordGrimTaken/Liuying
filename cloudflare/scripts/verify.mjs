// End-to-end checks against a running deployment (wrangler dev or workers.dev).
//   node scripts/verify.mjs https://liuying.<sub>.workers.dev <phase>      phase: static | account | sync | limits | all
// Signup is limited to 5/hour per IP, so on a real deployment run the phases separately and clear the
// limiter table in between:  DELETE FROM rate_hits;   (wrangler d1 execute liuying --remote --command "DELETE FROM rate_hits")
// Every account the script creates is deleted again (also on failure, best effort).
import crypto from 'node:crypto';
import fs from 'node:fs';

const base = (process.argv[2] || 'http://127.0.0.1:8799').replace(/\/$/, '');
const phase = process.argv[3] || 'all';
const host = new URL(base).host;
const results = [];
const check = (name, ok, extra = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !extra ? '' : `  -> ${extra}`}`); };
const eq = (name, got, want) => check(name, JSON.stringify(got) === JSON.stringify(want), `got ${JSON.stringify(got)} want ${JSON.stringify(want)}`);

class Client {
  constructor() { this.jar = new Map(); this.lastSetCookie = []; }
  async req(method, path, { body, headers = {}, json = true, raw } = {}) {
    const h = { ...headers };
    if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join('; ');
    if (body !== undefined && json) { h['content-type'] ??= 'application/json'; }
    const res = await fetch(base + path, { method, headers: h, body: raw ?? (body === undefined ? undefined : JSON.stringify(body)), redirect: 'manual' });
    this.lastSetCookie = res.headers.getSetCookie?.() ?? [];
    for (const c of this.lastSetCookie) {
      const [kv] = c.split(';'); const i = kv.indexOf('=');
      const k = kv.slice(0, i); const v = kv.slice(i + 1);
      if (/expires=Thu, 01 Jan 1970/i.test(c) || v === '') this.jar.delete(k); else this.jar.set(k, v);
    }
    let data = null; const text = await res.text();
    try { data = JSON.parse(text); } catch { data = text; }
    return { status: res.status, headers: res.headers, data, text };
  }
  get(p, o) { return this.req('GET', p, o); }
  post(p, body, o = {}) { return this.req('POST', p, { body: body ?? {}, ...o }); }
  put(p, body) { return this.req('PUT', p, { body }); }
  del(p) { return this.req('DELETE', p); }
}

const created = [];
const strong = () => `Pw-${crypto.randomBytes(6).toString('hex')}-x9`;
const uname = (p) => `${p}${crypto.randomBytes(3).toString('hex')}`;
async function cleanup() {
  for (const a of created) {
    const c = new Client();
    const l = await c.post('/api/auth/login', { username: a.username, password: a.password });
    if (l.status === 200) await c.post('/api/auth/delete', { password: a.password });
  }
}

async function staticPhase() {
  const c = new Client();
  const h = (r, k) => r.headers.get(k);
  let r = await c.get('/');
  eq('GET / 200', r.status, 200);
  check('strict CSP on /', /default-src 'self'/.test(h(r, 'content-security-policy')) && !/https:/.test(h(r, 'content-security-policy')), h(r, 'content-security-policy'));
  for (const [k, v] of [['x-content-type-options', 'nosniff'], ['x-frame-options', 'DENY'], ['referrer-policy', 'no-referrer'], ['cross-origin-opener-policy', 'same-origin'], ['x-xss-protection', '0']]) eq(`header ${k}`, h(r, k), v);
  check('HSTS header', /max-age=31536000/.test(h(r, 'strict-transport-security') || ''));
  for (const p of ['/login', '/signup', '/recover', '/account', '/download']) { r = await c.get(p); eq(`GET ${p} 200`, r.status, 200); }
  r = await c.get('/watch'); eq('/watch 200', r.status, 200);
  check('/watch relaxed CSP (connect-src https:)', /connect-src 'self' https:/.test(h(r, 'content-security-policy')) && (h(r, 'content-security-policy').match(/default-src/g) || []).length === 1, h(r, 'content-security-policy'));
  check('/watch html shell', /<html/i.test(r.text));
  r = await c.get('/watch/some/deep/link'); eq('/watch deep link serves SPA shell', r.status, 200); check('deep link body is shell', /<html/i.test(r.text));
  r = await c.get('/watch/sw.js'); eq('/watch/sw.js 200', r.status, 200); eq('sw.js no-cache', h(r, 'cache-control'), 'no-cache'); eq('sw.js Service-Worker-Allowed', h(r, 'service-worker-allowed'), '/watch/');
  r = await c.get('/watch/manifest.webmanifest'); check('webmanifest content-type', /application\/manifest\+json/.test(h(r, 'content-type')), h(r, 'content-type'));
  r = await c.get('/watch/test/progress.test.mjs'); eq('/watch/test not public', r.status, 404);
  r = await c.get('/watch/test'); eq('/watch/test 404', r.status, 404);
  r = await c.get('/downloads/latest.json'); eq('latest.json 200', r.status, 200); eq('latest.json no-cache', h(r, 'cache-control'), 'no-cache'); check('latest.json content-type', /application\/json/.test(h(r, 'content-type')));
  const latest = JSON.parse(r.text);
  const apkName = new URL(latest.apkUrl).pathname.split('/').pop();
  const apk = await fetch(`${base}/downloads/${apkName}`);
  const buf = Buffer.from(await apk.arrayBuffer());
  eq('APK 200', apk.status, 200);
  eq('APK content-type', apk.headers.get('content-type'), 'application/vnd.android.package-archive');
  check('APK content-disposition', /attachment; filename="/.test(apk.headers.get('content-disposition') || ''));
  eq('APK cache-control', apk.headers.get('cache-control'), 'public, max-age=3600');
  eq('APK size matches latest.json', buf.length, latest.sizeBytes);
  eq('APK sha256 matches latest.json', crypto.createHash('sha256').update(buf).digest('hex'), latest.sha256);
  const head = await fetch(`${base}/downloads/${apkName}`, { method: 'HEAD' }); eq('HEAD APK 200', head.status, 200); check('HEAD APK has no body', (await head.arrayBuffer()).byteLength === 0);
  r = await c.req('HEAD', '/'); eq('HEAD / 200', r.status, 200);
  r = await c.req('HEAD', '/api/auth/me'); eq('HEAD /api/auth/me 401', r.status, 401);
  r = await c.req('HEAD', '/health'); eq('HEAD /health 200', r.status, 200);
  r = await c.get('/health'); eq('/health 200', r.status, 200); eq('/health body', r.text, 'ok');
  r = await c.get('/nope', { headers: { accept: 'text/html' } }); eq('404 status', r.status, 404); check('404 branded page', /Page not found/.test(r.text));
  r = await c.get('/nope', { headers: { accept: '*/*' } }); eq('404 non-browser', r.status, 404);
  r = await c.get('/api/nothing'); eq('/api/nothing 404 json', [r.status, r.data.error], [404, 'not_found']);
  eq('api cache-control no-store', r.headers.get('cache-control'), 'no-store');
  check('API responses carry CSP', /default-src 'self'/.test(r.headers.get('content-security-policy') || ''));
}

async function accountPhase() {
  const alice = { username: uname('alice'), password: strong() };
  created.push(alice);
  const c = new Client();
  // CSRF
  let r = await c.req('POST', '/api/auth/signup', { body: alice, headers: { origin: 'https://evil.example' } }); eq('CSRF foreign Origin -> 403', [r.status, r.data.error], [403, 'forbidden']);
  r = await c.req('POST', '/api/auth/signup', { body: alice, headers: { 'sec-fetch-site': 'cross-site' } }); eq('CSRF cross-site fetch metadata -> 403', r.status, 403);
  r = await c.req('POST', '/api/auth/signup', { raw: JSON.stringify(alice), headers: { 'content-type': 'text/plain' } }); eq('non-JSON content-type -> 415', [r.status, r.data.error], [415, 'unsupported_media_type']);
  r = await c.req('POST', '/api/auth/login', { raw: '{bad', headers: { 'content-type': 'application/json' } }); eq('invalid JSON -> 400', [r.status, r.data.error], [400, 'bad_request']);
  r = await c.req('POST', '/api/auth/login', { raw: '"str"', headers: { 'content-type': 'application/json' } }); eq('JSON primitive -> 400', r.status, 400);
  r = await c.req('POST', '/api/auth/login', { raw: JSON.stringify({ username: 'x'.repeat(5000), password: 'y' }), headers: { 'content-type': 'application/json' } }); eq('body > 2kb -> 400', [r.status, r.data.error], [400, 'bad_request']);
  r = await c.req('POST', '/api/auth/logout', { body: {}, headers: { origin: base } }); eq('same Origin allowed (logout 204)', r.status, 204);

  // signup (1) with recovery code
  r = await c.post('/api/auth/signup', { ...alice, recovery: true });
  eq('signup 201', r.status, 201); eq('signup username', r.data.user?.username, alice.username);
  check('recovery code format', /^[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}$/.test(r.data.recoveryCode || ''), r.data.recoveryCode);
  let code = r.data.recoveryCode;
  const sc = c.lastSetCookie[0] || '';
  check('cookie attrs', /^liuying_sid=[^;]+; Max-Age=2592000; Path=\/; Expires=.+; HttpOnly; (Secure; )?SameSite=Lax$/.test(sc), sc.replace(/=[^;]{8,}/, '=<token>'));
  r = await c.get('/api/auth/me'); eq('me after signup', [r.status, r.data], [200, { user: { username: alice.username } }]);
  r = await c.get('/api/auth/recovery'); eq('recovery status enabled', r.data.enabled, true); check('recovery createdAt ISO', !Number.isNaN(Date.parse(r.data.createdAt)));
  r = await c.get('/api/auth/sessions'); eq('1 session, current', [r.data.sessions.length, r.data.sessions[0].current], [1, true]); check('session id 16 hex', /^[0-9a-f]{16}$/.test(r.data.sessions[0].id));

  // signup CJK (2)
  const cjk = { username: `用户${crypto.randomBytes(2).toString('hex')}`, password: strong() };
  created.push(cjk);
  const cc = new Client();
  r = await cc.post('/api/auth/signup', cjk); eq('CJK signup 201', [r.status, r.data.user?.username], [201, cjk.username]);
  r = await cc.get('/api/auth/me'); eq('CJK me', r.data.user?.username, cjk.username);
  // duplicate / normalisation (3,4,5)
  r = await new Client().post('/api/auth/signup', { username: alice.username.toUpperCase(), password: strong() }); eq('duplicate (case) -> 409', [r.status, r.data.error], [409, 'username_taken']);
  r = await new Client().post('/api/auth/signup', { username: 'a', password: strong() }); eq('short username -> 400', [r.status, r.data.error], [400, 'invalid_username']);
  r = await new Client().post('/api/auth/signup', { username: uname('weak'), password: 'password123' }); eq('common password -> 400', [r.status, r.data.error], [400, 'invalid_password']);

  // login: fullwidth/NFKC and case variants resolve to the same account
  const fw = [...alice.username].map((ch) => (/[a-z0-9]/i.test(ch) ? String.fromCharCode(ch.charCodeAt(0) + 0xfee0) : ch)).join('');
  const l1 = new Client();
  r = await l1.post('/api/auth/login', { username: fw.toUpperCase(), password: alice.password }); eq('login with fullwidth/upper-case name', [r.status, r.data.user?.username], [200, alice.username]);
  r = await new Client().post('/api/auth/login', { username: alice.username, password: 'wrong-Password-1' }); eq('wrong password -> 401', [r.status, r.data.error], [401, 'invalid_credentials']);
  r = await new Client().post('/api/auth/login', { username: uname('ghost'), password: 'whatever-Pass-1' }); eq('unknown user -> 401 (same error)', [r.status, r.data.error], [401, 'invalid_credentials']);
  r = await new Client().post('/api/auth/login', { username: 5, password: 'x' }); eq('login shape -> 400', r.status, 400);
  r = await l1.get('/api/auth/sessions'); eq('2 sessions after second login', r.data.sessions.length, 2);

  // me soft/401, logout
  r = await new Client().get('/api/auth/me'); eq('me unauthenticated 401', [r.status, r.data.error], [401, 'unauthenticated']);
  r = await new Client().get('/api/auth/me?soft=1'); eq('me soft -> 200 null', [r.status, r.data], [200, { user: null }]);
  const lo = new Client(); await lo.post('/api/auth/login', { username: alice.username, password: alice.password });
  r = await lo.post('/api/auth/logout'); eq('logout 204', r.status, 204); check('logout clears cookie', /liuying_sid=;/.test(lo.lastSetCookie[0] || ''));
  r = await lo.get('/api/auth/me'); eq('me after logout 401', r.status, 401);

  // sessions: revoke one, revoke others, logout-all
  const s2 = new Client(); await s2.post('/api/auth/login', { username: alice.username, password: alice.password });
  r = await l1.get('/api/auth/sessions'); const others = r.data.sessions.filter((s) => !s.current);
  check('sees other sessions', others.length >= 2, JSON.stringify(r.data));
  r = await l1.del(`/api/auth/sessions/${others[0].id}`); eq('revoke one -> 204', r.status, 204);
  r = await l1.del('/api/auth/sessions/0123456789abcdef'); eq('revoke unknown -> 404', [r.status, r.data.error], [404, 'not_found']);
  r = await l1.del('/api/auth/sessions/NOTHEX'); eq('revoke malformed -> 404', r.status, 404);
  r = await l1.post('/api/auth/sessions/revoke-others'); eq('revoke-others 204', r.status, 204);
  r = await l1.get('/api/auth/sessions'); eq('only current remains', r.data.sessions.length, 1);
  r = await s2.get('/api/auth/me'); eq('other device signed out', r.status, 401);

  // password change
  r = await l1.post('/api/auth/password', { currentPassword: 'nope-Nope-1', newPassword: strong() }); eq('change pw wrong current -> 403', [r.status, r.data.error], [403, 'invalid_credentials']);
  r = await l1.post('/api/auth/password', { currentPassword: alice.password, newPassword: alice.password }); eq('change pw same -> 400', r.status, 400);
  r = await new Client().post('/api/auth/password', { currentPassword: 'x', newPassword: 'y' }); eq('change pw unauthenticated -> 401', r.status, 401);
  const oldPw = alice.password; alice.password = strong();
  const s3 = new Client(); await s3.post('/api/auth/login', { username: alice.username, password: oldPw });
  r = await l1.post('/api/auth/password', { currentPassword: oldPw, newPassword: alice.password }); eq('change pw 200', [r.status, r.data.user?.username], [200, alice.username]);
  r = await l1.get('/api/auth/me'); eq('current session rotated and still valid', r.status, 200);
  r = await s3.get('/api/auth/me'); eq('other sessions revoked after pw change', r.status, 401);
  r = await new Client().post('/api/auth/login', { username: alice.username, password: oldPw }); eq('old password rejected', r.status, 401);

  // recovery regen + recover flow
  r = await l1.post('/api/auth/recovery', { password: 'bad-Password-1' }); eq('recovery regen wrong pw -> 403', r.status, 403);
  r = await l1.post('/api/auth/recovery', { password: alice.password }); eq('recovery regen 200', r.status, 200);
  const oldCode = code; code = r.data.recoveryCode;
  const rc = new Client();
  r = await rc.post('/api/auth/recover', { username: alice.username, recoveryCode: oldCode, newPassword: strong() }); eq('old recovery code rejected', [r.status, r.data.error], [401, 'invalid_recovery']);
  r = await rc.post('/api/auth/recover', { username: alice.username, recoveryCode: code.toLowerCase().replace(/-/g, ' '), newPassword: 'short' }); eq('recover with weak new pw -> 400 (code kept)', [r.status, r.data.error], [400, 'invalid_password']);
  const recPw = strong();
  r = await rc.post('/api/auth/recover', { username: alice.username.toUpperCase(), recoveryCode: code.toLowerCase(), newPassword: recPw }); eq('recover 200', [r.status, r.data.user?.username], [200, alice.username]);
  check('recover returns a fresh code', /^[0-9A-Z-]{23}$/.test(r.data.recoveryCode || '') && r.data.recoveryCode !== code);
  alice.password = recPw;
  r = await rc.get('/api/auth/me'); eq('recover signs in', r.status, 200);
  r = await l1.get('/api/auth/me'); eq('recover revoked old sessions', r.status, 401);
  r = await new Client().post('/api/auth/recover', { username: alice.username, recoveryCode: code, newPassword: strong() }); eq('recovery code is single use', r.status, 401);
  r = await new Client().post('/api/auth/recover', { username: uname('ghost'), recoveryCode: 'AAAAA-AAAAA-AAAAA-AAAAA', newPassword: strong() }); eq('recover unknown user -> 401', r.status, 401);
  r = await rc.post('/api/auth/logout-all'); eq('logout-all 204', r.status, 204);
  r = await rc.get('/api/auth/me'); eq('logout-all signs out', r.status, 401);

  // delete account
  const d = new Client(); await d.post('/api/auth/login', { username: alice.username, password: alice.password });
  r = await d.post('/api/auth/delete', { password: 'nope-Nope-1' }); eq('delete wrong pw -> 403', r.status, 403);
  r = await d.post('/api/auth/delete', { password: alice.password }); eq('delete 204', r.status, 204);
  r = await d.get('/api/auth/me'); eq('deleted account session gone', r.status, 401);
  r = await new Client().post('/api/auth/login', { username: alice.username, password: alice.password }); eq('deleted account cannot log in', r.status, 401);
  created.splice(created.indexOf(alice), 1);
}

async function syncPhase() {
  const a = { username: uname('sync'), password: strong() }; created.push(a);
  const c = new Client();
  let r = await new Client().get('/api/sync/state'); eq('sync unauthenticated -> 401', [r.status, r.data.error], [401, 'unauthenticated']);
  r = await c.post('/api/auth/signup', a); eq('signup for sync', r.status, 201);
  r = await c.get('/api/sync/state');
  eq('empty state', [r.status, r.data.sources, r.data.library, r.data.progress, r.data.settings], [200, [], [], [], {}]); check('serverTime number', typeof r.data.serverTime === 'number');
  // sources
  r = await c.put('/api/sync/sources', { sources: [{ url: 'https://a.example/manifest.json' }, { url: 'https://b.example/m.json', enabled: false }, { url: 'https://a.example/manifest.json' }] });
  eq('sources PUT dedup', [r.status, r.data.sources], [200, [{ url: 'https://a.example/manifest.json', enabled: true }, { url: 'https://b.example/m.json', enabled: false }]]);
  r = await c.put('/api/sync/sources', { sources: [{ url: 'ftp://x' }] }); eq('sources bad url -> 400', [r.status, r.data.error], [400, 'bad_request']);
  r = await c.put('/api/sync/sources', { sources: Array.from({ length: 101 }, (_, i) => ({ url: `https://e${i}.example/` })) }); eq('101 sources -> 400 too_many_items', [r.status, r.data.error], [400, 'too_many_items']);
  r = await c.put('/api/sync/sources', { sources: [{ url: 'https://c.example/', enabled: 'yes' }] }); eq('source.enabled type', r.status, 400);
  // library
  r = await c.post('/api/sync/library', { add: [{ id: 'tt1', type: 'movie', name: 'Film 一', poster: 'https://p.example/1.jpg' }, { id: 'tt2', type: 'series', name: 'Show' }] }); eq('library add', [r.status, r.data.count], [200, 2]);
  r = await c.post('/api/sync/library', { add: [{ id: 'tt1', type: 'movie', name: 'Film renamed' }, { id: 'tt3', type: 'tv', name: 'TV' }], remove: ['tt2', 'nonexistent'] }); eq('library upsert+remove count', r.data.count, 2);
  r = await c.post('/api/sync/library', { add: [{ id: 'x', type: 'bogus', name: 'n' }] }); eq('library bad type', r.status, 400);
  r = await c.post('/api/sync/library', { add: [{ id: 'x', type: 'movie', name: 'n', poster: 'javascript:1' }] }); eq('library bad poster', r.status, 400);
  r = await c.get('/api/sync/state'); eq('library state (upsert without poster nulls it, like Express)', r.data.library.map((x) => [x.id, x.name, x.poster]), [['tt1', 'Film renamed', null], ['tt3', 'TV', null]]);
  // note: upserting without poster sets poster to null (same as the Express server)
  // progress LWW
  const t0 = Date.now();
  const p = (o) => ({ mediaId: 'm1', episodeId: null, positionMs: 1000, durationMs: 60000, watched: false, dismissed: false, updatedAt: t0, ...o });
  r = await c.post('/api/sync/progress', { items: [p({}), p({ mediaId: 'm1', episodeId: 'e1', updatedAt: t0 + 5 })] }); eq('progress insert accepted', r.data.accepted, 2);
  r = await c.post('/api/sync/progress', { items: [p({ positionMs: 9, updatedAt: t0 - 1 })] }); eq('older write ignored', r.data.accepted, 0);
  r = await c.post('/api/sync/progress', { items: [p({ positionMs: 9, updatedAt: t0 })] }); eq('equal timestamp ignored', r.data.accepted, 0);
  r = await c.post('/api/sync/progress', { items: [p({ positionMs: 5000, watched: true, updatedAt: t0 + 10 })] }); eq('newer write accepted', r.data.accepted, 1);
  r = await c.post('/api/sync/progress', { items: [p({ positionMs: 1, updatedAt: t0 + 20 }), p({ positionMs: 2, updatedAt: t0 + 30 })] }); eq('within-call dedupe: highest wins, one row', r.data.accepted, 1);
  r = await c.get('/api/sync/state'); const m1 = r.data.progress.find((x) => x.mediaId === 'm1' && x.episodeId === null);
  eq('LWW final row', [m1.positionMs, m1.updatedAt, m1.watched, r.data.progress.length], [2, t0 + 30, false, 2]);
  eq('progress sorted updatedAt desc', r.data.progress.map((x) => x.updatedAt), [t0 + 30, t0 + 5]);
  r = await c.post('/api/sync/progress', { items: [p({ updatedAt: Date.now() + 3 * 864e5 })] }); eq('future updatedAt -> 400', r.status, 400);
  r = await c.post('/api/sync/progress', { items: [p({ positionMs: 999999, durationMs: 10 })] }); eq('position > duration -> 400', r.status, 400);
  r = await c.post('/api/sync/progress', { items: Array.from({ length: 201 }, (_, i) => p({ mediaId: `z${i}` })) }); eq('201 items -> 400', [r.status, r.data.error], [400, 'too_many_items']);
  // bulk: 200 items in one call, and 5000-ish library
  r = await c.post('/api/sync/progress', { items: Array.from({ length: 200 }, (_, i) => p({ mediaId: `bulk${i}`, updatedAt: t0 + 100 + i })) }); eq('200 progress items in one call', r.data.accepted, 200);
  r = await c.post('/api/sync/library', { add: Array.from({ length: 1000 }, (_, i) => ({ id: `lib${i}`, type: 'movie', name: `Movie ${i}` })) }); eq('1000 library items in one call', [r.status, r.data.count], [200, 1002]);
  r = await c.post('/api/sync/library', { remove: Array.from({ length: 1000 }, (_, i) => `lib${i}`) }); eq('1000 removals', r.data.count, 2);
  // settings
  r = await c.put('/api/sync/settings', { settings: { theme: 'dark', n: [1, 2], 名: '值' } }); eq('settings PUT echo', [r.status, r.data.settings.theme, r.data.settings['名']], [200, 'dark', '值']);
  r = await c.put('/api/sync/settings', { settings: [] }); eq('settings array -> 400', r.status, 400);
  r = await c.put('/api/sync/settings', { settings: { big: 'x'.repeat(20000) } }); eq('settings > 16kb -> 413', [r.status, r.data.error], [413, 'payload_too_large']);
  r = await c.get('/api/sync/state'); eq('settings persisted', r.data.settings.theme, 'dark');
  r = await c.post('/api/sync/progress', { items: 'nope' }); eq('progress items type', r.status, 400);
  r = await c.req('POST', '/api/sync/library', { raw: JSON.stringify({ add: Array.from({ length: 4000 }, (_, i) => ({ id: `i${i}`, type: 'movie', name: 'n'.repeat(60) })) }), headers: { 'content-type': 'application/json' } }); eq('body > 256kb -> 413', [r.status, r.data.error], [413, 'payload_too_large']);
  r = await c.req('PUT', '/api/sync/sources', { raw: '{}', headers: { 'content-type': 'text/plain' } }); eq('sync non-JSON -> 415', r.status, 415);
  r = await c.req('PUT', '/api/sync/sources', { body: { sources: [] }, headers: { origin: 'https://evil.example' } }); eq('sync foreign origin -> 403', r.status, 403);
  // isolation: a second user sees none of it
  const b = { username: uname('syncb'), password: strong() }; created.push(b); const cb = new Client();
  r = await cb.post('/api/auth/signup', b); eq('second user signup', r.status, 201);
  r = await cb.get('/api/sync/state'); eq('second user isolated', [r.data.sources.length, r.data.library.length, r.data.progress.length], [0, 0, 0]);
  // delete all
  r = await c.del('/api/sync/all'); eq('DELETE /sync/all 204', r.status, 204);
  r = await c.get('/api/sync/state'); eq('all cleared', [r.data.sources.length, r.data.library.length, r.data.progress.length, r.data.settings], [0, 0, 0, {}]);
  r = await c.get('/api/sync/nothing'); eq('unknown sync route 404', r.status, 404);
}

async function limitsPhase() {
  // signup: 5 per hour per IP. The 6th is rejected before any validation.
  const codes = [];
  for (let i = 0; i < 6; i++) { const r = await new Client().post('/api/auth/signup', { username: '!', password: 'x' }); codes.push(r.status); if (r.status === 429) { check('429 has Retry-After', Number(r.headers.get('retry-after')) > 0); eq('429 body', r.data.error, 'rate_limited'); } }
  eq('signup limiter: 5 x 400 then 429', codes, [400, 400, 400, 400, 400, 429]);
  // login pair: 10 per 15 min for ip|user
  const u = uname('rl'); const lc = [];
  for (let i = 0; i < 11; i++) lc.push((await new Client().post('/api/auth/login', { username: u, password: 'wrong-Password-1' })).status);
  eq('login limiter: 10 x 401 then 429', lc, [...Array(10).fill(401), 429]);
  // recover per user: 5 per hour
  const rcs = []; const rr = uname('rr');
  for (let i = 0; i < 6; i++) rcs.push((await new Client().post('/api/auth/recover', { username: rr, recoveryCode: 'AAAAA-AAAAA-AAAAA-AAAAA', newPassword: 'x' })).status);
  check('recover limiter eventually 429', rcs.includes(429), JSON.stringify(rcs));
}

const phases = { static: staticPhase, account: accountPhase, sync: syncPhase, limits: limitsPhase };
try {
  for (const [name, fn] of Object.entries(phases)) if (phase === 'all' || phase === name) { console.log(`--- ${name} (${base})`); await fn(); }
} catch (e) { check(`unexpected exception: ${e.message}`, false, e.stack); }
finally { try { await cleanup(); } catch (e) { console.log('cleanup error', e.message); } }
const fails = results.filter((x) => !x).length;
console.log(`\n${results.length - fails}/${results.length} passed${fails ? `, ${fails} FAILED` : ''}`);
process.exit(fails ? 1 : 0);
