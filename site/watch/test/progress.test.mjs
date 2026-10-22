import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../progress.js';

const NOW = Date.parse('2026-06-10T12:00:00');
const eps = G.normalizeVideos([
  { id: 'x:1:1', season: 1, episode: 1, released: '2020-01-01' }, { id: 'x:1:2', season: 1, episode: 2, released: '2020-01-08' },
  { id: 'x:1:3', season: 1, episode: 3, released: '2099-01-01' }, { id: 'x:0:1', season: 0, episode: 1 },
  { id: 'x:1:2b', season: 1, episode: 2, title: 'rich', released: '2020-01-08' },
]);

test('90% rule marks watched and clears the resume point', () => {
  assert.ok(G.reachedThreshold(90, 100));
  assert.ok(!G.reachedThreshold(89, 100));
  assert.ok(!G.reachedThreshold(5, 0));
  const r = G.applySample(null, { mediaId: 'm', positionMs: 91000, durationMs: 100000, now: 5 });
  assert.equal(r.watched, true); assert.equal(r.positionMs, 0); assert.equal(r.durationMs, 100000);
  const r2 = G.applySample(r, { mediaId: 'm', positionMs: 10000, durationMs: 100000, now: 9 });
  assert.equal(r2.watched, true, 'watched flag stays when re-watching');
  const r3 = G.applySample(null, { mediaId: 'm', positionMs: 50000, durationMs: 100000, now: 5, threshold: 0.5 });
  assert.equal(r3.watched, true);
  const r4 = G.applySample(null, { mediaId: 'm', positionMs: 999999, durationMs: 1000, now: 1 });
  assert.ok(r4.positionMs <= r4.durationMs);
});

test('isInProgress: needs 3% and 60 s, not watched, below threshold', () => {
  const r = (p, d, w = false) => ({ positionMs: p, durationMs: d, watched: w });
  assert.ok(G.isInProgress(r(600000, 3600000)));
  assert.ok(!G.isInProgress(r(30000, 600000)), 'under 60s');
  assert.ok(!G.isInProgress(r(40000, 3600000)), 'under 3%');
  assert.ok(!G.isInProgress(r(3500000, 3600000)), 'over threshold');
  assert.ok(!G.isInProgress(r(600000, 3600000, true)));
});

test('resumePosition uses fraction when duration differs by more than 5%', () => {
  const rec = { positionMs: 600000, durationMs: 3600000, watched: false };
  assert.equal(G.resumePosition(rec, 3600000), 600000);
  assert.equal(G.resumePosition(rec, 3700000), 600000);
  assert.equal(G.resumePosition(rec, 7200000), 1200000);
  assert.equal(G.resumePosition({ ...rec, watched: true }, 3600000), 0);
  assert.equal(G.resumePosition({ positionMs: 3500000, durationMs: 3600000 }, 3600000), 0);
});

test('normalizeVideos dedupes by season/episode keeping richer info, specials last', () => {
  assert.deepEqual(eps.map((e) => e.id), ['x:1:1', 'x:1:2b', 'x:1:3', 'x:0:1']);
});

test('isReleased: future dates are unreleased, no date is released', () => {
  assert.ok(G.isReleased({ released: '' }, NOW));
  assert.ok(G.isReleased({ released: '2020-01-01' }, NOW));
  assert.ok(!G.isReleased({ released: '2099-01-01' }, NOW));
});

test('nextEpisode: after highest watched, skips unreleased and specials; null when finished', () => {
  const rec = (set) => (id) => (set.includes(id) ? { watched: true } : undefined);
  assert.equal(G.nextEpisode(eps, rec([]), NOW).id, 'x:1:1');
  assert.equal(G.nextEpisode(eps, rec(['x:1:1']), NOW).id, 'x:1:2b');
  assert.equal(G.nextEpisode(eps, rec(['x:1:1', 'x:1:2b']), NOW), null, 'ep 3 not released, special ignored');
  assert.equal(G.nextEpisode(eps, rec(['x:1:2b']), NOW), null, 'skipped ep1: next is after highest watched (then unreleased)');
});

test('playTarget: resume, up next, watch again, movies', () => {
  const recs = {};
  const getRec = (id) => recs[id || ''];
  const t = (o = {}) => G.playTarget({ type: 'series', videos: eps, getRec, now: NOW, ...o });
  assert.deepEqual([t().episodeId, t().label], ['x:1:1', 'play']);
  recs['x:1:1'] = { watched: true, positionMs: 0, durationMs: 100, updatedAt: 1 };
  assert.deepEqual([t().episodeId, t().label], ['x:1:2b', 'play']);
  recs['x:1:2b'] = { watched: false, positionMs: 600000, durationMs: 3600000, updatedAt: 2 };
  assert.deepEqual([t().episodeId, t().label], ['x:1:2b', 'resume']);
  recs['x:1:2b'] = { watched: true, positionMs: 0, durationMs: 3600000, updatedAt: 3 };
  assert.deepEqual([t().episodeId, t().label], ['x:1:1', 'again']);
  const mrecs = {};
  const m = (r) => G.playTarget({ type: 'movie', videos: [], getRec: () => r });
  assert.equal(m(undefined).label, 'play');
  assert.equal(m({ positionMs: 600000, durationMs: 3600000, watched: false }).label, 'resume');
  assert.equal(m({ watched: true, positionMs: 0, durationMs: 1 }).label, 'again');
});

test('continueWatching: movies, one card per series, up next, dismissal', () => {
  const metas = { s1: { videos: eps.map((e) => ({ ...e })) } };
  const metaFor = (id) => metas[id] || null;
  const mk = (o) => ({ dismissed: false, watched: false, positionMs: 0, durationMs: 3600000, updatedAt: 1, episodeId: null, ...o });
  const progress = [
    mk({ mediaId: 'mv', positionMs: 900000, updatedAt: 50 }),
    mk({ mediaId: 'mv2', positionMs: 30000, updatedAt: 60 }), // not started
    mk({ mediaId: 'mv3', watched: true, updatedAt: 70 }),
    mk({ mediaId: 's1', episodeId: 'x:1:1', watched: true, updatedAt: 100 }),
  ];
  let r = G.continueWatching(progress, metaFor, { now: NOW });
  assert.deepEqual(r.cards.map((c) => [c.mediaId, c.kind, c.episodeId]), [['s1', 'upnext', 'x:1:2b'], ['mv', 'resume', null]]);
  // in-progress episode beats up next
  r = G.continueWatching([...progress, mk({ mediaId: 's1', episodeId: 'x:1:2b', positionMs: 900000, updatedAt: 120 })], metaFor, { now: NOW });
  assert.deepEqual(r.cards[0], { ...r.cards[0], mediaId: 's1', kind: 'resume', episodeId: 'x:1:2b' });
  assert.equal(r.cards.filter((c) => c.mediaId === 's1').length, 1);
  // dismiss marker hides the series until it is played again
  const dismissed = [...progress, mk({ mediaId: 's1', episodeId: null, dismissed: true, updatedAt: 200 })];
  assert.deepEqual(G.continueWatching(dismissed, metaFor, { now: NOW }).cards.map((c) => c.mediaId), ['mv']);
  const replayed = [...dismissed, mk({ mediaId: 's1', episodeId: 'x:1:2b', positionMs: 900000, updatedAt: 300 })];
  assert.ok(G.continueWatching(replayed, metaFor, { now: NOW }).cards.some((c) => c.mediaId === 's1'));
  // no meta -> requested lazily
  r = G.continueWatching(progress, () => null, { now: NOW });
  assert.deepEqual(r.needMeta, ['s1']);
  // showUpNext off
  assert.deepEqual(G.continueWatching(progress, metaFor, { now: NOW, showUpNext: false }).cards.map((c) => c.mediaId), ['mv']);
  // all watched: complete
  const all = [mk({ mediaId: 's1', episodeId: 'x:1:1', watched: true }), mk({ mediaId: 's1', episodeId: 'x:1:2b', watched: true })];
  assert.equal(G.continueWatching(all, metaFor, { now: NOW }).cards.length, 0);
});

test('followingEpisode only returns released regular episodes', () => {
  assert.equal(G.followingEpisode(eps, 'x:1:1', NOW).id, 'x:1:2b');
  assert.equal(G.followingEpisode(eps, 'x:1:2b', NOW), null);
});
