import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { setup, closeAll } from './helpers.js';

after(() => closeAll());

test('GET /api/auth/me?soft=1 answers 200 {user:null} when signed out (no 401 console noise)', async () => {
  const { agent } = await setup();
  const a = agent();
  const r = await a.get('/api/auth/me?soft=1');
  assert.equal(r.status, 200);
  assert.equal(r.body.user, null);
  assert.equal((await a.get('/api/auth/me')).status, 401, 'plain /me keeps its documented 401');
});

test('unknown pages: branded HTML for browsers, plain text for everything else', async () => {
  const { agent } = await setup();
  const a = agent();
  const html = await a.get('/nope-page').set('Accept', 'text/html,application/xhtml+xml');
  assert.equal(html.status, 404);
  assert.match(html.headers['content-type'], /html/);
  assert.match(html.text, /Page not found/);
  const plain = await a.get('/nope-page');
  assert.equal(plain.status, 404);
  assert.match(plain.headers['content-type'], /text\/plain/);
  const asset = await a.get('/missing.js').set('Accept', 'text/html');
  assert.match(asset.headers['content-type'], /text\/plain/);
});
