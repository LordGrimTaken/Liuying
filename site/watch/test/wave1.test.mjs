// Wave 1 (web) pure logic: search ranking, surprise, history/CSV, filters, theme, sync face, playback helpers, encodings, presets.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { titleScore, rankByQuery, exactMatches, searchTips, pickSurprise, resolveTheme, buildHistory, toCsv, libraryCsvRows, historyCsvRows, historyJson, HISTORY_COLUMNS,
  qualityOptions, languageOptions, filterStreams, shelfStatus, calendarBadgeCount, filterAgenda, relTime, pendingCount, syncFace, sortStreamsBy } from '../ux.js';
import { streamLanguages, describeStream, sortStreams, pickBestPlayable } from '../addon/streams.js';
import { resumeStartSec, previousEpisode, followingEpisode, normalizeVideos, parseEpisodeId } from '../progress.js';
import { createStallWatch, playbackErrorInfo, startSleep, sleepStatus, sleepLabel, statsRows, bufferAhead, pickAudioIndex, STALL_MS } from '../player/playback.js';
import { detectEncoding, decodeSubtitleBytes, SUB_PRESETS, presetOf, lang2encoding, srtToVtt } from '../player/subtitles.js';

const fx = JSON.parse(fs.readFileSync(new URL('./enc-fixtures.json', import.meta.url), 'utf8'));
const bytes = (k) => Uint8Array.from(Buffer.from(fx[k].hex, 'hex'));

// ---------- L006 search ranking ----------
test('titleScore tiers: exact, whole-word prefix, prefix, word, substring', () => {
  assert.equal(titleScore('Alien', 'alien'), 0);
  assert.equal(titleScore('ALIEN!', 'Alien'), 0);
  assert.equal(titleScore('Alien 3', 'Alien'), 1);
  assert.equal(titleScore('Aliens', 'Alien'), 2);
  assert.equal(titleScore('Prometheus: Alien Origins', 'Alien'), 3);
  assert.equal(titleScore('Terrible Aliens', 'Alien'), 4);
  assert.equal(titleScore('Predator', 'Alien'), 5);
  assert.equal(titleScore('Amélie', 'amelie'), 0); // accents folded
  assert.equal(titleScore('', 'x'), 5);
});
test('rankByQuery puts the exact title above sequels and partials, keeps source order inside a tier', () => {
  const items = [{ id: 'a', name: 'Aliens' }, { id: 'b', name: 'Alien: Covenant' }, { id: 'c', name: 'Alien' }, { id: 'd', name: 'Alien 3' }, { id: 'e', name: 'Space Aliens' }];
  assert.deepEqual(rankByQuery(items, 'Alien').map((i) => i.id), ['c', 'b', 'd', 'a', 'e']);
  assert.deepEqual(rankByQuery(null, 'x'), []);
  const same = [{ id: 1, name: 'X Y' }, { id: 2, name: 'X Z' }];
  assert.deepEqual(rankByQuery(same, 'x').map((i) => i.id), [1, 2]);
});
test('exactMatches dedupes across sections', () => {
  const m = exactMatches([[{ id: 'x', name: 'Alien' }, { id: 'y', name: 'Aliens' }], [{ id: 'x', name: 'Alien' }, { id: 'z', name: 'alien' }]], 'Alien');
  assert.deepEqual(m.map((i) => i.id), ['x', 'z']);
});

// ---------- L009 tips ----------
test('searchTips: concrete, never empty, at most 3, mentions sources when none can search', () => {
  const t = searchTips('The Moon Gate Extended Cut (2021)', { sources: 2, searchable: 0 });
  assert.ok(t.length >= 2 && t.length <= 3);
  assert.ok(t[0].includes('Remove the year'));
  assert.ok(t.some((x) => /searchable/.test(x)));
  assert.ok(searchTips('ab', { sources: 0, searchable: 0 }).some((x) => /Add a source/.test(x)));
  assert.ok(searchTips('Matrix', { sources: 1, searchable: 1 }).length >= 2);
});

// ---------- L010 surprise ----------
test('pickSurprise: unwatched only when possible, falls back, avoids repeating, deterministic with rng', () => {
  const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
  const watched = new Set(['a', 'b']);
  assert.equal(pickSurprise(items, { isWatched: (i) => watched.has(i.id), rng: () => 0 }).id, 'c');
  assert.ok(['a', 'b', 'c'].includes(pickSurprise(items, { isWatched: () => true, rng: () => 0.99 }).id));
  assert.equal(pickSurprise(items, { avoid: 'a', rng: () => 0 }).id, 'b');
  assert.equal(pickSurprise([], {}), null);
  assert.equal(pickSurprise([{ id: 'only' }], { avoid: 'only', rng: () => 0 }).id, 'only');
});

// ---------- L099 theme ----------
test('resolveTheme: system follows the OS, unknown falls back to dark', () => {
  assert.equal(resolveTheme('system', true), 'dark');
  assert.equal(resolveTheme('system', false), 'paper');
  assert.equal(resolveTheme('black', false), 'black');
  assert.equal(resolveTheme('nope', false), 'dark');
  assert.equal(resolveTheme(undefined, true), 'dark');
});

// ---------- L023 history, L031 export ----------
const NOW = new Date(2026, 9, 22, 15, 0, 0);
const at = (dOff, h = 12) => new Date(2026, 9, 22 + dOff, h).getTime();
const PROG = [
  { mediaId: 'm1', episodeId: null, positionMs: 0, durationMs: 100, watched: true, updatedAt: at(0, 9), name: 'Movie One', type: 'movie' },
  { mediaId: 's1', episodeId: 's1:1:2', positionMs: 30000, durationMs: 60000, watched: false, updatedAt: at(0, 14), name: 'Show, "One"', type: 'series' },
  { mediaId: 's1', episodeId: null, positionMs: 0, durationMs: 0, watched: false, dismissed: true, updatedAt: at(0, 15) }, // dismissed marker
  { mediaId: 'm2', episodeId: null, positionMs: 0, durationMs: 100, watched: false, dismissed: true, updatedAt: at(-1) }, // removed entry
  { mediaId: 'm3', episodeId: null, positionMs: 5000, durationMs: 100000, watched: false, updatedAt: at(-1, 20) },
  { mediaId: 'm4', episodeId: null, positionMs: 0, durationMs: 100, watched: true, updatedAt: at(-5) },
];
test('buildHistory: newest first, grouped by local day, hides removed entries and dismissed markers', () => {
  const days = buildHistory(PROG, (id) => ({ name: id === 'm3' ? 'From Shelf' : '' }), NOW, parseEpisodeId);
  assert.deepEqual(days.map((d) => d.label.split(',')[0]), ['Today', 'Yesterday', 'Sat Oct 17'.split(',')[0]].map((x, i) => (i === 2 ? days[2].label.split(',')[0] : x)));
  assert.equal(days.length, 3);
  assert.deepEqual(days[0].items.map((i) => i.rec.mediaId), ['s1', 'm1']);
  assert.deepEqual(days[0].items[0].ep, { season: 1, episode: 2 });
  assert.equal(days[1].items.length, 1);
  assert.equal(days[1].items[0].name, 'From Shelf'); // name from hint
  assert.equal(days[2].items[0].rec.mediaId, 'm4');
});
test('toCsv quotes, escapes and neutralises formulas', () => {
  const out = toCsv(['a', 'b'], [['x,y', 'he said "hi"'], ['=1+1', '+cmd'], ['-5', '@x'], [null, 'line\nbreak'], ['plain', 3]]);
  assert.equal(out, 'a,b\r\n"x,y","he said ""hi"""\r\n\'=1+1,\'+cmd\r\n\'-5,\'@x\r\n,"line\nbreak"\r\nplain,3\r\n');
});
test('library and history export rows', () => {
  const rows = libraryCsvRows([{ id: 'tt1', type: 'movie', name: 'A, B', year: '1999', addedAt: Date.UTC(2026, 0, 2) }]);
  assert.deepEqual(rows[0], ['tt1', 'movie', 'A, B', '1999', '2026-01-02T00:00:00.000Z']);
  const days = buildHistory(PROG, () => ({}), NOW, parseEpisodeId);
  const hrows = historyCsvRows(days);
  assert.equal(hrows.length, 4);
  assert.equal(hrows[0].length, HISTORY_COLUMNS.length);
  assert.equal(hrows[0][0], 'Show, "One"');
  assert.equal(hrows[0][6], 'no'); assert.equal(hrows[0][7], 30);
  const csv = toCsv(HISTORY_COLUMNS, hrows);
  assert.ok(csv.split('\r\n')[1].startsWith('"Show, ""One"""'));
  const j = historyJson(days);
  assert.equal(j[0].season, 1); assert.equal(j[0].episode, 2); assert.equal(j[1].watched, true);
});

// ---------- L064 / L065 stream sorting and filters ----------
const mk = (name, size) => describeStream({ name, title: `${name} ${size ? size + ' GB' : ''}`, url: 'https://x.test/a.mp4' }, { url: 'u', manifest: { name: 'S' } }, 0, { pageSecure: true });
test('combined sort: resolution desc then size desc', () => {
  const list = [mk('720p a', 3), mk('1080p small', 2), mk('1080p big', 9), mk('4K', 1), mk('1080p mid', 5)];
  assert.deepEqual(sortStreams(list, 'resolution-size').map((s) => s.title), ['4K', '1080p big', '1080p mid', '1080p small', '720p a']);
  assert.deepEqual(sortStreamsBy(list, 'quality', 'desc').map((s) => s.title), ['4K', '1080p big', '1080p mid', '1080p small', '720p a']);
  assert.equal(pickBestPlayable(list).title, '4K');
  assert.equal(pickBestPlayable([]), null);
});
test('streamLanguages and quality/language chips', () => {
  assert.deepEqual(streamLanguages('Movie.2020.1080p 🇬🇧 🇫🇷 MULTi-Audio'), ['English', 'French', 'Multi audio']);
  assert.deepEqual(streamLanguages('Spanish latino 720p'), ['Spanish']);
  assert.deepEqual(streamLanguages('Some.Release.x264'), []);
  const list = [mk('1080p 🇬🇧', 2), mk('720p 🇬🇧 🇫🇷', 1), mk('1080p 🇫🇷', 3), mk('480p', 1)];
  assert.deepEqual(qualityOptions(list), ['1080p', '720p', 'SD']);
  assert.deepEqual(languageOptions(list), ['English', 'French']);
  assert.equal(filterStreams(list, { quality: '1080p' }).length, 2);
  assert.equal(filterStreams(list, { lang: 'French' }).length, 2);
  assert.equal(filterStreams(list, { quality: '1080p', lang: 'French' }).length, 1);
  assert.equal(filterStreams(list, {}).length, 4);
});

// ---------- L005 shelf status, L115 calendar ----------
test('shelfStatus maps progress state to Planned / Watching / Completed', () => {
  assert.equal(shelfStatus({ any: false, prog: false }), 'planned');
  assert.equal(shelfStatus({ any: false, prog: true }), 'watching');
  assert.equal(shelfStatus({ any: true, prog: false }), 'completed');
  assert.equal(shelfStatus({ any: true, prog: true }), 'watching');
});
test('calendar badge counts unwatched episodes that aired in the last week or air today; filter hides watched', () => {
  const ep = (id) => ({ id });
  const agenda = {
    recent: [{ diff: -2, items: [{ mediaId: 's1', ep: ep('s1:1:1') }, { mediaId: 's2', ep: ep('s2:1:1') }] }],
    upcoming: [{ diff: 0, items: [{ mediaId: 's3', ep: ep('s3:1:1') }] }, { diff: 3, items: [{ mediaId: 's4', ep: ep('s4:1:1') }] }],
  };
  assert.equal(calendarBadgeCount(agenda, () => false), 3);
  assert.equal(calendarBadgeCount(agenda, (m) => m === 's1'), 2);
  assert.equal(calendarBadgeCount({ recent: [], upcoming: [] }), 0);
  const f = filterAgenda(agenda, { hideWatched: true, isWatched: (m) => m === 's1' || m === 's3' });
  assert.equal(f.recent[0].items.length, 1);
  assert.equal(f.upcoming.length, 1);
  assert.equal(agenda.recent[0].items.length, 2); // not mutated
  assert.equal(filterAgenda(agenda, {}), agenda);
});

// ---------- L032 / L104 sync ----------
test('relTime and pendingCount', () => {
  const n = 1_000_000_000_000;
  assert.equal(relTime(0, n), 'never');
  assert.equal(relTime(n - 10_000, n), 'just now');
  assert.equal(relTime(n - 60_000, n), '1 min ago');
  assert.equal(relTime(n - 5 * 60_000, n), '5 min ago');
  assert.equal(relTime(n - 3 * 3600_000, n), '3 h ago');
  assert.equal(relTime(n - 30 * 3600_000, n), 'yesterday');
  assert.equal(relTime(n - 5 * 86400_000, n), '5 days ago');
  assert.equal(pendingCount({ sources: true, settings: false, libAdd: { a: 1, b: 2 }, libRemove: ['c'], progress: { x: true } }), 5);
  assert.equal(pendingCount({}), 0);
});
test('sync face never relies on colour: every state has a distinct glyph or word and a screen-reader sentence', () => {
  const states = ['idle', 'syncing', 'offline', 'error', 'checking'];
  const faces = states.map((s) => syncFace(s));
  assert.equal(new Set(faces.map((f) => f.glyph)).size, 5);
  for (const f of faces) assert.ok(f.word && f.sr.length > 5);
  assert.equal(syncFace('syncing', true).word, 'Waking');
  assert.equal(syncFace('idle', true).word, 'Synced');
  assert.equal(syncFace('error').glyph, '!');
});

// ---------- L037 resume rewind, L044 prev/next ----------
test('resumeStartSec backs up by the rewind setting, never below zero, none without progress', () => {
  const rec = { positionMs: 100000, durationMs: 1000000, watched: false };
  assert.equal(resumeStartSec(rec, 1000000, 0.9, 0), 100);
  assert.equal(resumeStartSec(rec, 1000000, 0.9, 10), 90);
  assert.equal(resumeStartSec({ ...rec, positionMs: 40000 }, 1000000, 0.9, 5), 35);
  assert.equal(resumeStartSec({ positionMs: 31000, durationMs: 1000000 }, 1000000, 0.9, 40), 0);
  assert.equal(resumeStartSec(null, 1000, 0.9, 10), 0);
  assert.equal(resumeStartSec({ ...rec, watched: true }, 1000000, 0.9, 10), 0);
  assert.equal(resumeStartSec(rec, 1000000, 0.9, -5), 100);
});
test('previousEpisode and followingEpisode skip specials', () => {
  const eps = normalizeVideos([{ id: 'x:0:1', season: 0, episode: 1 }, { id: 'x:1:1', season: 1, episode: 1 }, { id: 'x:1:2', season: 1, episode: 2 }, { id: 'x:2:1', season: 2, episode: 1 }]);
  // specials sort last
  assert.deepEqual(eps.map((e) => e.id), ['x:1:1', 'x:1:2', 'x:2:1', 'x:0:1']);
  assert.equal(previousEpisode(eps, 'x:1:1'), null);
  assert.equal(previousEpisode(eps, 'x:1:2').id, 'x:1:1');
  assert.equal(previousEpisode(eps, 'x:2:1').id, 'x:1:2');
  assert.equal(previousEpisode(eps, 'x:0:1').id, 'x:2:1');
  assert.equal(previousEpisode(eps, 'nope'), null);
  assert.equal(followingEpisode(eps, 'x:1:2').id, 'x:2:1');
  assert.equal(followingEpisode(eps, 'x:2:1'), null);
});

// ---------- L048 stall watch ----------
test('stall watch fires once after the delay, can be cancelled, re-arms after recovery', () => {
  const timers = []; let fired = 0;
  const w = createStallWatch({ ms: STALL_MS, onStall: () => fired++, setT: (fn, ms) => { timers.push({ fn, ms }); return timers.length; }, clearT: (id) => { timers[id - 1].dead = true; } });
  assert.equal(STALL_MS, 15000);
  w.begin(); w.begin(); // second begin is a no-op
  assert.equal(timers.length, 1); assert.equal(timers[0].ms, 15000); assert.ok(w.active);
  w.end(); assert.ok(!w.active);
  assert.ok(timers[0].dead);
  w.begin(); assert.equal(timers.length, 2);
  timers[1].fn();
  assert.equal(fired, 1); assert.ok(!w.active);
  w.begin(); assert.equal(timers.length, 3); // next stall re-arms
});

// ---------- L050 plain-language errors ----------
test('every error class has a title, why, next step and at least one action; primary action is offered', () => {
  const cases = [
    [{ offline: true }, 'offline', 'retry'], [{ status: 403 }, 'forbidden', 'another'], [{ status: 401 }, 'forbidden', 'another'], [{ status: 404 }, 'gone', 'another'], [{ status: 410 }, 'gone', 'another'],
    [{ status: 429 }, 'busy', 'retry'], [{ status: 503 }, 'server', 'retry'], [{ timedOut: true }, 'timeout', 'retry'], [{ mediaCode: 3 }, 'codec', 'another'],
    [{ hlsType: 'mediaError', hlsDetail: 'bufferAddCodecError' }, 'codec', 'another'], [{ mediaCode: 2 }, 'network', 'retry'], [{ hlsType: 'networkError' }, 'network', 'retry'],
    [{ mediaCode: 4 }, 'format', 'another'], [{}, 'unknown', 'retry'], [{ noHls: true }, 'nohls', 'another'],
  ];
  for (const [input, id, primary] of cases) {
    const e = playbackErrorInfo(input);
    assert.equal(e.id, id, JSON.stringify(input));
    assert.equal(e.primary, primary, id);
    assert.ok(e.title.length > 8 && e.why.length > 15 && e.next.length > 10, id);
    assert.ok(e.actions.includes(e.primary) && e.actions.includes('close'), id);
  }
  assert.match(playbackErrorInfo({ status: 403 }).why, /403/);
  assert.match(playbackErrorInfo({ mediaCode: 3 }).next, /H\.264|Android/);
  assert.equal(playbackErrorInfo({ offline: true, status: 404 }).id, 'offline'); // offline wins
});

// ---------- L052 sleep timer ----------
test('sleep timer: minutes count down, end-of-episode has no clock, off is null', () => {
  const t0 = 1_000_000;
  assert.equal(startSleep('off', t0), null);
  assert.equal(startSleep('nonsense', t0), null);
  const t = startSleep('15', t0);
  assert.deepEqual(sleepStatus(t, t0), { remainingMs: 900000, expired: false });
  assert.equal(sleepLabel(t, t0), '15:00');
  assert.equal(sleepLabel(t, t0 + 61_000), '13:59');
  assert.equal(sleepStatus(t, t0 + 900000).expired, true);
  assert.equal(sleepStatus(t, t0 + 999999).remainingMs, 0);
  const e = startSleep('end', t0);
  assert.deepEqual(sleepStatus(e, t0 + 1e9), { remainingMs: null, expired: false });
  assert.equal(sleepLabel(e), 'End of episode');
  assert.deepEqual(sleepStatus(null), { remainingMs: null, expired: false });
  assert.equal(startSleep('60', t0).endsAt, t0 + 3600000);
});

// ---------- L049 stats ----------
test('stats rows leave unknown values out and format units', () => {
  const rows = statsRows({ width: 1920, height: 1080, bandwidth: 8_400_000, levelBitrate: 4_500_000, dropped: 3, total: 1200, bufferAhead: 12.34, speed: 1.25, kind: 'hls', host: 'cdn.example' });
  assert.deepEqual(rows.map((r) => r[0]), ['Resolution', 'Stream bitrate', 'Connection speed', 'Dropped frames', 'Buffer ahead', 'Speed', 'Type', 'Host']);
  assert.equal(rows[0][1], '1920×1080'); assert.equal(rows[1][1], '4.5 Mbps'); assert.equal(rows[3][1], '3 of 1200'); assert.equal(rows[4][1], '12.3 s');
  assert.deepEqual(statsRows({}).map((r) => r[0]), ['Speed']);
  assert.equal(statsRows({ bandwidth: 640_000 })[0][1], '640 kbps');
  const tr = (ranges) => ({ length: ranges.length, start: (i) => ranges[i][0], end: (i) => ranges[i][1] });
  assert.equal(bufferAhead(tr([[0, 10], [20, 40]]), 25), 15);
  assert.equal(bufferAhead(tr([[0, 10]]), 25), 0);
  assert.equal(bufferAhead(null, 5), 0);
});

// ---------- L047 audio language memory ----------
test('pickAudioIndex matches by language code or label', () => {
  const tracks = [{ lang: 'eng', label: 'English' }, { lang: 'jpn', label: 'Japanese' }, { lang: '', label: 'Commentary' }];
  assert.equal(pickAudioIndex(tracks, 'ja'), 1);
  assert.equal(pickAudioIndex(tracks, 'jpn'), 1);
  assert.equal(pickAudioIndex(tracks, 'en'), 0);
  assert.equal(pickAudioIndex(tracks, 'de'), -1);
  assert.equal(pickAudioIndex([{ lang: '', label: 'Japanese 5.1' }], 'ja'), 0);
  assert.equal(pickAudioIndex(tracks, ''), -1);
  assert.equal(pickAudioIndex(tracks, 'und'), -1);
});

// ---------- L069 encodings ----------
test('detectEncoding: BOMs, BOM-less UTF-16, valid UTF-8', () => {
  assert.equal(detectEncoding(bytes('utf8bom')), 'utf-8');
  assert.equal(detectEncoding(bytes('utf16le')), 'utf-16le');
  assert.equal(detectEncoding(bytes('utf16be')), 'utf-16be');
  assert.equal(detectEncoding(bytes('utf16le_nobom')), 'utf-16le');
  assert.equal(detectEncoding(new TextEncoder().encode('Plain ASCII line\n1\n00:00:01,000 --> 00:00:02,000\nHi\n')), 'utf-8');
  assert.equal(detectEncoding(new TextEncoder().encode('Café 你好')), 'utf-8');
});
test('decodeSubtitleBytes decodes every legacy encoding correctly with a language hint', () => {
  assert.equal(decodeSubtitleBytes(bytes('gbk'), '', 'zh'), fx.gbk.text);
  assert.equal(decodeSubtitleBytes(bytes('big5'), '', 'zh-TW'), fx.big5.text);
  assert.equal(decodeSubtitleBytes(bytes('sjis'), '', 'ja'), fx.sjis.text);
  assert.equal(decodeSubtitleBytes(bytes('euckr'), '', 'ko'), fx.euckr.text);
  assert.equal(decodeSubtitleBytes(bytes('w1251'), '', 'ru'), fx.w1251.text);
  assert.equal(decodeSubtitleBytes(bytes('w1252'), '', 'fr'), fx.w1252.text);
  assert.equal(decodeSubtitleBytes(bytes('w1253'), '', 'el'), fx.w1253.text);
  assert.equal(decodeSubtitleBytes(bytes('utf16le')), fx.utf16le.text);
  assert.equal(decodeSubtitleBytes(bytes('utf16be')), fx.utf16be.text);
  assert.equal(decodeSubtitleBytes(bytes('utf8bom')), fx.utf8bom.text); // BOM stripped
});
test('decodeSubtitleBytes detects without any language hint', () => {
  assert.equal(detectEncoding(bytes('w1252')), 'windows-1252');
  assert.equal(detectEncoding(bytes('w1251')), 'windows-1251');
  assert.equal(detectEncoding(bytes('sjis')), 'shift_jis');
  assert.equal(detectEncoding(bytes('euckr')), 'euc-kr');
  assert.equal(detectEncoding(bytes('big5')), 'big5');
  assert.equal(detectEncoding(bytes('gbk')), 'gb18030');
  assert.equal(decodeSubtitleBytes(bytes('sjis')), fx.sjis.text);
  assert.equal(decodeSubtitleBytes(bytes('gbk')), fx.gbk.text);
});
test('manual override wins; a bad override falls back instead of throwing; hint mismatch is verified', () => {
  assert.equal(decodeSubtitleBytes(bytes('big5'), 'big5'), fx.big5.text);
  assert.doesNotThrow(() => decodeSubtitleBytes(bytes('gbk'), 'not-an-encoding'));
  // wrong language hint (says Japanese, is Cyrillic): the hint's decode is junk-checked, then statistics take over
  assert.equal(decodeSubtitleBytes(bytes('w1251'), '', 'ja'), fx.w1251.text);
  assert.equal(lang2encoding('ja'), 'shift_jis'); assert.equal(lang2encoding('zht'), 'big5'); assert.equal(lang2encoding('en'), '');
});
test('a decoded non-UTF-8 file converts to cues', () => {
  const srt = `1\r\n00:00:01,000 --> 00:00:03,000\r\n${fx.gbk.text.split('\n')[0]}\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\n${fx.sjis.text.split('\n')[0]}\r\n`;
  const r = srtToVtt(srt);
  assert.equal(r.cues, 2); assert.ok(r.vtt.includes('你好'));
});

// ---------- L070 presets ----------
test('subtitle presets: high contrast is bold white on solid black, yellow on black; presetOf recognises them', () => {
  const byId = Object.fromEntries(SUB_PRESETS.map((p) => [p.id, p]));
  assert.deepEqual([byId.contrast.values.subColor, byId.contrast.values.subBg, byId.contrast.values.subBgOpacity, byId.contrast.values.subBold], ['#ffffff', 'box', 1, true]);
  assert.deepEqual([byId.yellow.values.subColor, byId.yellow.values.subBg, byId.yellow.values.subBgOpacity], ['#ffe27a', 'box', 1]);
  for (const p of SUB_PRESETS) assert.equal(presetOf({ ...p.values }), p.id);
  assert.equal(presetOf({ ...byId.contrast.values, subSize: 130 }), 'custom');
  assert.equal(presetOf({ ...byId.standard.values, subBgOpacity: 0.3 }), 'standard'); // opacity is irrelevant without a box
});

// ---------- per-source test (L081) ----------
import { testSource, declaredResources, summarize } from '../addon/sourcetest.js';
const mkManifest = (over = {}) => ({ id: 'x', name: 'X', version: '1', types: ['movie'], resources: ['catalog', 'meta', 'stream', 'subtitles'], catalogs: [{ type: 'movie', id: 'top', name: 'Top' }], ...over });
const okFns = (over = {}) => ({
  manifest: async () => ({ manifest: mkManifest() }),
  catalog: async () => [{ id: 'mock:1', type: 'movie', name: 'A' }],
  meta: async () => ({ name: 'A' }), stream: async () => [{ url: 'u' }], subtitles: async () => [],
  describe: (e) => e.message, ...over,
});
test('source test: every declared resource is tried and reported', async () => {
  const r = await testSource({ url: 'https://x/manifest.json', manifest: mkManifest() }, okFns());
  assert.deepEqual(r.map((x) => [x.resource, x.status]), [['manifest', 'pass'], ['catalog', 'pass'], ['meta', 'pass'], ['stream', 'pass'], ['subtitles', 'pass']]);
  assert.match(r.find((x) => x.resource === 'stream').detail, /1 stream/);
  assert.equal(summarize(r), 'All 5 checks passed');
});
test('source test: a failing resource is marked failed with its reason, others continue', async () => {
  const r = await testSource({ url: 'u', manifest: mkManifest() }, okFns({ stream: async () => { throw new Error('HTTP 500'); } }));
  const s = r.find((x) => x.resource === 'stream');
  assert.equal(s.status, 'fail'); assert.equal(s.detail, 'HTTP 500');
  assert.equal(r.find((x) => x.resource === 'subtitles').status, 'pass');
  assert.match(summarize(r), /1 of 5 checks failed/);
});
test('source test: unreachable manifest stops early; sources without catalogs probe a known title', async () => {
  const bad = await testSource({ url: 'u' }, okFns({ manifest: async () => { throw new Error('offline'); } }));
  assert.deepEqual(bad.map((x) => x.status), ['fail']);
  const noCat = mkManifest({ resources: ['stream'], catalogs: [] });
  const r = await testSource({ url: 'u', manifest: noCat }, okFns({ manifest: async () => ({ manifest: noCat }) }));
  assert.deepEqual(r.map((x) => [x.resource, x.status]), [['manifest', 'pass'], ['stream', 'pass']]);
  assert.deepEqual(declaredResources(noCat), ['stream']);
});
test('source test: sources that only take other id prefixes skip instead of failing', async () => {
  const m = mkManifest({ resources: [{ name: 'stream', types: ['movie'], idPrefixes: ['kitsu:'] }], catalogs: [] });
  const r = await testSource({ url: 'u', manifest: m }, okFns({ manifest: async () => ({ manifest: m }) }));
  assert.equal(r.find((x) => x.resource === 'stream').status, 'skip');
});
