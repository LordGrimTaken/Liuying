import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { setup, post, SITE, closeAll } from './helpers.js';
import { hashPassword, verifyPassword, needsRehash, DEFAULT_PARAMS } from '../src/auth.js';

after(() => closeAll());

const GOOD = { username: 'Alice_01', password: 'correct horse battery' };

test('signup/me/logout/login flow', async () => {
  const { agent, db } = await setup();
  const a = agent();
  assert.equal((await a.get('/api/auth/me')).status, 401);
  assert.equal((await a.get('/api/auth/me')).body.error, 'unauthenticated');

  const s = await post(a, '/api/auth/signup', GOOD);
  assert.equal(s.status, 201);
  assert.deepEqual(s.body, { user: { username: 'Alice_01' } });

  const me = await a.get('/api/auth/me');
  assert.equal(me.status, 200);
  assert.deepEqual(me.body, { user: { username: 'Alice_01' } });

  const lo = await post(a, '/api/auth/logout', {});
  assert.equal(lo.status, 204);
  assert.equal((await a.get('/api/auth/me')).status, 401);
  assert.equal((await db.query('SELECT count(*) AS c FROM sessions')).rows[0].c, 0);

  // login with different case, display form preserved
  const li = await post(a, '/api/auth/login', { username: 'alice_01', password: GOOD.password });
  assert.equal(li.status, 200);
  assert.deepEqual(li.body, { user: { username: 'Alice_01' } });
  assert.equal((await a.get('/api/auth/me')).status, 200);
});

test('session token is stored hashed only; login rotates session', async () => {
  const { agent, db } = await setup();
  const a = agent();
  const s = await post(a, '/api/auth/signup', GOOD);
  const tok = /liuying_sid=([^;]+)/.exec(s.headers['set-cookie'][0])[1];
  const rows = (await db.query('SELECT token_hash FROM sessions')).rows;
  assert.equal(rows.length, 1);
  assert.notEqual(rows[0].token_hash, tok);
  assert.match(rows[0].token_hash, /^[0-9a-f]{64}$/);
  await post(a, '/api/auth/login', GOOD);
  const rows2 = (await db.query('SELECT token_hash FROM sessions')).rows;
  assert.equal(rows2.length, 1);
  assert.notEqual(rows2[0].token_hash, rows[0].token_hash);
});

test('usernames are case-insensitively unique', async () => {
  const { agent } = await setup();
  assert.equal((await post(agent(), '/api/auth/signup', GOOD)).status, 201);
  const r = await post(agent(), '/api/auth/signup', { username: 'ALICE_01', password: 'another good pass' });
  assert.equal(r.status, 409);
  assert.equal(r.body.error, 'username_taken');
  assert.ok(r.body.message);
});

test('invalid inputs', async () => {
  const { agent } = await setup({ limits: { signupIp: { limit: 1000 } } });
  const cases = [
    [{ username: 'a', password: 'long enough pw' }, 'invalid_username'],
    [{ username: '李', password: 'long enough pw' }, 'invalid_username'],
    [{ username: '李 雷', password: 'long enough pw' }, 'invalid_username'],
    [{ username: 'a\u200bb', password: 'long enough pw' }, 'invalid_username'],
    [{ username: 'ab\u0007', password: 'long enough pw' }, 'invalid_username'],
    [{ username: 'a\nb', password: 'long enough pw' }, 'invalid_username'],
    [{ username: 'a-b', password: 'long enough pw' }, 'invalid_username'],
    [{ username: '李'.repeat(25), password: 'long enough pw' }, 'invalid_username'],
    [{ username: 'a'.repeat(25), password: 'long enough pw' }, 'invalid_username'],
    [{ username: 'bad name', password: 'long enough pw' }, 'invalid_username'],
    [{ username: 123, password: 'long enough pw' }, 'invalid_username'],
    [{ password: 'long enough pw' }, 'invalid_username'],
    [{ username: 'gooduser', password: 'short' }, 'invalid_password'],
    [{ username: 'gooduser', password: 'x'.repeat(129) }, 'invalid_password'],
    [{ username: 'gooduser' }, 'invalid_password'],
    [{ username: 'gooduser1', password: 'GoodUser1' }, 'invalid_password'],
    [{ username: 'gooduser', password: 'Password123' }, 'invalid_password'],
    [{ username: 'gooduser', password: '12345678' }, 'invalid_password'],
  ];
  for (const [body, code] of cases) {
    const r = await post(agent(), '/api/auth/signup', body);
    assert.equal(r.status, 400, JSON.stringify(body));
    assert.equal(r.body.error, code, JSON.stringify(body));
  }
  // password length boundaries ok
  assert.equal((await post(agent(), '/api/auth/signup', { username: 'abc', password: 'x'.repeat(128) })).status, 201);
  assert.equal((await post(agent(), '/api/auth/signup', { username: 'b'.repeat(24), password: 'zzzzzzzz' })).status, 201);
  // malformed / non-object / oversized bodies
  const bad = await agent().post('/api/auth/signup').set('Content-Type', 'application/json').send('{nope');
  assert.equal(bad.status, 400); assert.equal(bad.body.error, 'bad_request');
  assert.equal((await post(agent(), '/api/auth/signup', [1])).body.error, 'bad_request');
  const big = await post(agent(), '/api/auth/signup', { username: 'abc', password: 'x'.repeat(5000) });
  assert.equal(big.status, 400); assert.equal(big.body.error, 'bad_request');
  assert.equal((await post(agent(), '/api/auth/login', { username: 5, password: 'x' })).body.error, 'bad_request');
});

test('wrong password and unknown user give identical responses', async () => {
  const { agent } = await setup();
  await post(agent(), '/api/auth/signup', GOOD);
  const wrong = await post(agent(), '/api/auth/login', { username: GOOD.username, password: 'not the password' });
  const unknown = await post(agent(), '/api/auth/login', { username: 'nobody_here', password: 'not the password' });
  const weird = await post(agent(), '/api/auth/login', { username: '!!', password: 'x' });
  for (const r of [wrong, unknown, weird]) assert.equal(r.status, 401);
  assert.deepEqual(wrong.body, unknown.body);
  assert.deepEqual(wrong.body, weird.body);
  assert.equal(wrong.body.error, 'invalid_credentials');
  assert.equal(wrong.headers['set-cookie'], undefined);
});

test('cookie flags (dev and production)', async () => {
  const dev = await setup();
  const r = await post(dev.agent(), '/api/auth/signup', GOOD);
  const c = r.headers['set-cookie'][0];
  assert.match(c, /^liuying_sid=/);
  assert.match(c, /HttpOnly/i);
  assert.match(c, /SameSite=Lax/i);
  assert.match(c, /Path=\//);
  assert.match(c, /Max-Age=2592000/);
  assert.doesNotMatch(c, /Secure/i);

  const prod = await setup({ production: true });
  const r2 = await prod.request().post('/api/auth/signup').set('X-Forwarded-Proto', 'https')
    .set('Content-Type', 'application/json').send(JSON.stringify(GOOD));
  assert.equal(r2.status, 201);
  assert.match(r2.headers['set-cookie'][0], /Secure/i);
  assert.match(r2.headers['strict-transport-security'], /max-age/);

  const lo = await dev.agent().post('/api/auth/logout').set('Content-Type', 'application/json').send('{}');
  assert.match(lo.headers['set-cookie'][0], /liuying_sid=;/);
});

test('content-type and origin checks', async () => {
  const { request } = await setup();
  const txt = await request().post('/api/auth/signup').set('Content-Type', 'text/plain').send(JSON.stringify(GOOD));
  assert.equal(txt.status, 415); assert.equal(txt.body.error, 'unsupported_media_type');
  const none = await request().post('/api/auth/login').send();
  assert.equal(none.status, 415);
  const form = await request().post('/api/auth/login').type('form').send(GOOD);
  assert.equal(form.status, 415);

  const evil = await request().post('/api/auth/signup').set('Origin', 'https://evil.example')
    .set('Content-Type', 'application/json').send(JSON.stringify(GOOD));
  assert.equal(evil.status, 403);
  const t = request().post('/api/auth/signup');
  const host = t.url.replace(/^http:\/\//, '').split('/')[0];
  const ok = await request().post('/api/auth/signup').set('Host', host).set('Origin', `http://${host}`)
    .set('Content-Type', 'application/json; charset=utf-8').send(JSON.stringify(GOOD));
  assert.equal(ok.status, 201);
  const nullOrigin = await request().post('/api/auth/login').set('Origin', 'null')
    .set('Content-Type', 'application/json').send(JSON.stringify(GOOD));
  assert.equal(nullOrigin.status, 403);
});

test('login rate limit per ip+username and per ip', async () => {
  const { agent, clock } = await setup();
  await post(agent(), '/api/auth/signup', GOOD);
  for (let i = 0; i < 10; i++) {
    assert.equal((await post(agent(), '/api/auth/login', { username: GOOD.username, password: 'wrong wrong' })).status, 401);
  }
  const r = await post(agent(), '/api/auth/login', GOOD); // even the right password is blocked
  assert.equal(r.status, 429);
  assert.equal(r.body.error, 'rate_limited');
  assert.ok(Number(r.headers['retry-after']) > 0);
  // other username from same IP still allowed
  assert.equal((await post(agent(), '/api/auth/login', { username: 'someone_else', password: 'wrong wrong' })).status, 401);
  // window expires
  clock.t += 15 * 60 * 1000 + 1000;
  assert.equal((await post(agent(), '/api/auth/login', GOOD)).status, 200);

  // per-IP: 30 total across usernames
  const s2 = await setup();
  let last;
  for (let i = 0; i < 31; i++) last = await post(s2.agent(), '/api/auth/login', { username: `user_${i}`, password: 'wrong wrong' });
  assert.equal(last.status, 429);
});

test('signup rate limit: 5 per hour per ip', async () => {
  const { agent, clock } = await setup();
  for (let i = 0; i < 5; i++) {
    assert.equal((await post(agent(), '/api/auth/signup', { username: `user_${i}`, password: 'a fine password' })).status, 201);
  }
  const r = await post(agent(), '/api/auth/signup', { username: 'user_9', password: 'a fine password' });
  assert.equal(r.status, 429);
  assert.equal(r.body.error, 'rate_limited');
  assert.ok(Number(r.headers['retry-after']) > 0);
  clock.t += 61 * 60 * 1000;
  assert.equal((await post(agent(), '/api/auth/signup', { username: 'user_9', password: 'a fine password' })).status, 201);
});

test('sessions expire and are purged', async () => {
  const { agent, clock, db } = await setup();
  const a = agent();
  await post(a, '/api/auth/signup', GOOD);
  assert.equal((await a.get('/api/auth/me')).status, 200);
  clock.t += 29 * 24 * 3600 * 1000;
  assert.equal((await a.get('/api/auth/me')).status, 200);
  clock.t += 2 * 24 * 3600 * 1000;
  assert.equal((await a.get('/api/auth/me')).status, 401);
  await new Promise((r) => setTimeout(r, 50));
  assert.equal((await db.query('SELECT count(*) AS c FROM sessions')).rows[0].c, 0);
});

test('static pages, 404s, health, headers', async () => {
  const { request } = await setup();
  const home = await request().get('/');
  assert.equal(home.status, 200); assert.match(home.text, /home/);
  assert.match(home.headers['content-security-policy'], /script-src 'self'/);
  assert.match(home.headers['content-security-policy'], /img-src 'self' data:/);
  assert.equal((await request().get('/login')).status, 200);
  assert.equal((await request().get('/signup')).status, 404); // file missing -> graceful 404
  assert.equal((await request().get('/account')).status, 404);
  assert.equal((await request().get('/nope')).status, 404);
  const api404 = await request().get('/api/nope');
  assert.equal(api404.status, 404); assert.equal(api404.body.error, 'not_found');
  const h = await request().get('/health');
  assert.equal(h.status, 200); assert.equal(h.text, 'ok');
  assert.equal(home.headers['x-powered-by'], undefined);
  void SITE;
});

test('db failure yields generic 500 with no stack and no password in logs', async () => {
  const logs = [];
  const { agent, db } = await setup({ logger: { info() {}, error: (o) => logs.push(JSON.stringify(o)) } });
  const orig = db.query;
  db.query = () => { throw new Error('boom'); };
  const r = await post(agent(), '/api/auth/signup', { username: 'abcdef', password: 'my secret pass' });
  db.query = orig;
  assert.equal(r.status, 500);
  assert.equal(r.body.error, 'internal_error');
  assert.doesNotMatch(JSON.stringify(r.body), /boom|at /);
  assert.ok(logs.length > 0);
  assert.ok(!logs.join('').includes('my secret pass'));
});

test('hash format, verification and upgrade detection', async () => {
  const h = await hashPassword('hunter2hunter2', { ...DEFAULT_PARAMS });
  assert.match(h, /^scrypt\$32768\$8\$1\$/);
  assert.equal(h.split('$')[5].length, 88); // 64 bytes base64
  assert.ok(await verifyPassword('hunter2hunter2', h));
  assert.ok(!(await verifyPassword('hunter3hunter3', h)));
  assert.ok(!(await verifyPassword('x', 'garbage')));
  assert.ok(!needsRehash(h));
  assert.ok(needsRehash(h, { ...DEFAULT_PARAMS, N: 2 ** 16 }));
});

test('login upgrades hash when params change', async () => {
  const s = await setup();
  await post(s.agent(), '/api/auth/signup', GOOD);
  const before = (await s.db.query('SELECT password_hash FROM users')).rows[0].password_hash;
  assert.match(before, /^scrypt\$1024\$/);
  const { createApp } = await import('../src/app.js');
  const app2 = createApp({ db: s.db, siteDir: SITE, logger: { info() {}, error() {} }, hashParams: { N: 2 ** 11, r: 8, p: 1, keylen: 64 } });
  const { default: request } = await import('supertest');
  const r = await post(request(app2), '/api/auth/login', GOOD);
  assert.equal(r.status, 200);
  const after = (await s.db.query('SELECT password_hash FROM users')).rows[0].password_hash;
  assert.match(after, /^scrypt\$2048\$/);
});

test('CJK usernames: signup, login, length counted in code points', async () => {
  const { agent } = await setup();
  const pw = 'correct horse battery';
  const s = await post(agent(), '/api/auth/signup', { username: '李雷', password: pw });
  assert.equal(s.status, 201);
  assert.deepEqual(s.body, { user: { username: '李雷' } });
  const li = await post(agent(), '/api/auth/login', { username: '李雷', password: pw });
  assert.equal(li.status, 200);
  assert.equal((await post(agent(), '/api/auth/login', { username: '李雷', password: 'wrong password!' })).status, 401);
  assert.equal((await post(agent(), '/api/auth/signup', { username: '𠀀'.repeat(24), password: pw })).status, 201); // astral, 24 code points
  assert.equal((await post(agent(), '/api/auth/signup', { username: '𠀀'.repeat(25), password: pw })).status, 400);
  assert.equal((await post(agent(), '/api/auth/signup', { username: 'Émile_é', password: pw })).status, 201);
  assert.equal((await post(agent(), '/api/auth/signup', { username: '한국어사용자', password: pw })).status, 201);
});

test('login and uniqueness use NFKC + case folding; display form kept', async () => {
  const { agent } = await setup({ limits: { signupIp: { limit: 100 } } });
  const pw = 'correct horse battery';
  let n = 0;
  const su = (username, password) => agent().post('/api/auth/signup').set('Content-Type', 'application/json').set('X-Forwarded-For', `7.7.7.${++n}`).send(JSON.stringify({ username, password }));
  assert.equal((await su('Émile', pw)).status, 201); // precomposed
  const dec = 'E\u0301mile'; // decomposed
  const li = await post(agent(), '/api/auth/login', { username: dec, password: pw });
  assert.equal(li.status, 200);
  assert.equal(li.body.user.username, 'Émile');
  assert.equal((await post(agent(), '/api/auth/login', { username: 'éMILE', password: pw })).status, 200);
  const dup = await su('e\u0301mile', 'another good pass');
  assert.equal(dup.status, 409);
  assert.equal(dup.body.error, 'username_taken');
  // fullwidth Latin folds to ASCII under NFKC
  assert.equal((await su('Ｂｏｂ_1', pw)).body.user.username, 'Bob_1');
  assert.equal((await su('bob_1', pw)).status, 409);
  // halfwidth katakana vs fullwidth
  assert.equal((await su('ｶﾀｶﾅ', pw)).status, 201);
  assert.equal((await su('カタカナ', pw)).status, 409);
});

test('migration backfills username_lower idempotently and keeps ASCII accounts working', async () => {
  const { agent, db } = await setup();
  const pw = 'correct horse battery';
  await post(agent(), '/api/auth/signup', { username: 'Alice_01', password: pw });
  await db.query("UPDATE users SET username = $1, username_lower = $2 WHERE username = 'Alice_01'", ['Ｚｏｅ', 'ｚｏｅ']);
  const { backfillUsernameKeys } = await import('../src/db.js');
  await backfillUsernameKeys(db); await backfillUsernameKeys(db);
  assert.equal((await db.query('SELECT username_lower FROM users')).rows[0].username_lower, 'zoe');
});

test('rate limits still apply to Unicode usernames (equivalent forms share a bucket)', async () => {
  const { agent } = await setup();
  let last;
  for (let i = 0; i < 12; i++) {
    const name = i % 2 ? 'E\u0301mile' : 'Émile';
    last = await agent().post('/api/auth/login').set('Content-Type', 'application/json').set('X-Forwarded-For', `8.8.8.${i}`)
      .send(JSON.stringify({ username: name, password: 'guess guess' }));
    if (last.status === 429) break;
  }
  assert.equal(last.status, 429);
});
