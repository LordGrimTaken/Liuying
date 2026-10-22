import test from 'node:test';
import assert from 'node:assert/strict';
import * as M from '../sync-merge.js';
import { srtToVtt, decodeSubtitleBytes, languageName, langCode, normalizeSubtitles, pickAutoSubtitle, sortSubtitles } from '../player/subtitles.js';

const p = (mediaId, episodeId, updatedAt, extra = {}) => ({ mediaId, episodeId, positionMs: 1, durationMs: 10, watched: false, dismissed: false, updatedAt, ...extra });

test('mergeProgress: newest updatedAt wins in both directions, local extras kept', () => {
  const local = [p('a', null, 100, { name: 'A' }), p('b', 'b:1:1', 300), p('c', null, 50)];
  const remote = [p('a', null, 200, { positionMs: 9 }), p('b', 'b:1:1', 100), p('d', null, 10)];
  const { merged, toPush, changed } = M.mergeProgress(local, remote);
  const by = Object.fromEntries(merged.map((r) => [r.mediaId, r]));
  assert.equal(by.a.positionMs, 9); assert.equal(by.a.name, 'A'); assert.equal(by.a.updatedAt, 200);
  assert.equal(by.b.updatedAt, 300);
  assert.ok(by.d && by.c);
  assert.deepEqual(toPush.map((r) => r.mediaId).sort(), ['b', 'c']);
  assert.ok(changed);
});

test('mergeProgress: equal timestamps keep local, no change', () => {
  const r = M.mergeProgress([p('a', null, 5, { positionMs: 2 })], [p('a', null, 5, { positionMs: 7 })]);
  assert.equal(r.merged[0].positionMs, 2); assert.equal(r.toPush.length, 0); assert.equal(r.changed, false);
});

test('mergeLibrary: union minus local removals, reports adds to push', () => {
  const local = [{ id: 'a', addedAt: 3 }, { id: 'b', addedAt: 2 }, { id: 'gone', addedAt: 1 }];
  const remote = [{ id: 'b', addedAt: 2 }, { id: 'c', addedAt: 5 }, { id: 'gone', addedAt: 1 }];
  const r = M.mergeLibrary(local, remote, new Set(['gone']));
  assert.deepEqual(r.merged.map((x) => x.id), ['c', 'a', 'b']);
  assert.deepEqual(r.toAdd.map((x) => x.id), ['a']);
  assert.deepEqual(r.toRemove, ['gone']);
});

test('mergeSources: server wins when clean; merge by url preserving order when dirty', () => {
  const local = [{ url: 'u1', enabled: true, manifest: { id: 1 } }, { url: 'u3', enabled: false }];
  const remote = [{ url: 'u2', enabled: true }, { url: 'u1', enabled: false }];
  const clean = M.mergeSources(local, remote, false);
  assert.deepEqual(clean.map((s) => [s.url, s.enabled]), [['u2', true], ['u1', false]]);
  assert.deepEqual(clean[1].manifest, { id: 1 }, 'cached manifest kept');
  const dirty = M.mergeSources(local, remote, true);
  assert.deepEqual(dirty.map((s) => s.url), ['u2', 'u1', 'u3']);
  assert.equal(dirty[1].enabled, true, 'local enabled flag preserved when dirty');
  assert.ok(M.sameSources([{ url: 'a' }], [{ url: 'a', enabled: true }]));
});

test('mergeSettings and wire helpers', () => {
  assert.deepEqual(M.mergeSettings({ a: 1, b: 2 }, { b: 3, c: 4 }, false), { a: 1, b: 3, c: 4 });
  assert.deepEqual(M.mergeSettings({ a: 1, b: 2 }, { b: 3, c: 4 }, true), { b: 2, c: 4, a: 1 });
  const w = M.progressWire({ mediaId: 'm', positionMs: 99.6, durationMs: 50, updatedAt: 5.2 });
  assert.deepEqual(w, { mediaId: 'm', episodeId: null, positionMs: 50, durationMs: 50, watched: false, dismissed: false, updatedAt: 5 });
  assert.deepEqual(M.chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]);
  assert.deepEqual([0, 1, 2, 10].map((n) => M.backoffMs(n)), [2000, 4000, 8000, 60000]);
});

test('srtToVtt converts timing, strips index, keeps simple tags', () => {
  const srt = '﻿1\r\n00:00:01,500 --> 00:00:03,000\r\nHello <i>world</i>\r\nline2\r\n\r\n2\r\n00:01:00,5 --> 00:01:02,25\r\n<font color="red">Hi</font> <script>x</script>\r\n';
  const { vtt, cues } = srtToVtt(srt);
  assert.equal(cues, 2);
  assert.ok(vtt.startsWith('WEBVTT\n'));
  assert.ok(vtt.includes('00:00:01.500 --> 00:00:03.000\nHello <i>world</i>\nline2'));
  assert.ok(vtt.includes('00:01:00.500 --> 00:01:02.250'));
  assert.ok(!vtt.includes('<script>') && !vtt.includes('<font'));
  assert.equal(srtToVtt('WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nx').cues, 1);
  assert.equal(srtToVtt('garbage').cues, 0);
  assert.ok(srtToVtt('1\n00:00:01,000 --> 00:00:02,000\na --> b').vtt.includes('a --&gt; b'));
});

test('decodeSubtitleBytes: utf-8, bom and legacy fallback', () => {
  assert.equal(decodeSubtitleBytes(new TextEncoder().encode('café')), 'café');
  assert.equal(decodeSubtitleBytes(Uint8Array.from([0x63, 0x61, 0x66, 0xe9])), 'café');
});

test('languages: names, canonical codes, ranking and auto pick', () => {
  assert.equal(languageName('eng'), 'English'); assert.equal(languageName('zz'), 'zz'); assert.equal(langCode('spa'), 'es');
  const list = normalizeSubtitles([{ source: 'S', origin: 'source', items: [
    { id: '1', url: 'u1', lang: 'fra' }, { id: '2', url: 'u2', lang: 'eng' }, { id: '3', url: 'u3', lang: 'eng', label: 'SDH' }, { id: 'forced4', url: 'u4', lang: 'eng' }] }]);
  assert.equal(list[3].label, 'English 3'); assert.ok(list[3].forced && list[2].sdh);
  assert.equal(sortSubtitles(list, ['en'])[0].lang, 'en');
  assert.equal(pickAutoSubtitle(list, ['fr', 'en']).lang, 'fr');
  assert.equal(pickAutoSubtitle(list, ['en']).url, 'u2');
  assert.equal(pickAutoSubtitle(list, ['de']), null);
  assert.equal(pickAutoSubtitle(list, ['en'], 'off'), null);
});
