import test from 'node:test';
import assert from 'node:assert/strict';
import { pickFeatured, matchesType, typeFilters, releaseDay, dayLabel, buildAgenda, airStatus, nextSort, sortStreamsBy, splitTop, fmtBytes, shareUrl } from '../ux.js';
import { normalizeVideos } from '../progress.js';

test('pickFeatured: needs art, prefers background+description, dedupes, caps', () => {
  const items = [{ id: 'a' }, { id: 'b', poster: 'p' }, { id: 'c', background: 'x', description: 'd' }, { id: 'b', poster: 'p' }, { id: 'd', background: 'x' }, { id: 'e', poster: 'p', description: 'd' }];
  assert.deepEqual(pickFeatured(items, 3).map((i) => i.id), ['c', 'd', 'e']);
  assert.deepEqual(pickFeatured(null), []);
  assert.equal(pickFeatured(items, 10).length, 4);
});

test('type filters', () => {
  assert.ok(matchesType('movie', 'all') && matchesType('series', 'series') && !matchesType('movie', 'series'));
  assert.deepEqual(typeFilters(['movie', 'tv', 'tv']), ['all', 'movie', 'series', 'tv']);
});

test('releaseDay reads midnight-UTC as the calendar date', () => {
  const d = releaseDay('2026-10-24T00:00:00.000Z');
  assert.deepEqual([d.getFullYear(), d.getMonth(), d.getDate()], [2026, 9, 24]);
  const d2 = releaseDay('2026-10-24');
  assert.equal(d2.getDate(), 24);
  assert.equal(releaseDay('nope'), null);
  assert.equal(releaseDay(''), null);
});

const NOW = new Date(2026, 9, 22, 15, 0, 0); // Oct 22 2026, local
const iso = (offset) => { const d = new Date(Date.UTC(2026, 9, 22 + offset)); return d.toISOString(); };
const videosFor = (id, offs) => offs.map((o, i) => ({ id: `${id}:1:${i + 1}`, season: 1, episode: i + 1, title: `E${i + 1}`, released: iso(o) }));

test('dayLabel and airStatus', () => {
  assert.equal(dayLabel(releaseDay(iso(0)), NOW), 'Today');
  assert.equal(dayLabel(releaseDay(iso(1)), NOW), 'Tomorrow');
  assert.equal(dayLabel(releaseDay(iso(-1)), NOW), 'Yesterday');
  assert.match(dayLabel(releaseDay(iso(5)), NOW), /Oct 27/);
  assert.equal(airStatus(0), 'Airs today'); assert.equal(airStatus(3), 'In 3 days'); assert.equal(airStatus(-4), 'Aired 4 days ago');
});

test('buildAgenda groups, windows and orders', () => {
  const lib = [{ id: 's1', name: 'Alpha', type: 'series' }, { id: 's2', name: 'Beta', type: 'series' }, { id: 'm1', name: 'Film', type: 'movie' }, { id: 's3', name: 'NoMeta', type: 'series' }];
  const metas = {
    s1: { name: 'Alpha', videos: videosFor('s1', [-30, -2, 0, 4, 200]) },
    s2: { name: 'Beta', videos: [...videosFor('s2', [0, 4]), { id: 's2:0:1', season: 0, episode: 1, title: 'Special' }] },
  };
  const a = buildAgenda(lib, (id) => metas[id], normalizeVideos, NOW);
  assert.deepEqual(a.upcoming.map((d) => d.label), ['Today', a.upcoming[1].label]);
  assert.equal(a.upcoming[0].items.map((i) => i.name).join(), 'Alpha,Beta');
  assert.equal(a.upcoming[1].items.length, 2);
  assert.equal(a.recent.length, 1);
  assert.equal(a.recent[0].items[0].ep.title, 'E2');
  assert.equal(a.series, 3); assert.equal(a.tracked, 2);
  // 200 days out and 30 days back are outside the windows; undated special ignored
  const total = [...a.upcoming, ...a.recent].reduce((n, d) => n + d.items.length, 0);
  assert.equal(total, 5);
});

const mk = (title, res, size, src, playable = true, openUrl = '') => ({ title, res, size, source: { manifest: { name: src } }, cls: { playable, openUrl } });
test('nextSort toggles and sortStreamsBy orders', () => {
  assert.deepEqual(nextSort(null, 'quality'), { key: 'quality', dir: 'desc' });
  assert.deepEqual(nextSort({ key: 'quality', dir: 'desc' }, 'quality'), { key: 'quality', dir: 'asc' });
  assert.deepEqual(nextSort({ key: 'quality', dir: 'asc' }, 'source'), { key: 'source', dir: 'asc' });
  const l = [mk('a', '720p', 2e9, 'Zed'), mk('b', '4K', 9e9, 'Alpha'), mk('c', '1080p', 4e9, 'Mid'), mk('t', '4K', 20e9, 'Alpha', false)];
  assert.deepEqual(sortStreamsBy(l, 'quality', 'desc').map((s) => s.title), ['b', 'c', 'a', 't']);
  assert.deepEqual(sortStreamsBy(l, 'quality', 'asc').map((s) => s.title), ['a', 'c', 'b', 't']);
  assert.deepEqual(sortStreamsBy(l, 'size', 'desc').map((s) => s.title), ['b', 'c', 'a', 't']);
  assert.deepEqual(sortStreamsBy(l, 'source', 'asc').map((s) => s.title), ['b', 't', 'c', 'a']);
  assert.deepEqual(sortStreamsBy(l, 'source', 'desc').map((s) => s.title), ['a', 'c', 'b', 't']);
  assert.deepEqual(sortStreamsBy(l, 'bogus').map((s) => s.title), ['a', 'b', 'c', 't']);
});

test('splitTop never hides a lone row', () => {
  const a = [1, 2, 3, 4, 5, 6, 7];
  assert.deepEqual(splitTop(a, 5), { top: [1, 2, 3, 4, 5], rest: [6, 7] });
  assert.deepEqual(splitTop([1, 2, 3, 4, 5, 6], 5), { top: [1, 2, 3, 4, 5, 6], rest: [] });
  assert.deepEqual(splitTop([], 5), { top: [], rest: [] });
});

test('fmtBytes and shareUrl', () => {
  assert.equal(fmtBytes(0), ''); assert.equal(fmtBytes(850e6), '850 MB'); assert.equal(fmtBytes(2.45e9), '2.5 GB'); assert.equal(fmtBytes(15e9), '15 GB');
  assert.equal(shareUrl('https://x.example/', 'series', 'tt1:a b'), 'https://x.example/watch#/title/series/tt1%3Aa%20b');
});

import { orderRows, moveKey, suggest, parsePluginUrl } from '../ux.js';
test('orderRows and moveKey', () => {
  const rows = [{ key: 'a' }, { key: 'b' }, { key: 'c' }, { key: 'd' }];
  assert.deepEqual(orderRows(rows, ['c', 'a']).map((r) => r.key), ['c', 'a', 'b', 'd']);
  assert.deepEqual(orderRows(rows, undefined).map((r) => r.key), ['a', 'b', 'c', 'd']);
  assert.deepEqual(moveKey(['a', 'b', 'c'], 'b', -1), ['b', 'a', 'c']);
  assert.deepEqual(moveKey(['a', 'b', 'c'], 'a', -1), ['a', 'b', 'c']);
  assert.deepEqual(moveKey(['a', 'b', 'c'], 'c', 1), ['a', 'b', 'c']);
});
test('suggest ranks prefix before word-start before substring', () => {
  const out = suggest('mo', [['The Moon Gate', 'Mock Movie', 'Mo Money', 'Almost'], ['mock movie', 'Moana']], 10);
  assert.deepEqual(out, ['Mock Movie', 'Mo Money', 'Moana', 'The Moon Gate', 'Almost']);
  assert.deepEqual(suggest('', [['a']]), []);
  assert.deepEqual(suggest('mock movie', [['Mock Movie']]), []);
  assert.equal(suggest('a', [['a1', 'a2', 'a3']], 2).length, 2);
});
test('parsePluginUrl', () => {
  assert.equal(parsePluginUrl(' https://x.example/manifest.json '), 'https://x.example/manifest.json');
  assert.equal(parsePluginUrl('http://x.example/m.json'), null);
  assert.equal(parsePluginUrl('javascript:alert(1)'), null);
  assert.equal(parsePluginUrl('https://localhost/x'), null);
  assert.equal(parsePluginUrl(''), null);
});
