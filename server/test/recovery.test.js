import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { setup, post, closeAll } from './helpers.js';
import { newRecoveryCode, normalizeRecoveryCode, hashRecoveryCode, recoveryMatches } from '../src/auth.js';
import { createMemoryDb, migrate } from '../src/db.js';

after(() => closeAll());

const GOOD = { username: 'Rita_01', password: 'correct horse battery' };

test('recovery code helpers: format, normalisation, hashing', () => {
  const c = newRecoveryCode();
  assert.match(c, /^[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}$/);
  assert.notEqual(newRecoveryCode(), c);
  const n = normalizeRecoveryCode(c);
  assert.equal(n.length, 20);
  assert.equal(normalizeRecoveryCode(c.toLowerCase().replace(/-/g, ' ')), n);
  assert.equal(normalizeRecoveryCode('0'.repeat(19) + 'O'), '0'.repeat(20)); // O folds to 0
  assert.equal(normalizeRecoveryCode('short'), '');
  assert.equal(normalizeRecoveryCode(12345), '');
  assert.equal(normalizeRecoveryCode('U'.repeat(20)), ''); // U is not in the alphabet
  assert.ok(recoveryMatches(n, hashRecoveryCode(n)));
  assert.ok(!recoveryMatches(n, hashRecoveryCode(normalizeRecoveryCode(newRecoveryCode()))));
  assert.ok(!recoveryMatches(n, null));
});

test('migration: existing databases upgrade in place, ALTER statements are idempotent', async () => {
  // pg-mem cannot re-run CREATE TABLE IF NOT EXISTS with constraints, so model "a deployed DB from before this change":
  // apply only the old statements, insert a user, then replay the new ones several times (as every Render boot does).
  const { readFile } = await import('node:fs/promises');
  const sql = await readFile(new URL('../src/migrations.sql', import.meta.url), 'utf8');
  const stmts = sql.split(';').map((x) => x.trim()).filter(Boolean);
  const alters = stmts.filter((x) => /^ALTER TABLE/i.test(x));
  assert.ok(alters.length >= 2 && alters.every((x) => /ADD COLUMN IF NOT EXISTS/i.test(x)), 'every ALTER must be IF NOT EXISTS');
  const db = await createMemoryDb();
  for (const st of stmts.filter((x) => !/^ALTER TABLE/i.test(x))) await db.query(st);
  await db.query("INSERT INTO users (id, username, username_lower, password_hash, created_at) VALUES ('00000000-0000-4000-8000-000000000001','old','old','x',now())");
  for (let i = 0; i < 3; i++) for (const st of alters) await db.query(st);
  const r = await db.query('SELECT username, recovery_hash, recovery_created_at FROM users');
  assert.equal(r.rows[0].username, 'old');
  assert.equal(r.rows[0].recovery_hash, null);
  await db.query("UPDATE users SET recovery_hash = 'h'");
  assert.equal((await db.query('SELECT recovery_hash FROM users')).rows[0].recovery_hash, 'h');
  // and the normal first-boot path still works end to end
  const fresh = await createMemoryDb();
  await migrate(fresh);
  assert.equal((await fresh.query('SELECT recovery_hash FROM users')).rows.length, 0);
});

test('signup without the recovery flag behaves as before (no code)', async () => {
  const { agent } = await setup();
  const r = await post(agent(), '/api/auth/signup', GOOD);
  assert.equal(r.status, 201);
  assert.deepEqual(r.body, { user: { username: GOOD.username } });
  assert.equal((await agent().get('/api/auth/recovery')).status, 401);
});

test('signup with recovery:true returns the code once; only its hash is stored; code resets the password', async () => {
  const { agent, db } = await setup();
  const a = agent();
  const s = await post(a, '/api/auth/signup', { ...GOOD, recovery: true });
  assert.equal(s.status, 201);
  const code = s.body.recoveryCode;
  assert.match(code, /^[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}$/);
  const row = (await db.query('SELECT recovery_hash FROM users')).rows[0];
  assert.equal(row.recovery_hash, hashRecoveryCode(normalizeRecoveryCode(code)));
  assert.ok(!JSON.stringify(row).includes(normalizeRecoveryCode(code)));
  const st = await a.get('/api/auth/recovery');
  assert.equal(st.body.enabled, true);
  assert.ok(!JSON.stringify(st.body).includes(code));
  assert.deepEqual((await a.get('/api/auth/me')).body, { user: { username: GOOD.username } }); // me shape unchanged

  const other = agent();
  await post(other, '/api/auth/login', GOOD);
  const t = agent();
  // typed sloppily: lowercase, spaces instead of dashes
  const typed = code.toLowerCase().replace(/-/g, ' ');
  const rec = await post(t, '/api/auth/recover', { username: GOOD.username.toLowerCase(), recoveryCode: typed, newPassword: 'a brand new pass 9' });
  assert.equal(rec.status, 200);
  assert.equal(rec.body.user.username, GOOD.username);
  assert.match(rec.body.recoveryCode, /^[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}$/);
  assert.notEqual(rec.body.recoveryCode, code);
  assert.equal((await t.get('/api/auth/me')).status, 200); // signed in by the reset
  assert.equal((await a.get('/api/auth/me')).status, 401); // other sessions revoked
  assert.equal((await other.get('/api/auth/me')).status, 401);
  assert.equal((await post(agent(), '/api/auth/login', GOOD)).status, 401); // old password dead
  assert.equal((await post(agent(), '/api/auth/login', { username: GOOD.username, password: 'a brand new pass 9' })).status, 200);
  // the used code is dead, the new one works
  assert.equal((await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: code, newPassword: 'yet another pass 7' })).status, 401);
  assert.equal((await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: rec.body.recoveryCode, newPassword: 'yet another pass 7' })).status, 200);
});

test('recover: wrong code, unknown user and account without a code give the same generic 401', async () => {
  const { agent } = await setup();
  await post(agent(), '/api/auth/signup', { ...GOOD, recovery: true });
  await post(agent(), '/api/auth/signup', { username: 'NoCode_1', password: GOOD.password });
  const bad = newRecoveryCode();
  const a = await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: bad, newPassword: 'whatever pass 12' });
  const b = await post(agent(), '/api/auth/recover', { username: 'Nobody_9', recoveryCode: bad, newPassword: 'whatever pass 12' });
  const c = await post(agent(), '/api/auth/recover', { username: 'NoCode_1', recoveryCode: bad, newPassword: 'whatever pass 12' });
  const d = await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: 'nonsense', newPassword: 'whatever pass 12' });
  for (const r of [a, b, c, d]) assert.equal(r.status, 401);
  assert.deepEqual(a.body, b.body); assert.deepEqual(a.body, c.body); assert.deepEqual(a.body, d.body);
  assert.equal(a.body.error, 'invalid_recovery');
  assert.equal((await post(agent(), '/api/auth/recover', { username: 5, recoveryCode: bad, newPassword: 'x' })).status, 400);
});

test('recover: a weak new password is rejected and does NOT burn the code', async () => {
  const { agent } = await setup();
  const s = await post(agent(), '/api/auth/signup', { ...GOOD, recovery: true });
  const weak = await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: s.body.recoveryCode, newPassword: 'short' });
  assert.equal(weak.status, 400); assert.equal(weak.body.error, 'invalid_password');
  assert.equal((await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: s.body.recoveryCode, newPassword: 'long enough pass 3' })).status, 200);
});

test('recover is rate limited per account and per IP', async () => {
  const { agent } = await setup();
  await post(agent(), '/api/auth/signup', { ...GOOD, recovery: true });
  const bad = newRecoveryCode();
  const codes = [];
  for (let i = 0; i < 7; i++) codes.push((await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: bad, newPassword: 'whatever pass 12' })).status);
  assert.deepEqual(codes.slice(0, 5), [401, 401, 401, 401, 401]);
  assert.equal(codes[5], 429);
  const r = await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: bad, newPassword: 'whatever pass 12' });
  assert.ok(Number(r.headers['retry-after']) > 0);

  const t2 = await setup({ limits: { recoverUser: { limit: 1000 }, recoverIp: { limit: 3 } } });
  const s = [];
  for (let i = 0; i < 5; i++) s.push((await post(t2.agent(), '/api/auth/recover', { username: `User_${i}x`, recoveryCode: bad, newPassword: 'whatever pass 12' })).status);
  assert.deepEqual(s, [401, 401, 401, 429, 429]);
});

test('regenerate: needs a session and the current password, invalidates the previous code', async () => {
  const { agent } = await setup();
  const a = agent();
  const s = await post(a, '/api/auth/signup', { ...GOOD, recovery: true });
  assert.equal((await post(agent(), '/api/auth/recovery', { password: GOOD.password })).status, 401);
  assert.equal((await post(a, '/api/auth/recovery', { password: 'wrong password!' })).status, 403);
  const n = await post(a, '/api/auth/recovery', { password: GOOD.password });
  assert.equal(n.status, 200);
  assert.notEqual(n.body.recoveryCode, s.body.recoveryCode);
  assert.equal((await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: s.body.recoveryCode, newPassword: 'long enough pass 3' })).status, 401);
  assert.equal((await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: n.body.recoveryCode, newPassword: 'long enough pass 3' })).status, 200);
});

test('regenerate works for an account created without a code; delete account removes it', async () => {
  const { agent, db } = await setup();
  const a = agent();
  await post(a, '/api/auth/signup', GOOD);
  assert.equal((await a.get('/api/auth/recovery')).body.enabled, false);
  const n = await post(a, '/api/auth/recovery', { password: GOOD.password });
  assert.equal(n.status, 200);
  assert.equal((await a.get('/api/auth/recovery')).body.enabled, true);
  assert.equal((await post(a, '/api/auth/delete', { password: GOOD.password })).status, 204);
  assert.equal((await db.query('SELECT count(*) AS c FROM users')).rows[0].c, 0);
});

test('the server never logs the recovery code', async () => {
  const lines = [];
  const { agent } = await setup({ logger: { info: (o) => lines.push(JSON.stringify(o)), error: (o) => lines.push(JSON.stringify(o)) } });
  const s = await post(agent(), '/api/auth/signup', { ...GOOD, recovery: true });
  await post(agent(), '/api/auth/recover', { username: GOOD.username, recoveryCode: s.body.recoveryCode, newPassword: 'x' });
  assert.ok(!lines.join('\n').includes(normalizeRecoveryCode(s.body.recoveryCode)));
});
