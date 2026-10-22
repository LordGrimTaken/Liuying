import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { titleHint } from '../progress.js';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const sw = fs.readFileSync(path.join(root, 'sw.js'), 'utf8');

function walk(dir, base = '') {
  return fs.readdirSync(path.join(dir, base), { withFileTypes: true }).flatMap((e) => {
    const rel = path.posix.join(base, e.name);
    if (e.isDirectory()) return e.name === 'test' ? [] : walk(dir, rel);
    return [rel];
  });
}

test('service worker precache list matches the shell files on disk', () => {
  const block = sw.slice(sw.indexOf('const SHELL = ['), sw.indexOf('];', sw.indexOf('const SHELL = [')));
  const listed = new Set([...block.matchAll(/'(\/[^']+)'/g)].map((m) => m[1]));
  listed.add('/watch/offline.html'); // referenced through the OFFLINE constant
  const disk = new Set(walk(root).filter((f) => f !== 'sw.js' && f !== 'index.html' && f !== 'icons/icon-maskable.svg').map((f) => `/watch/${f}`));
  disk.add('/watch/'); disk.add('/vendor/hls.min.js');
  const hasHls = /const HLS = '\/vendor\/hls\.min\.js'/.test(sw) && /^\s+HLS,$/m.test(block);
  assert.ok(hasHls, 'hls vendor file is precached');
  listed.add('/vendor/hls.min.js');
  assert.deepEqual([...disk].filter((f) => !listed.has(f)), [], 'files missing from SHELL (and VERSION must be bumped)');
  assert.deepEqual([...listed].filter((f) => !disk.has(f)), [], 'SHELL lists files that do not exist');
  assert.match(sw, /const VERSION = '[^']+'/);
});

test('service worker never touches API, cross-origin or media requests', () => {
  assert.match(sw, /url\.origin !== self\.location\.origin\) return false/);
  assert.match(sw, /headers\.has\('range'\)/);
  assert.ok(!/\/api\//.test(sw.replace(/\/\*[\s\S]*?\*\//, '')), 'api paths are excluded by scope, not cached');
});

test('manifest is valid and in scope', () => {
  const m = JSON.parse(fs.readFileSync(path.join(root, 'manifest.webmanifest'), 'utf8'));
  assert.equal(m.name, 'Liuying');
  assert.ok(m.start_url.startsWith(m.scope));
  for (const i of m.icons) assert.ok(fs.existsSync(path.join(root, i.src.replace('/watch/', ''))), i.src);
  assert.ok(m.icons.some((i) => i.purpose === 'maskable'));
});

test('titleHint falls back to meta, shelf, then any episode progress record', () => {
  const progress = [
    { mediaId: 's1', episodeId: 's1:1:1', updatedAt: 1, name: 'Old', poster: 'p-old' },
    { mediaId: 's1', episodeId: 's1:1:2', updatedAt: 5, name: 'Flowing Shadows', type: 'series' },
    { mediaId: 'other', episodeId: null, updatedAt: 9, name: 'Nope' },
  ];
  assert.deepEqual(titleHint('s1', { progress }), { name: 'Flowing Shadows', poster: 'p-old', type: 'series' });
  assert.equal(titleHint('s1', { progress, library: [{ id: 's1', name: 'Shelf Name', poster: 'sp', type: 'series' }] }).name, 'Shelf Name');
  assert.equal(titleHint('s1', { progress, meta: { name: 'Meta' } }).name, 'Meta');
  assert.deepEqual(titleHint('zzz', { progress }), { name: '', poster: '', type: '' });
});

test('shell content hash is recorded in sw.js (change a shell file => bump VERSION and the hash)', () => {
  const files = walk(root).filter((f) => f !== 'sw.js').sort();
  const hash = crypto.createHash('sha256');
  for (const f of files) hash.update(f).update(fs.readFileSync(path.join(root, f)));
  hash.update(fs.readFileSync(path.join(root, '..', 'vendor', 'hls.min.js')));
  const now = hash.digest('hex').slice(0, 12);
  const recorded = /shell-hash: ([0-9a-f]{12})/.exec(sw)?.[1];
  assert.equal(recorded, now, `Shell files changed. Bump VERSION in sw.js and set "shell-hash: ${now}".`);
});
