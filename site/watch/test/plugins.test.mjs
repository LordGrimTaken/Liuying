// Plugin logic: manifest/result parsing, request validation and the sandbox engine (run in a vm context with a fake worker global).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import {
  parseManifest, parseTarget, supportsType, sampleFor, parseResult, toStremioStream, groupFailures, streamUrlSafe, deriveId, resolveUrl, RISK_NOTICE, SAMPLE_TV,
} from '../plugins/contract.js';
import { validateFetchSpec } from '../plugins/host.js';
import { LIMITS } from '../plugins/rules.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const plug = path.join(here, '../plugins');
const read = (f) => fs.readFileSync(path.join(plug, f), 'utf8');

// ---------- manifests ----------
test('parseManifest: Nuvio scrapers format, scripts resolved against the manifest URL', () => {
  const url = 'https://raw.githubusercontent.com/o/r/refs/heads/main/manifest.json';
  const r = parseManifest(url, JSON.stringify({
    name: 'All-in-One', version: '1.0.0', scrapers: [
      { id: 'vidlink', name: 'VidLink', version: '2.0', author: 'A', description: 'd', supportedTypes: ['Movie', 'tv', 'movie'], filename: 'providers/vidlink.js' },
      { name: 'No id', filename: 'providers/noid.js' },
      { id: 'dup', filename: 'a.js' }, { id: 'dup', filename: 'b.js' },
      { id: 'bad' }, { id: 'abs', filename: 'https://cdn.example.com/x.js' }, { id: 'ftp', filename: 'ftp://x.example.com/x.js' }, 5, null,
    ],
  }));
  assert.equal(r.ok, true);
  const m = r.manifest;
  assert.deepEqual([m.id, m.name, m.version, m.author], ['raw.githubusercontent.com/o/r', 'All-in-One', '1.0.0', null]);
  assert.deepEqual(m.plugins.map((p) => p.id), ['vidlink', 'noid', 'dup', 'abs']);
  assert.equal(m.plugins[0].scriptUrl, 'https://raw.githubusercontent.com/o/r/refs/heads/main/providers/vidlink.js');
  assert.deepEqual(m.plugins[0].types, ['movie', 'tv']);
  assert.equal(m.plugins[0].description, 'd (by A)');
  assert.equal(m.plugins[1].name, 'No id'); // an entry without an id gets one from the file name
  assert.equal(m.plugins[3].scriptUrl, 'https://cdn.example.com/x.js');
});

test('parseManifest: plugins format and failures', () => {
  const ok = parseManifest('https://example.org/m/manifest.json', { id: 'r1', name: 'R', plugins: [{ id: 'p', scriptUrl: 'p.js', types: ['movie'], permissions: ['network', 'network', 'storage'] }, { id: 'q', script: '../q.js' }] });
  assert.equal(ok.ok, true);
  assert.deepEqual(ok.manifest.plugins.map((p) => [p.id, p.scriptUrl]), [['p', 'https://example.org/m/p.js'], ['q', 'https://example.org/q.js']]);
  assert.deepEqual(ok.manifest.plugins[0].permissions, ['network', 'storage']);
  assert.match(parseManifest('https://e.org/m.json', 'nope').error, /valid JSON/);
  assert.match(parseManifest('https://e.org/m.json', '[]').error, /JSON object/);
  assert.match(parseManifest('https://e.org/m.json', '{"plugins":[]}').error, /missing an id or a name/);
  const many = parseManifest('https://e.org/m.json', { name: 'x', scrapers: Array.from({ length: 300 }, (_, i) => ({ id: `s${i}`, filename: `${i}.js` })) });
  assert.equal(many.manifest.plugins.length, 200);
  assert.equal(deriveId('https://example.org/a/b/manifest.json'), 'example.org/a/b');
  assert.equal(resolveUrl('https://e.org/a/', 'javascript:alert(1)'), null);
});

// ---------- which titles ----------
test('parseTarget: imdb and tmdb ids, series need season and episode', () => {
  assert.deepEqual(parseTarget('movie', 'tt0133093'), { rawId: 'tt0133093', mediaType: 'movie', season: null, episode: null, isImdb: true });
  assert.deepEqual(parseTarget('series', 'tt0944947:1:2'), { rawId: 'tt0944947', mediaType: 'tv', season: 1, episode: 2, isImdb: true });
  assert.deepEqual(parseTarget('series', 'tmdb:1396:3:4'), { rawId: '1396', mediaType: 'tv', season: 3, episode: 4, isImdb: false });
  assert.equal(parseTarget('movie', 'tmdb:603').rawId, '603');
  for (const [t, id] of [['series', 'tt0944947'], ['series', 'tt0944947:x:1'], ['movie', 'kitsu:123'], ['movie', 'mock:m1'], ['channel', 'tt0133093'], ['movie', ''], ['movie', 'tt12']]) assert.equal(parseTarget(t, id), null, `${t} ${id}`);
});

test('supportsType and sample titles', () => {
  assert.equal(supportsType([], 'movie'), true);
  assert.equal(supportsType(['tv'], 'movie'), false);
  assert.equal(supportsType(['series'], 'tv'), true);
  assert.equal(supportsType(['movie'], 'tv'), false);
  assert.deepEqual(sampleFor(['movie', 'tv']), { tmdbId: '603', mediaType: 'movie' });
  assert.deepEqual(sampleFor(['tv']), SAMPLE_TV);
});

// ---------- results ----------
test('parseResult: arrays and {streams}, unsafe and bad entries dropped, text trimmed, headers filtered', () => {
  const r = parseResult(JSON.stringify([
    { url: ' https://cdn.example.com/a.m3u8 ', name: ' A ', title: 'T', quality: '1080p', headers: { Referer: 'https://x.example.com/', 'Bad:Name': 'x', Ok: 'a\nb', Num: 5 } },
    { url: 'http://127.0.0.1/x.mp4' }, { url: 'http://192.168.1.4/x.mp4' }, { url: 'https://localhost/x' }, { url: 'ftp://x.example.com/f' }, { url: 'javascript:alert(1)' },
    { url: 'https://user:pw@cdn.example.com/x' }, { name: 'nourl' }, 'str', null, { url: 'https://cdn.example.org/b.mp4', quality: 720 },
    { url: 'https://abc.workers.dev/v.mp4' },
  ]), 'Plug');
  assert.equal(r.ok, true);
  assert.deepEqual(r.streams.map((s) => s.url), ['https://cdn.example.com/a.m3u8', 'https://cdn.example.org/b.mp4', 'https://abc.workers.dev/v.mp4']);
  assert.deepEqual(r.streams[0], { name: 'A', title: 'T', url: 'https://cdn.example.com/a.m3u8', quality: '1080p', headers: { Referer: 'https://x.example.com/' } });
  assert.equal(r.streams[1].name, 'Plug');
  assert.equal(r.streams[1].quality, '720');
  assert.equal(parseResult('{"streams":[{"url":"https://a.example.com/x"}]}', 'P').streams.length, 1);
  assert.deepEqual(parseResult('null', 'P'), { ok: true, streams: [] });
  assert.equal(parseResult('5', 'P').ok, false);
  assert.equal(parseResult('{"a":1}', 'P').ok, false);
  assert.equal(parseResult('not json', 'P').ok, false);
  assert.equal(parseResult(JSON.stringify(Array.from({ length: 150 }, (_, i) => ({ url: `https://a.example.com/${i}` }))), 'P').streams.length, 100);
});

test('streamUrlSafe', () => {
  assert.equal(streamUrlSafe('https://cdn.example.com:9000/v.mp4'), true);
  for (const u of ['http://10.0.0.1/v', 'http://[::1]/v', 'http://printer.local/v', 'blob:https://a.example.com/x', '', 'https://a.example.com/' + 'x'.repeat(5000)]) assert.equal(streamUrlSafe(u), false, u);
});

test('streams that need request headers become "may not play" streams; copying the link stays possible', () => {
  const plain = toStremioStream({ name: 'N', title: 'T', url: 'https://a.example.com/v.mp4', quality: '1080p', headers: {} });
  assert.deepEqual([plain.behaviorHints, plain.pluginHeaders, plain.description], [{}, null, 'T\n1080p']);
  const hdr = toStremioStream({ name: 'N', title: null, url: 'https://a.example.com/v.mp4', quality: null, headers: { Referer: 'x' } });
  assert.deepEqual([hdr.behaviorHints, hdr.pluginHeaders, hdr.url], [{ notWebReady: true }, { Referer: 'x' }, 'https://a.example.com/v.mp4']);
});

test('groupFailures merges identical reasons and unreachable hosts', () => {
  const g = groupFailures([['A', 'Could not reach api.x.com (the connection failed).'], ['B', 'TypeError: fetch failed: Could not reach api.x.com (other text)'], ['C', 'boom'], ['D', 'boom'], ['E', 'only one']]);
  assert.deepEqual(g.map((x) => x.headline), ['2 plugins could not reach api.x.com', '2 plugins failed: boom', 'Plugin E failed: only one']);
  assert.deepEqual(g[1].names, ['C', 'D']);
});

test('risk notice is word for word the Android text', () => {
  const kt = fs.readFileSync(path.join(here, '../../../app/src/main/java/com/liuying/app/ui/settings/YouScreen.kt'), 'utf8');
  const piece = (s) => { const m = kt.match(new RegExp(s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').slice(0, 60))); return !!m; };
  assert.ok(piece(RISK_NOTICE.slice(0, 80)));
  const flat = kt.replace(/"\s*\+\s*\n\s*"/g, '');
  assert.ok(flat.includes(RISK_NOTICE), 'Android YouScreen.kt contains the identical notice');
});

// ---------- request validation in the page ----------
test('validateFetchSpec: what a plugin may ask the relay for', () => {
  const ok = validateFetchSpec({ url: 'https://api.example.com/x?a=1', method: 'post', headers: { Cookie: 'a=b', Referer: 'https://api.example.com/', 'X-Forwarded-For': '1.1.1.1' }, body: 'k=v' });
  assert.deepEqual(ok, { ok: true, req: { url: 'https://api.example.com/x?a=1', method: 'POST', headers: { referer: 'https://api.example.com/' }, body: 'k=v' } });
  for (const bad of [
    null, [], { url: 'http://127.0.0.1/' }, { url: 'https://localhost/' }, { url: 'https://10.0.0.1/' }, { url: 'https://x.example.com:22/' }, { url: 'https://www.liuyingapp.com/api/auth/me' },
    { url: 'file:///etc/passwd' }, { url: 'https://a.example.com/', method: 'PUT' }, { url: 'https://a.example.com/', method: 'GET', body: 'x' },
    { url: 'https://a.example.com/', method: 'POST', body: 'x'.repeat(LIMITS.MAX_REQUEST_BODY_BYTES + 1) }, { url: 'https://a.example.com/', method: 'POST', body: { a: 1 } }, {},
  ]) assert.equal(validateFetchSpec(bad).ok, false, JSON.stringify(bad)?.slice(0, 80));
});

// ---------- the engine (plugins/engine.js) in a fake worker global ----------
function makeEngine({ onPost } = {}) {
  const listeners = [];
  const posted = [];
  const ctx = {
    postMessage: (m) => { posted.push(m); onPost?.(m, send); },
    addEventListener: (t, f) => { if (t === 'message') listeners.push(f); },
    setTimeout, clearTimeout, URL, URLSearchParams, TextEncoder, TextDecoder, atob, btoa, queueMicrotask, Uint8Array, ArrayBuffer,
  };
  ctx.self = ctx;
  vm.createContext(ctx);
  vm.runInContext(read('engine.js'), ctx, { filename: 'engine.js' });
  function send(data) { for (const f of listeners) f({ data }); }
  return { ctx, posted, send, run: (m) => send({ type: 'run', tmdbId: '603', mediaType: 'movie', season: null, episode: null, modules: {}, ...m }) };
}
const vendor = () => ({ 'cheerio-without-node-native': read('vendor/cheerio.js'), 'crypto-js': read('vendor/crypto-js.js') });
function until(posted, type, ms = 2000) {
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    const tick = () => {
      const m = posted.find((x) => x.type === type || (type === 'end' && (x.type === 'result' || x.type === 'reject')));
      if (m) resolve(m); else if (Date.now() - t0 > ms) reject(new Error(`no ${type} message; got ${JSON.stringify(posted.map((p) => p.type))}`)); else setTimeout(tick, 5);
    };
    tick();
  });
}
const reply = (res) => (m, send) => { if (m.type === 'fetch') send({ type: 'fetch-result', id: m.id, ok: true, res: { statusText: 'OK', headers: {}, ...res(m.spec) } }); };

test('engine: announces itself and answers with getStreams() output as JSON', async () => {
  const e = makeEngine();
  assert.equal(e.posted[0].type, 'engine-ready');
  e.run({ script: "module.exports = { getStreams: async (id, type, s, ep) => [{ url: 'https://a.example.com/' + id + '/' + type + '/' + s + '/' + ep, name: 'x' }] };", tmdbId: '1396', mediaType: 'tv', season: 2, episode: 5 });
  const m = await until(e.posted, 'end');
  assert.equal(m.type, 'result');
  assert.equal(JSON.parse(m.json)[0].url, 'https://a.example.com/1396/tv/2/5');
});

test('engine: movies get undefined season and episode; exports.getStreams and module.exports.getStreams both work', async () => {
  let e = makeEngine();
  e.run({ script: 'exports.getStreams = function (a, b, s, ep) { return [{ url: "https://a.example.com/" + typeof s + typeof ep }]; };' });
  assert.equal(JSON.parse((await until(e.posted, 'end')).json)[0].url, 'https://a.example.com/undefinedundefined');
  e = makeEngine();
  e.run({ script: 'module.exports.getStreams = () => ({ streams: [] });' });
  assert.equal(JSON.parse((await until(e.posted, 'end')).json).streams.length, 0);
});

test('engine: fetch goes out as a message and the reply becomes a Response', async () => {
  const e = makeEngine({ onPost: reply((spec) => ({ status: 200, url: spec.url, body: JSON.stringify({ q: spec.body }), headers: { 'content-type': 'application/json', 'x-a': '1' } })) });
  e.run({ script: `module.exports = { getStreams: async () => {
    const res = await fetch('https://api.example.com/find', { method: 'POST', headers: new Headers({ 'X-K': 'v' }), body: new URLSearchParams({ a: '1', b: 'x y' }) });
    const j = await res.json(); const c = res.clone();
    return [{ url: 'https://a.example.com/' + res.status + res.ok + res.headers.get('X-A') + '/' + encodeURIComponent(j.q) + '/' + (await c.text()).length }];
  } };` });
  const m = await until(e.posted, 'end');
  assert.equal(m.type, 'result', m.message);
  const f = e.posted.find((x) => x.type === 'fetch');
  assert.deepEqual(JSON.parse(JSON.stringify(f.spec)), { url: 'https://api.example.com/find', method: 'POST', headers: { 'x-k': 'v', 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }, body: 'a=1&b=x+y' });
  assert.match(JSON.parse(m.json)[0].url, /^https:\/\/a\.example\.com\/200true1\/a%3D1%26b%3Dx%2By\/17$/);
});

test('engine: a failed fetch rejects like a network error; binary bodies decode to bytes', async () => {
  let e = makeEngine({ onPost: (m, send) => { if (m.type === 'fetch') send({ type: 'fetch-result', id: m.id, ok: false, error: 'Could not reach down.example.com (the connection failed).' }); } });
  e.run({ script: "module.exports.getStreams = async () => { try { await fetch('https://down.example.com/'); } catch (err) { throw new Error(err.name + ': ' + err.message); } };" });
  assert.equal((await until(e.posted, 'end')).message, 'TypeError: fetch failed: Could not reach down.example.com (the connection failed).');
  e = makeEngine({ onPost: reply(() => ({ status: 200, url: 'https://a.example.com/b', body: Buffer.from([1, 2, 250]).toString('base64'), binary: true })) });
  e.run({ script: "module.exports.getStreams = async () => { const b = new Uint8Array(await (await fetch('https://a.example.com/b')).arrayBuffer()); return [{ url: 'https://a.example.com/' + Array.from(b).join('-') }]; };" });
  assert.equal(JSON.parse((await until(e.posted, 'end')).json)[0].url, 'https://a.example.com/1-2-250');
});

test('engine: errors, missing export, bad script and unreadable results are reported, once', async () => {
  const cases = [
    ['module.exports.getStreams = async () => { throw new Error("boom"); };', /^boom$/],
    ['module.exports.getStreams = () => { throw new RangeError("sync"); };', /^RangeError: sync$/],
    ['module.exports = {};', /does not export getStreams/],
    ['this is not javascript', /failed to load/],
    ['module.exports.getStreams = async () => { const a = {}; a.a = a; return a; };', /could not be read/],
  ];
  for (const [script, re] of cases) {
    const e = makeEngine();
    e.run({ script });
    const m = await until(e.posted, 'end');
    assert.equal(m.type, 'reject', script);
    assert.match(m.message, re);
    await new Promise((r) => setTimeout(r, 120));
    assert.equal(e.posted.filter((x) => x.type === 'reject' || x.type === 'result').length, 1, 'answered once');
  }
});

test('engine: a plugin that goes quiet without answering is reported with what it waited for', async () => {
  let e = makeEngine();
  e.run({ script: 'module.exports.getStreams = () => new Promise(() => {});' });
  assert.match((await until(e.posted, 'end')).message, /stopped without giving an answer\.$/);
  e = makeEngine({ onPost: reply(() => ({ status: 503, url: 'https://down.example.com/', body: '' })) });
  e.run({ script: 'module.exports.getStreams = async () => { await fetch("https://down.example.com/"); await new Promise(() => {}); };' });
  assert.match((await until(e.posted, 'end')).message, /last request: down\.example\.com answered HTTP 503/);
});

test('engine: timers are counted, capped and string callbacks are ignored; no setInterval', async () => {
  const e = makeEngine();
  e.run({ script: `module.exports.getStreams = async () => {
    const t0 = Date.now(); await new Promise((r) => setTimeout(r, 30));
    const id = setTimeout(() => { throw new Error('cleared'); }, 10); clearTimeout(id);
    return [{ url: 'https://a.example.com/' + (Date.now() - t0 >= 25) + typeof setInterval + setTimeout('alert(1)', 1) }];
  };` });
  assert.equal(JSON.parse((await until(e.posted, 'end')).json)[0].url, 'https://a.example.com/trueundefined0');
});

test('engine: the host API a plugin expects exists; ways out of the sandbox do not', async () => {
  const e = makeEngine();
  e.run({ script: `module.exports.getStreams = async () => {
    const have = ['fetch','atob','btoa','URL','URLSearchParams','setTimeout','Buffer','TextEncoder','TextDecoder','AbortController','Headers','queueMicrotask','require','module','exports','console','SCRAPER_SETTINGS'].filter((n) => typeof globalThis[n] === 'undefined');
    const gone = ['XMLHttpRequest','WebSocket','EventSource','importScripts','indexedDB','caches','Worker','SharedWorker','postMessage','setInterval','WebAssembly'].filter((n) => typeof globalThis[n] !== 'undefined');
    const b = Buffer.from('héllo').toString('hex') + '|' + Buffer.from('aGk=', 'base64').toString() + '|' + Buffer.from('hi').toString('base64url');
    const u = new URL('../x?y=1#z', 'https://a.example.com/p/q/r'); const h = new Headers({ A: '1' }); h.append('a', '2');
    const ac = new AbortController(); let ab = ''; ac.signal.addEventListener('abort', () => { ab = 'aborted'; }); ac.abort();
    console.log('discarded');
    return [{ url: 'https://a.example.com/', name: JSON.stringify({ have, gone, b, u: u.href, h: h.get('a'), ab, btoa: btoa('hi'), atob: atob('aGk='), nav: typeof navigator.userAgent }) }];
  };` });
  const m = await until(e.posted, 'end');
  assert.equal(m.type, 'result', m.message);
  const info = JSON.parse(JSON.parse(m.json)[0].name);
  assert.deepEqual(info, { have: [], gone: [], b: '68c3a96c6c6f|hi|aGk', u: 'https://a.example.com/p/x?y=1#z', h: '1, 2', ab: 'aborted', btoa: 'aGk=', atob: 'hi', nav: 'string' });
});

test('engine: require() gives exactly the two bundled modules (and buffer); everything else is refused', async () => {
  const e = makeEngine();
  e.run({ modules: vendor(), script: `module.exports.getStreams = async () => {
    const CryptoJS = require('crypto-js'); const cheerio = require('cheerio-without-node-native'); const { Buffer: B } = require('buffer');
    const $ = cheerio.load('<ul><li class="a"><a href="/x">One</a></li><li>Two</li></ul>');
    let refused = [];
    for (const n of ['fs', 'child_process', 'http', 'net', 'axios', 'node:fs', '../../etc/passwd', '__proto__', 'constructor']) { try { require(n); } catch (err) { refused.push(/not available/.test(err.message)); } }
    return [{ url: 'https://a.example.com/', name: JSON.stringify({ md5: CryptoJS.MD5('hello').toString(), sha: CryptoJS.SHA256('abc').toString().slice(0, 16), links: $('li.a a').attr('href'), n: $('li').length, text: $('li').last().text(), buf: B === Buffer, refused }) }];
  };` });
  const m = await until(e.posted, 'end', 8000);
  assert.equal(m.type, 'result', m.message);
  const info = JSON.parse(JSON.parse(m.json)[0].name);
  assert.deepEqual(JSON.parse(JSON.stringify(info)), { md5: '5d41402abc4b2a76b9719d911017c592', sha: 'ba7816bf8f01cfea', links: '/x', n: 2, text: 'Two', buf: true, refused: Array(9).fill(true) });
});

test('engine: a module is only offered when the page sent it', async () => {
  const e = makeEngine();
  e.run({ modules: {}, script: "module.exports.getStreams = async () => { require('crypto-js'); };" });
  assert.match((await until(e.posted, 'end')).message, /require\('crypto-js'\) is not available/);
});

test('engine: ignores a second run and unknown messages', async () => {
  const e = makeEngine();
  e.send({ type: 'bogus' }); e.send(null); e.send('x'); e.send({ type: 'fetch-result', id: 99, ok: true });
  e.run({ script: 'module.exports.getStreams = async () => [];' });
  await until(e.posted, 'end');
  e.run({ script: 'module.exports.getStreams = async () => [{url:"https://b.example.com/"}];' });
  await new Promise((r) => setTimeout(r, 60));
  assert.equal(e.posted.filter((x) => x.type === 'result').length, 1);
});

// ---------- files ----------
test('vendored modules are byte-identical to the Android assets and carry their license notice', () => {
  for (const f of ['cheerio.js', 'crypto-js.js']) {
    const a = fs.readFileSync(path.join(plug, 'vendor', f));
    const b = fs.readFileSync(path.join(here, '../../../app/src/main/resources/liuying-plugin-modules', f));
    assert.equal(crypto.createHash('sha256').update(a).digest('hex'), crypto.createHash('sha256').update(b).digest('hex'), f);
    assert.match(a.toString('utf8', 0, 200), /\bMIT\b/);
  }
  assert.match(read('vendor/NOTICE.txt'), /cheerio-without-node-native 0\.20\.2[\s\S]*crypto-js 4\.2\.0[\s\S]*MIT License/);
});

test('sandbox.htm: one inline starter script, no external resource, messages only from the parent', () => {
  const html = read('sandbox.htm');
  assert.equal((html.match(/<script/g) || []).length, 1);
  assert.ok(!/(src|href)\s*=/.test(html), 'no external references');
  assert.match(html, /ev\.source !== P\) return;/);
  assert.ok(!/localStorage|document\.cookie|eval\(/.test(html));
});

test('the browser files import nothing from outside the web player (no third-party code is loaded)', () => {
  for (const f of ['contract.js', 'host.js', 'rules.js', 'service.js']) {
    for (const m of read(f).matchAll(/from '([^']+)'/g)) assert.ok(m[1].startsWith('.'), `${f} imports ${m[1]}`);
  }
  assert.ok(!/importScripts\s*\(|https?:\/\//.test(read('engine.js').replace(/\/\*[\s\S]*?\*\//, '').replace(/\/\/.*$/gm, '').replace(/'[^'\n]*https?:\/\/[^'\n]*'/g, "''")), 'engine loads nothing');
});
