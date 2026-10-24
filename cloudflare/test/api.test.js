// Worker API on an in-memory SQLite D1 stand-in. Cases mirror server/test (auth, recovery, sync).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup } from './helpers.js';
import { checkD1 } from '../src/rateLimit.js';

const PW = 'correct horse battery';

test('CJK usernames: signup, login, length in code points', async () => {
  const { Client } = setup();
  const s = await new Client().post('/api/auth/signup', { username: '李雷', password: PW });
  assert.equal(s.status, 201);
  assert.deepEqual(s.data, { user: { username: '李雷' } });
  assert.equal((await new Client().post('/api/auth/login', { username: '李雷', password: PW })).status, 200);
  assert.equal((await new Client().post('/api/auth/login', { username: '李雷', password: 'wrong password!' })).status, 401);
  assert.equal((await new Client().post('/api/auth/signup', { username: '𠀀'.repeat(24), password: PW })).status, 201);
  assert.equal((await new Client().post('/api/auth/signup', { username: '𠀀'.repeat(25), password: PW })).status, 400);
  assert.equal((await new Client().post('/api/auth/signup', { username: 'Émile_é', password: PW })).status, 201);
  assert.equal((await new Client().post('/api/auth/signup', { username: '한국어사용자', password: PW })).status, 201);
});

test('login and uniqueness use NFKC + case folding; display form kept', async () => {
  const { Client } = setup();
  assert.equal((await new Client().post('/api/auth/signup', { username: 'Émile', password: PW })).status, 201);
  const li = await new Client().post('/api/auth/login', { username: 'Émile', password: PW });
  assert.equal(li.status, 200);
  assert.equal(li.data.user.username, 'Émile');
  assert.equal((await new Client().post('/api/auth/login', { username: 'éMILE', password: PW })).status, 200);
  const dup = await new Client().post('/api/auth/signup', { username: 'émile', password: 'another good pass' });
  assert.deepEqual([dup.status, dup.data.error], [409, 'username_taken']);
  assert.equal((await new Client().post('/api/auth/signup', { username: 'Ｂｏｂ_1', password: PW })).data.user.username, 'Bob_1');
  assert.equal((await new Client().post('/api/auth/signup', { username: 'bob_1', password: PW })).status, 409);
  assert.equal((await new Client().post('/api/auth/signup', { username: 'ｶﾀｶﾅ', password: PW })).status, 201);
  assert.equal((await new Client().post('/api/auth/signup', { username: 'カタカナ', password: PW })).status, 409);
});

test('rate limits apply across equivalent Unicode forms and across IPs (per user)', async () => {
  const { Client } = setup();
  let last;
  for (let i = 0; i < 45; i++) {
    last = await new Client().post('/api/auth/login', { username: i % 2 ? 'Émile' : 'Émile', password: 'guess guess' });
    if (last.status === 429) break;
  }
  assert.equal(last.status, 429);
  assert.ok(Number(last.headers.get('retry-after')) > 0);
  assert.equal(last.data.error, 'rate_limited');
});

test('signup: 5 per IP per hour, window slides', async () => {
  const { Client, clock } = setup();
  const c = new Client('203.0.113.50');
  const codes = [];
  for (let i = 0; i < 6; i++) codes.push((await c.post('/api/auth/signup', { username: '!', password: 'x' })).status);
  assert.deepEqual(codes, [400, 400, 400, 400, 400, 429]);
  clock.t += 61 * 60 * 1000;
  assert.equal((await c.post('/api/auth/signup', { username: '!', password: 'x' })).status, 400);
});

test('checkD1: blocked attempts are not recorded; retry-after counts down', async () => {
  const { db } = setup();
  const t = 1_000_000;
  for (let i = 0; i < 5; i++) assert.equal((await checkD1(db, [['signupIp', 'k']], t + i))[0].allowed, true);
  const [b] = await checkD1(db, [['signupIp', 'k']], t + 10);
  assert.equal(b.allowed, false);
  assert.equal(b.retryAfterSec, Math.ceil((t + 60 * 60 * 1000 - (t + 10)) / 1000));
  assert.equal(db.sqlite.prepare("SELECT count(*) AS c FROM rate_hits WHERE k = 'signupIp:k'").get().c, 5);
});

test('recovery flow, single use, sessions revoked, legacy NULL-hash accounts', async () => {
  const { Client, db } = setup();
  const c = new Client();
  const s = await c.post('/api/auth/signup', { username: 'rita', password: PW, recovery: true });
  const code = s.data.recoveryCode;
  assert.match(code, /^[0-9A-Z]{5}(-[0-9A-Z]{5}){3}$/);
  const r = await new Client().post('/api/auth/recover', { username: 'RITA', recoveryCode: code.toLowerCase(), newPassword: 'brand new pass 1' });
  assert.equal(r.status, 200);
  assert.notEqual(r.data.recoveryCode, code);
  assert.equal((await c.get('/api/auth/me')).status, 401); // old session revoked
  assert.equal((await new Client().post('/api/auth/recover', { username: 'rita', recoveryCode: code, newPassword: 'whatever pass 1' })).status, 401);
  assert.equal((await new Client().post('/api/auth/login', { username: 'rita', password: 'brand new pass 1' })).status, 200);
  // account imported from Render: no password hash, cannot log in, but a recovery code still works
  db.sqlite.prepare("UPDATE users SET password_hash = NULL, needs_new_password = 1 WHERE username_lower = 'rita'").run();
  assert.equal((await new Client().post('/api/auth/login', { username: 'rita', password: 'brand new pass 1' })).status, 401);
  const r2 = await new Client().post('/api/auth/recover', { username: 'rita', recoveryCode: r.data.recoveryCode, newPassword: 'another pass 123' });
  assert.equal(r2.status, 200);
  assert.equal(db.sqlite.prepare("SELECT needs_new_password AS n FROM users WHERE username_lower = 'rita'").get().n, 0);
  assert.equal((await new Client().post('/api/auth/login', { username: 'rita', password: 'another pass 123' })).status, 200);
});

test('hash is upgraded to the configured iteration count on login', async () => {
  const { Client, env, db } = setup({ iterations: '1000' });
  await new Client().post('/api/auth/signup', { username: 'upg', password: PW });
  assert.match(db.sqlite.prepare("SELECT password_hash h FROM users WHERE username_lower = 'upg'").get().h, /^pbkdf2-sha256\$1000\$/);
  env.PBKDF2_ITERATIONS = '2000';
  assert.equal((await new Client().post('/api/auth/login', { username: 'upg', password: PW })).status, 200);
  assert.match(db.sqlite.prepare("SELECT password_hash h FROM users WHERE username_lower = 'upg'").get().h, /^pbkdf2-sha256\$2000\$/);
});

test('sessions: cap of 20 per user, expiry, delete cascades', async () => {
  const { Client, clock, db } = setup();
  await new Client().post('/api/auth/signup', { username: 'many', password: PW });
  for (let i = 0; i < 24; i++) { clock.t += 1; await new Client().post('/api/auth/login', { username: 'many', password: PW }); }
  assert.equal(db.sqlite.prepare('SELECT count(*) c FROM sessions').get().c, 20);
  const c = new Client(); await c.post('/api/auth/login', { username: 'many', password: PW });
  clock.t += 31 * 24 * 3600 * 1000;
  assert.equal((await c.get('/api/auth/me')).status, 401);
  const d = new Client(); await d.post('/api/auth/login', { username: 'many', password: PW });
  await d.post('/api/sync/library', { add: [{ id: 'a', type: 'movie', name: 'A' }] });
  assert.equal((await d.post('/api/auth/delete', { password: PW })).status, 204);
  for (const t of ['users', 'sessions', 'user_library']) assert.equal(db.sqlite.prepare(`SELECT count(*) c FROM ${t}`).get().c, 0, t);
});

test('sync: LWW progress, library caps (409 leaves no partial writes), settings, isolation', async () => {
  const { Client, clock } = setup();
  const c = new Client(); await c.post('/api/auth/signup', { username: 'syncer', password: PW });
  const t0 = clock.t;
  const p = (o) => ({ mediaId: 'm', episodeId: null, positionMs: 10, durationMs: 100, updatedAt: t0, ...o });
  assert.equal((await c.post('/api/sync/progress', { items: [p({})] })).data.accepted, 1);
  assert.equal((await c.post('/api/sync/progress', { items: [p({ positionMs: 50, updatedAt: t0 - 5 })] })).data.accepted, 0);
  assert.equal((await c.post('/api/sync/progress', { items: [p({ positionMs: 50, updatedAt: t0 + 5, watched: true })] })).data.accepted, 1);
  const st = (await c.get('/api/sync/state')).data;
  assert.deepEqual(st.progress, [{ mediaId: 'm', episodeId: null, positionMs: 50, durationMs: 100, watched: true, dismissed: false, updatedAt: t0 + 5 }]);
  // library cap
  const fill = Array.from({ length: 5000 }, (_, i) => ({ id: `i${i}`, type: 'movie', name: 'n' }));
  assert.equal((await c.post('/api/sync/library', { add: fill })).data.count, 5000);
  const over = await c.post('/api/sync/library', { add: [{ id: 'extra', type: 'movie', name: 'n' }], remove: ['i0'] });
  assert.equal(over.status, 200); // net 5000: one out, one in
  const over2 = await c.post('/api/sync/library', { add: [{ id: 'extra2', type: 'movie', name: 'n' }] });
  assert.deepEqual([over2.status, over2.data.error], [409, 'too_many_items']);
  assert.equal((await c.get('/api/sync/state')).data.library.length, 5000);
  const other = new Client(); await other.post('/api/auth/signup', { username: 'other', password: PW });
  assert.equal((await other.get('/api/sync/state')).data.library.length, 0);
  assert.equal((await c.put('/api/sync/settings', { settings: { a: 1 } })).status, 200);
  assert.deepEqual((await c.get('/api/sync/state')).data.settings, { a: 1 });
});

test('sync is limited to 60 requests/minute per user', async () => {
  const { Client } = setup();
  const c = new Client(); await c.post('/api/auth/signup', { username: 'busy', password: PW });
  const codes = [];
  for (let i = 0; i < 62; i++) codes.push((await c.get('/api/sync/state')).status);
  assert.equal(codes.filter((x) => x === 200).length, 60);
  assert.deepEqual(codes.slice(60), [429, 429]);
});
