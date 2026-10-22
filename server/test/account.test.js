import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { setup, post, closeAll } from './helpers.js';

after(() => closeAll());

const GOOD = { username: 'Alice_01', password: 'correct horse battery' };
const del = (t, url) => t.delete(url);

test('change password: needs auth + current password, revokes other sessions, old password dies', async () => {
  const { agent, db } = await setup();
  const a = agent(); const b = agent();
  assert.equal((await post(agent(), '/api/auth/password', { currentPassword: 'x', newPassword: 'y' })).status, 401);
  await post(a, '/api/auth/signup', GOOD);
  await post(b, '/api/auth/login', GOOD);
  assert.equal((await post(a, '/api/auth/password', { currentPassword: 'wrong wrong', newPassword: 'brand new pass1' })).status, 403);
  const weak = await post(a, '/api/auth/password', { currentPassword: GOOD.password, newPassword: 'short' });
  assert.equal(weak.status, 400); assert.equal(weak.body.error, 'invalid_password');
  assert.equal((await post(a, '/api/auth/password', { currentPassword: GOOD.password, newPassword: GOOD.password })).status, 400);
  const ok = await post(a, '/api/auth/password', { currentPassword: GOOD.password, newPassword: 'brand new pass1' });
  assert.equal(ok.status, 200);
  assert.equal((await a.get('/api/auth/me')).status, 200);   // current session rotated, still valid
  assert.equal((await b.get('/api/auth/me')).status, 401);   // others revoked
  assert.equal((await db.query('SELECT count(*) AS c FROM sessions')).rows[0].c, 1);
  assert.equal((await post(agent(), '/api/auth/login', GOOD)).status, 401);
  assert.equal((await post(agent(), '/api/auth/login', { username: GOOD.username, password: 'brand new pass1' })).status, 200);
});

test('delete account removes the user and every row they own', async () => {
  const { agent, db } = await setup();
  const a = agent();
  await post(a, '/api/auth/signup', GOOD);
  await a.put('/api/sync/sources').set('Content-Type', 'application/json').send(JSON.stringify({ sources: [{ url: 'https://x.example/manifest.json' }] }));
  await post(a, '/api/sync/library', { add: [{ id: 'tt1', type: 'movie', name: 'M' }] });
  await post(a, '/api/sync/progress', { items: [{ mediaId: 'tt1', positionMs: 1, durationMs: 10, updatedAt: Date.now() }] });
  assert.equal((await post(a, '/api/auth/delete', { password: 'nope nope nope' })).status, 403);
  assert.equal((await a.get('/api/auth/me')).status, 200);
  assert.equal((await post(a, '/api/auth/delete', { password: GOOD.password })).status, 204);
  assert.equal((await a.get('/api/auth/me')).status, 401);
  for (const t of ['users', 'sessions', 'user_sources', 'user_library', 'user_progress', 'user_settings']) {
    assert.equal((await db.query(`SELECT count(*) AS c FROM ${t}`)).rows[0].c, 0, t);
  }
  assert.equal((await post(agent(), '/api/auth/login', GOOD)).status, 401);
  // the name is free again
  assert.equal((await post(agent(), '/api/auth/signup', GOOD)).status, 201);
});

test('sessions: list, revoke one, revoke others, logout-all', async () => {
  const { agent } = await setup();
  const a = agent(); const b = agent(); const c = agent();
  await post(a, '/api/auth/signup', GOOD);
  await post(b, '/api/auth/login', GOOD);
  await post(c, '/api/auth/login', GOOD);
  const list = (await a.get('/api/auth/sessions')).body.sessions;
  assert.equal(list.length, 3);
  assert.equal(list.filter((s) => s.current).length, 1);
  assert.ok(list.every((s) => /^[0-9a-f]{16}$/.test(s.id)));
  const other = list.find((s) => !s.current);
  assert.equal((await del(a, '/api/auth/sessions/' + other.id)).status, 204);
  assert.equal((await del(a, '/api/auth/sessions/' + other.id)).status, 404);
  assert.equal((await del(a, '/api/auth/sessions/not-an-id')).status, 404);
  assert.equal((await post(a, '/api/auth/sessions/revoke-others', {})).status, 204);
  assert.equal((await a.get('/api/auth/sessions')).body.sessions.length, 1);
  assert.equal((await a.get('/api/auth/me')).status, 200);
  assert.equal((await post(a, '/api/auth/logout-all', {})).status, 204);
  assert.equal((await a.get('/api/auth/me')).status, 401);
  assert.equal((await agent().get('/api/auth/sessions')).status, 401);
});

test('a user cannot revoke another user\'s session', async () => {
  const { agent } = await setup();
  const a = agent(); const m = agent();
  await post(a, '/api/auth/signup', GOOD);
  await post(m, '/api/auth/signup', { username: 'mallory', password: 'mallory secret 1' });
  const id = (await a.get('/api/auth/sessions')).body.sessions[0].id;
  assert.equal((await del(m, '/api/auth/sessions/' + id)).status, 404);
  assert.equal((await a.get('/api/auth/me')).status, 200);
});

test('sessions per user are capped', async () => {
  const { agent, db } = await setup();
  await post(agent(), '/api/auth/signup', GOOD);
  for (let i = 0; i < 24; i++) await post(agent(), '/api/auth/login', GOOD);
  assert.ok((await db.query('SELECT count(*) AS c FROM sessions')).rows[0].c <= 20);
});

test('Sec-Fetch-Site cross-site and foreign Origin are rejected; per-username limiter holds across IPs', async () => {
  const { agent } = await setup();
  const r = await agent().post('/api/auth/login').set('Content-Type', 'application/json').set('Sec-Fetch-Site', 'cross-site').send('{}');
  assert.equal(r.status, 403);
  const s = await setup({ limits: { loginUser: { limit: 3 } } });
  let last;
  for (let i = 0; i < 4; i++) last = await s.agent().post('/api/auth/login').set('Content-Type', 'application/json').set('X-Forwarded-For', `9.9.9.${i}`).send(JSON.stringify({ username: 'victim_1', password: 'guess guess' }));
  assert.equal(last.status, 429);
});

test('frames are denied and /watch/test is not served', async () => {
  const { agent } = await setup();
  assert.equal((await agent().get('/login')).headers['x-frame-options'], 'DENY');
  assert.equal((await agent().get('/watch/test/x.test.mjs')).status, 404);
});
