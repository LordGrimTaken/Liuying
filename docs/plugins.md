# Plugins (stream scrapers)

Liuying can run small JavaScript plugins that look up streams for a title. They are listed next to the streams from your
addon sources on the Details screen, with a **plugin** badge. This is off by default and Android only.

> Plugins are third-party code. Liuying does not review, host or endorse them or what they link to. Use them at your own risk.

## Turning it on

All of these must be true before a plugin runs for a title:

1. **You > Repositories > Run plugins** (master switch, default off) is on.
2. The plugin's repository has been **allowed** (the consent dialog was accepted, stored per repository).
3. The plugin itself is **enabled** (switch in its row; plugins start disabled and re-disable themselves if an update asks for new permissions).
4. The plugin supports the title's type (`supportedTypes` of `movie` and/or `tv`; empty means both).

Each plugin row has a **Test plugin** button (needs 1 and 2). It runs `getStreams` once with a sample title (TMDB 603, The
Matrix, as a movie; TMDB 1396 season 1 episode 1 for TV-only plugins) and shows pass or fail, the stream count, the time, and
the error text on failure.

## Repository manifest

The existing "scrapers" format is used:

```json
{ "name": "My Repo", "version": "1.0.0",
  "scrapers": [ { "id": "example", "name": "Example", "version": "1.0.0", "supportedTypes": ["movie", "tv"], "filename": "providers/example.js" } ] }
```

`filename` is resolved relative to the manifest URL. Only http(s) script addresses are accepted.

## Plugin contract

The script is a plain (non-module) script. It must export `getStreams` the CommonJS way:

```js
module.exports = { getStreams };   // or: module.exports.getStreams = ...; or: exports.getStreams = ...

async function getStreams(tmdbId, mediaType, season, episode) {
  const res = await fetch('https://api.example.com/find?id=' + encodeURIComponent(tmdbId) + '&type=' + mediaType);
  const data = await res.json();
  return data.items.map(i => ({ name: 'Example', title: i.title, url: i.url, quality: '1080p', headers: { Referer: 'https://example.com/' } }));
}
```

Arguments:

| argument | value |
| --- | --- |
| `tmdbId` | string. The TMDB id when it can be resolved, otherwise the **IMDb id** (`tt...`), see below |
| `mediaType` | `"movie"` or `"tv"` |
| `season`, `episode` | numbers for `tv`; `undefined` for movies |

Return a value (or a Promise of one): an array of stream objects, or `{ "streams": [...] }`, or `null`/`[]` for nothing.

| field | required | notes |
| --- | --- | --- |
| `url` | yes | public http(s) address of the video. Entries with other schemes or private addresses are dropped |
| `name` | no | defaults to the plugin name |
| `title` | no | secondary line |
| `quality` | no | text such as `1080p`; shown and used for the quality badges |
| `headers` | no | request headers the player must send (for example `Referer`); at most 20 |

At most 100 streams are kept. Throwing or rejecting marks the plugin as failed with your error message.

### Id resolution

Details screens know titles by IMDb id. To produce a TMDB id, Liuying calls TMDB `/find` with the **Metadata API key** from
You > Integrations (a v3 key or a v4 read token; it stays on the host and is never given to a plugin). If no key is set, the
lookup fails, or the id is not an IMDb id, **the plugin receives the IMDb id in `tmdbId`** instead. Plugins that need a
numeric TMDB id should check `/^tt/.test(tmdbId)` and look it up themselves. Titles with other id schemes (for example `kitsu:`)
are not sent to plugins.

## What a plugin can use

The script runs in a fresh QuickJS (ES2020) engine per run. It has the language built-ins (`Promise`, `JSON`, `Map`,
`RegExp`, `async/await`, ...) plus only this host API:

- `fetch(url, { method, headers, body, signal })` returning a Response-like object: `ok`, `status`, `statusText`, `url`, `headers` (a `Headers`), `text()`, `json()`, `arrayBuffer()` (UTF-8 bytes of the decoded text, so not for binary data), `clone()`. `body` is a string or `URLSearchParams`. Response bodies are decoded as text (no binary). A `signal` from `AbortController` / `AbortSignal.timeout()` cancels the call.
- `console.log/info/warn/error/debug` (output is discarded; nothing leaves the sandbox)
- `atob`, `btoa` (Latin-1 text, as in browsers), `TextEncoder` / `TextDecoder` (UTF-8, Latin-1), `Buffer` (a subset: `from`, `alloc`, `concat`, `isBuffer`, `byteLength`, `toString` with utf8/hex/base64/base64url/latin1)
- `URL`, `URLSearchParams`, `Headers`, `AbortController`, `AbortSignal`, `queueMicrotask`, `navigator.userAgent`, `SCRAPER_SETTINGS` (an empty object)
- `setTimeout` / `clearTimeout` (up to 5 s per timer; counts against the run time; there is no `setInterval`)
- `module`, `exports`, and `require` for exactly two bundled npm modules, loaded only when required: **`cheerio-without-node-native`** 0.20.2 (HTML parsing, the jQuery-like API) and **`crypto-js`** 4.2.0 (AES, MD5, SHA, HMAC, encoders). Both are MIT licensed and shipped unmodified (esbuild bundles) in `app/src/main/resources/liuying-plugin-modules/`. `require('buffer')` returns `{ Buffer }`. Any other `require` throws a clear error.
- Small language gaps are filled when the engine lacks them (`String.prototype.replaceAll`, `Array.prototype.at`, `Object.fromEntries`, `Promise.allSettled/any`).

Not available: `XMLHttpRequest`, `WebSocket`, `localStorage`, `process`, `WebAssembly`, `FormData`, `Blob`, `setInterval`, `crypto.getRandomValues` (so `CryptoJS.AES.encrypt` with a passphrase, which needs a random salt, fails; decrypting and key/iv encryption work), Node built-ins other than `buffer`, and any Android/Java access.

Text crossing the engine boundary is passed as ASCII-escaped JSON (plugin results and `fetch` requests/responses), and non-BMP characters in the script itself are written as surrogate escapes, because the engine's UTF-8 bridge is not safe for emoji; plugins do not notice.

## Limits and sandbox rules

| rule | value |
| --- | --- |
| wall clock for the whole run (script download, execution, waiting) | **20 s**, then the run is abandoned and reported as failed |
| engine memory | 48 MB heap, 1 MB stack; exceeding it fails the run |
| web requests per run | **30** (redirect hops count) |
| per request | 15 s; response at most **5 MB**; request body at most 1 MB |
| redirects | at most **5**, each hop re-checked |
| schemes | `http` and `https` only |
| targets | no `localhost`, loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7), link-local (169.254/16, fe80::/10), carrier-grade NAT, multicast or reserved addresses, no `.local`/`.internal`/single-label names, no odd numeric forms such as `2130706433`; no URLs with embedded credentials. Names are checked again **after DNS** so a name pointing at a private address is refused |
| cookies | none are stored or sent; `Set-Cookie` is not shown to the plugin |
| user agent | `Liuying/<version> (Android)` unless the plugin sets its own `User-Agent` header |
| concurrency | at most 3 plugins run at the same time; every plugin is a separate engine; at most 4 script downloads at once |
| after the plugin answers | requests and timers it left behind are dropped immediately; they never delay the run |

### Network resilience

Plugin traffic uses its own resolver (`ResilientDns`): up to 3 system lookups with a short backoff, a 5-minute in-memory cache shared
by all runs, a 30-second negative cache (a host that failed is not retried for 30 s; "Retry failed plugins" and "Test plugin"
clear it), and as a last resort DNS over HTTPS (Cloudflare 1.1.1.1, then Google 8.8.8.8, reached by fixed IPs; the host name is sent
to them only in this fallback). Every answer from every source passes the same private-address check. Downloaded scripts are
cached in memory for 10 minutes and shared by all runs.

A plugin's script never sees settings, tokens, source URLs, the library, or other plugins: the engine contains nothing but
the host API above, and each run starts from a blank engine (nothing carries over between runs or between plugins).

Failures are isolated per plugin: a failing, slow or crashing plugin shows a muted "Plugin X failed: reason" line in the
stream list and never affects addon streams or the app. Identical reasons are merged ("7 plugins could not reach host"), a tap
shows the full text and the plugin names, and "Retry failed plugins" asks only the ones that failed. A plugin that ends without
answering is reported with what it was waiting for ("... while still waiting for 1 web request (last request: host answered HTTP 200)").

**Known limit:** a script stuck in a tight synchronous loop is abandoned at 20 s (the caller returns and the engine is closed in
the background) but the engine's thread is only reclaimed when the engine honours the close. See "Verification status" below.

## Web player

Running plugins is **Android only for now**. A browser cannot fetch arbitrary third-party sites because of CORS, and Liuying
deliberately does not ship a server-side open proxy for plugins. The web player does not run plugins.

## Engine

`io.github.dokar3:quickjs-kt` 1.0.0-alpha13 (QuickJS via JNI; arm64-v8a, armeabi-v7a, x86, x86_64 libraries; Android minSdk 24 is
compatible). Newer releases (1.0.1 and later) are built with Kotlin 2.3+ metadata and cannot be read by this project's Kotlin 2.1.21
compiler, so alpha13 is the pin until the project's Kotlin is upgraded. R8 is off in this project, so no keep rules are needed.
Unit tests use the desktop build (`quickjs-kt-jvm`) of the same engine.

## Compatibility with the All-in-One-Nuvio repository

Checked on the JVM engine with all 66 scripts of `D3adlyRocket/All-in-One-Nuvio` (scripts are not committed: the repository
has no clear license for redistribution), each loaded and `getStreams` called once against a fake `fetch`
(`PluginCompatHarnessTest`, set `LIUYING_PLUGIN_DIR`). Result: **65 load and answer; `kisskh` ends with a TypeError that
depends on real API data** (obfuscated; no missing global was found). Before the shims, 18 plugins failed on
`require('cheerio-without-node-native')` (4khdhub, allmovieland, allwish, anidb, animedekho, animepahe, animesalt, anizone,
fibwatch, hdhub4u, hianime, moviesdrive, moviesmod, reanime, showbox, topcartoons, uhdmovies, vegamovies) and 4 on
`require('crypto-js')` (allanime, mapple, movieblast, moviebox).

Known not to work fully (loads, but a feature is missing):
- `1shows`: decrypts through `WebAssembly` and binary `arrayBuffer()`; returns nothing.
- `vixsrc`: a 1 MB bundle of Node libraries (undici, streams, `FormData`, `Blob`, `setInterval`); loads and returns an empty list on the fake data, real behaviour is device-unverified.
- `peachify`: uses `localStorage` in a guarded path; `netmirror`, `animezey`, `einthusan`, `gramcinema`, `showbox`, `torrentio` read `SCRAPER_SETTINGS` (empty, so their defaults apply).
- Plugins whose real sites block or change are outside what any runner can fix.

## Verification status

See the build notes in the commit message and the project report: automated tests cover the URL rules, the HTTP door,
the contract and the engine on the JVM. The Android build of the engine, the settings UI and the Details list are not
exercised on a device by the test suite.
