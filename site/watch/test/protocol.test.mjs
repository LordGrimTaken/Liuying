import test from 'node:test';
import assert from 'node:assert/strict';
import * as P from '../addon/protocol.js';
import * as S from '../addon/streams.js';
import { mergeMetas } from '../addon/client.js';

test('normalizeManifestUrl handles stremio://, bare hosts and rejects non-manifest links', () => {
  assert.equal(P.normalizeManifestUrl('stremio://example.com/abc/manifest.json'), 'https://example.com/abc/manifest.json');
  assert.equal(P.normalizeManifestUrl('example.com/manifest.json'), 'https://example.com/manifest.json');
  assert.equal(P.normalizeManifestUrl(' https://a.b/x/manifest.json?x=1#y '), 'https://a.b/x/manifest.json');
  assert.throws(() => P.normalizeManifestUrl('https://example.com/'), /manifest\.json/);
  assert.throws(() => P.normalizeManifestUrl(''), /Paste/);
  assert.throws(() => P.normalizeManifestUrl('ftp://x/manifest.json'), /http/);
});

test('resource URL building: base, ids, extras', () => {
  const m = 'https://a.b/cfg/manifest.json';
  assert.equal(P.baseUrl(m), 'https://a.b/cfg');
  assert.equal(P.buildResourceUrl(m, 'stream', 'series', 'tt1:2:3'), 'https://a.b/cfg/stream/series/tt1:2:3.json');
  assert.equal(P.buildResourceUrl(m, 'meta', 'movie', 'a/b c'), 'https://a.b/cfg/meta/movie/a%2Fb%20c.json');
  assert.equal(P.buildCatalogUrl(m, 'movie', 'top', { genre: 'Sci-Fi & Fantasy', skip: 20 }),
    'https://a.b/cfg/catalog/movie/top/genre=Sci-Fi%20%26%20Fantasy&skip=20.json');
  assert.equal(P.buildCatalogUrl(m, 'movie', 'top', { search: 'dune', skip: '' }), 'https://a.b/cfg/catalog/movie/top/search=dune.json');
  assert.equal(P.buildCatalogUrl(m, 'movie', 'top'), 'https://a.b/cfg/catalog/movie/top.json');
});

test('resource matching: string list, object list, types and idPrefixes', () => {
  const strMan = { types: ['movie'], idPrefixes: ['tt'], resources: ['stream', 'meta'] };
  assert.ok(P.resourceApplies(strMan, 'stream', 'movie', 'tt123'));
  assert.ok(!P.resourceApplies(strMan, 'stream', 'series', 'tt123'));
  assert.ok(!P.resourceApplies(strMan, 'stream', 'movie', 'kitsu:1'));
  assert.ok(!P.resourceApplies(strMan, 'subtitles', 'movie', 'tt123'));
  const objMan = { types: ['movie', 'series'], resources: [
    { name: 'stream', types: ['series'], idPrefixes: ['tt', 'kitsu'] }, { name: 'meta', types: ['movie'] }, 'subtitles'] };
  assert.ok(P.resourceApplies(objMan, 'stream', 'series', 'kitsu:5'));
  assert.ok(!P.resourceApplies(objMan, 'stream', 'movie', 'tt1'));
  assert.ok(P.resourceApplies(objMan, 'meta', 'movie', 'anything'));
  assert.ok(P.resourceApplies(objMan, 'subtitles', 'series', 'x'));
});

test('catalog capabilities: modern and legacy extras', () => {
  const c = P.catalogCapabilities({ extra: [{ name: 'genre', options: ['A', 'B'], isRequired: true }, { name: 'skip' }, { name: 'search' }] });
  assert.deepEqual([c.search, c.skip, c.genre, c.genreRequired, c.genreOptions], [true, true, true, true, ['A', 'B']]);
  const legacy = P.catalogCapabilities({ extraSupported: ['search', 'skip'], extraRequired: ['search'] });
  assert.ok(legacy.search && legacy.searchRequired);
  assert.ok(!P.isHomeEligible({ extra: [{ name: 'search', isRequired: true }] }));
  assert.ok(!P.isHomeEligible({ extra: [{ name: 'foo', isRequired: true }] }));
  assert.ok(P.isHomeEligible({ extra: [{ name: 'genre', isRequired: true, options: ['x'] }] }));
  assert.ok(P.isSearchable({ extra: [{ name: 'search' }] }));
  assert.ok(!P.isSearchable({ extra: [{ name: 'skip' }] }));
});

test('validateManifest rejects junk with specific messages', () => {
  assert.throws(() => P.validateManifest(null), /manifest/);
  assert.throws(() => P.validateManifest({ name: 'x', resources: [] }), /id/);
  assert.throws(() => P.validateManifest({ id: 'x', resources: [] }), /name/);
  assert.throws(() => P.validateManifest({ id: 'x', name: 'y' }), /resources/);
  const ok = P.validateManifest({ id: 'x', name: 'y', resources: ['catalog'], catalogs: [{ id: 'a', type: 'movie' }, {}] });
  assert.equal(ok.catalogs.length, 1);
});

test('quality parsing from name/title/description', () => {
  const b = S.parseBadges('Torrentio\n4K HDR Dolby Vision x265 Atmos 7.1\n\u{1F464} 42 \u{1F4BE} 12.4 GB');
  const labels = b.map((x) => x.label);
  for (const l of ['4K', 'Dolby Vision', 'HEVC', 'Atmos', '7.1', '12.4 GB', '42 seeds']) assert.ok(labels.includes(l), l + ' in ' + labels);
  assert.equal(S.parseResolution('Movie.2020.1080p.BluRay.x264'), '1080p');
  assert.equal(S.parseResolution('720p'), '720p');
  assert.equal(S.parseResolution('nothing here'), null);
  assert.equal(S.parseSizeBytes('2.5 GB'), 2.5e9);
});

test('stream classification for the browser', () => {
  assert.equal(S.classifyStream({ url: 'https://x/y.mp4' }).kind, 'direct');
  assert.equal(S.classifyStream({ url: 'https://x/y.m3u8?token=1' }).kind, 'hls');
  const blocked = S.classifyStream({ url: 'http://x/y.mp4' });
  assert.equal(blocked.kind, 'blocked'); assert.equal(blocked.playable, false);
  assert.match(blocked.note, /Blocked by your browser/);
  assert.ok(S.classifyStream({ url: 'http://x/y.mp4' }, { pageSecure: false }).playable);
  const t = S.classifyStream({ infoHash: 'abc' });
  assert.equal(t.kind, 'torrent'); assert.match(t.note, /Not playable in the browser/);
  const e = S.classifyStream({ externalUrl: 'https://example.com/watch' });
  assert.equal(e.kind, 'external'); assert.equal(e.openUrl, 'https://example.com/watch');
  assert.equal(S.classifyStream({ externalUrl: 'javascript:alert(1)' }).openUrl, '');
  assert.equal(S.classifyStream({ url: 'https://x/a', behaviorHints: { proxyHeaders: { request: { A: 'b' } } } }).playable, false);
  assert.equal(S.classifyStream({ ytId: 'abc' }).kind, 'youtube');
});

test('pickSimilar prefers binge group, then source+resolution', () => {
  const mk = (url, bg, res, src) => ({ cls: { playable: true }, bingeGroup: bg, res, source: { url: src } });
  const list = [mk('a', 'g1', '720p', 's1'), mk('b', 'g2', '1080p', 's2'), mk('c', '', '1080p', 's1')];
  assert.equal(S.pickSimilar(list, { bingeGroup: 'g2' }), list[1]);
  assert.equal(S.pickSimilar(list, { sourceUrl: 's1', res: '1080p' }), list[2]);
  assert.equal(S.pickSimilar(list, { sourceUrl: 'zz' }), null);
});

test('mergeMetas fills gaps without overwriting', () => {
  const m = mergeMetas([{ name: 'A', poster: '' , description: 'd1' }, { name: 'B', poster: 'p', description: 'd2', cast: ['x'] }]);
  assert.deepEqual(m, { name: 'A', poster: 'p', description: 'd1', cast: ['x'] });
});

test('noCatalogsNotice explains sources without browsable catalogs', () => {
  assert.equal(P.noCatalogsNotice({ resources: ['stream', 'meta'], catalogs: [] }), 'This add-on has no browsable catalogs; it provides: stream, meta');
  assert.ok(P.noCatalogsNotice({ resources: ['catalog'], catalogs: [{ type: 'movie', id: 's', extra: [{ name: 'search', isRequired: true }] }] }));
  assert.equal(P.noCatalogsNotice({ resources: ['catalog'], catalogs: [{ type: 'movie', id: 'top' }] }), null);
});
