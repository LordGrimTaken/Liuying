import test from 'node:test';
import assert from 'node:assert/strict';

// minimal browser stand-ins so router.js can run under node
const listeners = {};
let backs = 0;
globalThis.location = { hash: '' };
globalThis.history = { length: 3, back() { backs++; }, replaceState(_s, _t, h) { location.hash = h; } };
globalThis.addEventListener = (n, f) => { listeners[n] = f; };
globalThis.window = { scrollY: 0, scrollTo() {} }; globalThis.scrollTo = () => {};
globalThis.document = { body: { dataset: {} }, getElementById: () => null };
const R = await import('../router.js');

test('goBack stays in the app: no in-app navigation yet falls back to the given link', async () => {
  R.route('/', () => ({}), 'today');
  R.route('/title/:type/:id', () => ({}), 'title');
  await R.start();
  assert.equal(R.canGoBack(), false);
  assert.equal(R.goBack('#/'), false);
  assert.equal(backs, 0, 'must not leave the site via history.back()');
});

test('goBack uses history.back() after an in-app navigation and honours the predicate', async () => {
  location.hash = '#/title/movie/x';
  await listeners.hashchange();
  assert.equal(R.canGoBack(), true);
  assert.equal(R.previousRoute().name, 'today');
  assert.equal(R.goBack('#/', (p) => p.name === 'streams'), false, 'predicate mismatch falls back');
  assert.equal(backs, 0);
  assert.equal(R.goBack('#/'), true);
  assert.equal(backs, 1);
});
