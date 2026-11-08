// The generated _headers and the paused web player.
//
// The web player is not shipped for now: /watch redirects to the Android download, no /watch assets or
// CSP rules are emitted, and 'unsafe-eval' must appear nowhere in the header file.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { CSP_BASE, cspString } from '../src/http.js';
import worker from '../src/worker.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
execFileSync(process.execPath, ['scripts/build-assets.mjs'], { cwd: root, stdio: 'pipe' });
const text = fs.readFileSync(path.join(root, 'dist/_headers'), 'utf8');
const blocks = Object.fromEntries(text.trim().split(/\n\s*\n/).map((b) => { const [p, ...l] = b.split('\n'); return [p.trim(), l.map((x) => x.trim())]; }));
const header = (p, name) => (blocks[p].find((l) => l.toLowerCase().startsWith(name.toLowerCase() + ':')) || '').slice(name.length + 1).trim();

test('the site keeps the strict base CSP and ships no /watch rules', () => {
  assert.equal(header('/*', 'Content-Security-Policy'), cspString(CSP_BASE));
  assert.deepEqual(Object.keys(blocks).filter((p) => p.startsWith('/watch')), []);
});

test("'unsafe-eval' appears in no rule at all", () => {
  for (const [p, lines] of Object.entries(blocks)) {
    assert.ok(!lines.some((l) => /unsafe-eval/.test(l)), p);
  }
});

test('the web player is not published', () => {
  assert.ok(!fs.existsSync(path.join(root, 'dist/watch')), 'dist must not contain watch/');
});

test('the APK download rules are still emitted', () => {
  const latest = JSON.parse(fs.readFileSync(path.join(root, '..', 'site/downloads/latest.json'), 'utf8'));
  const apk = path.basename(new URL(latest.apkUrl).pathname);
  assert.match(header('/downloads/latest.json', 'Content-Type'), /application\/json/);
  assert.match(header(`/downloads/${apk}`, 'Content-Type'), /android\.package-archive/);
  assert.match(header(`/downloads/${apk}`, 'Content-Disposition'), /attachment/);
});

test('/watch redirects to the Android download', async () => {
  for (const p of ['/watch', '/watch/', '/watch/play/movie/tt1', '/watch/player/player.js']) {
    const res = await worker.fetch(new Request('https://www.liuyingapp.com' + p), {}, {});
    assert.equal(res.status, 302, p);
    assert.equal(res.headers.get('location'), 'https://www.liuyingapp.com/download', p);
  }
});
