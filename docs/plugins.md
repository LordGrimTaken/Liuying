# Plugins (stream scrapers)

Liuying can run small JavaScript plugins that look up streams for a title. They are listed next to the streams from your
addon sources on the Details screen, with a **plugin** badge. This is off by default. It runs on Android and, since the web player
got a sandbox and a relay, in the browser too (see "Web player" below for what differs).

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

The web player (`/watch`, Settings > Repositories and plugins) runs the same plugins with the same contract, behind a server relay
and a browser sandbox. Everything below is about the web; the Android sections above still describe Android.

### What you see

* Settings > **Repositories and plugins**: the Android risk notice (word for word), the master **Run plugins** switch (default **off**,
  stored in this browser, not synced), add a repository by manifest URL, per repository: description, consent state, search (more
  than 8 plugins), Enable all / Disable all / Update / Remove, per plugin: enable switch, description, permissions, **Test plugin**.
* Consent is per repository, asked the first time you enable one of its plugins (same wording as Android, plus a line saying
  requests go through the Liuying relay). A plugin whose update asks for new permissions is switched off again.
* Needs a **signed-in Liuying account**: the relay only serves signed-in users. Signed out, the panel says so and nothing runs.
* On the streams page plugin results sit next to the addon streams, each stream with a **plugin** badge, under the plugin's name
  (one "Plugins" filter chip instead of one chip per plugin). Plugins that found nothing are not listed. Every failure goes into
  **one** expandable line ("3 plugins failed", same grouping as Android: identical reasons and unreachable hosts merge) with a
  **Retry** button that asks only the failed plugins again.
* A stream that carries required request headers (`headers`, e.g. `Referer`) cannot be played faithfully by a browser, which cannot set
  those headers on a media request. It is marked **May not play in a browser**, names the headers, is never auto-selected, and every
  plugin stream has a **Copy link** button.
* Ids: the streams page knows IMDb ids. If you save a TMDB key (Settings > Repositories and plugins > Metadata API key, v3 key or v4
  read token; kept in this browser only, never synced, sent only to `api.themoviedb.org`, never given to a plugin) the page resolves
  IMDb to TMDB through `/find` like Android; otherwise the plugin receives the IMDb id in `tmdbId`. `tmdb:<id>` ids are used as they are.
* **Test plugin** uses the same sample titles as Android (TMDB 603 as a movie, or 1396 S1E1 for TV-only plugins) and shows
  `Pass: N streams in X.Xs` or `Failed: <error>`.

### How it runs

```
page (/watch)  --postMessage-->  <iframe sandbox="allow-scripts" src="/watch/plugins/sandbox.htm">   opaque origin, own CSP
                                      \-- dedicated Worker running plugins/engine.js                  inherits that CSP
page  --POST /api/relay-->  Worker (signed-in session, limits, public hosts only)  -->  the internet
```

1. The page (`plugins/host.js`) creates a hidden iframe with `sandbox="allow-scripts"` and **no** `allow-same-origin`: the frame has an
   opaque origin, so it cannot read the player's cookies, `localStorage`, IndexedDB, DOM or session, and cannot reach any origin.
2. The frame is served from its own static page with its own policy (generated into `_headers` by `cloudflare/scripts/build-assets.mjs`):
   `default-src 'none'; script-src 'unsafe-eval' 'unsafe-inline'; worker-src blob:; sandbox allow-scripts; base-uri 'none';
   form-action 'none'; frame-ancestors 'self'`. There is **no `connect-src`**, so nothing in the frame can open a network connection.
   `'unsafe-eval'` exists on this one page only; the `/watch` policy is unchanged (`script-src 'self'`, no `unsafe-*`), and a test
   (`cloudflare/test/headers.test.js`) fails if `unsafe-eval` appears anywhere else. The page is `sandbox.htm`, not `.html`, because
   Workers Assets redirects `.html` URLs to the extension-less path, which would miss the headers rule.
3. The frame's only script is a 20-line starter. It starts `engine.js` (sent by the page as text) in a **dedicated Worker**
   and forwards messages between the Worker and the page. The plugin runs only in that Worker, which inherits the frame's CSP. The Worker
   matters for two reasons: a plugin stuck in a tight `while (true)` freezes only the Worker (the page stays responsive and the
   20-second timer still fires; the e2e test checks this), and workers have no DOM, no WebRTC and no way to navigate the frame.
   If the browser cannot start the Worker the run fails closed ("This browser cannot start the plugin sandbox").
4. `engine.js` is a browser port of `PluginPrelude.kt` (same host API and contract). The browser's own `URL`, `URLSearchParams`,
   `atob`/`btoa`, `TextEncoder`/`TextDecoder` are used; `Headers`, `AbortController`, `Buffer` and the `fetch` shim are ported.
   The shim posts the request to the page and waits for the answer. `XMLHttpRequest`, `WebSocket`, `EventSource`, `importScripts`,
   `Worker`, `indexedDB`, `caches`, `WebAssembly`, `setInterval` and `postMessage` are removed from the plugin's global (defence in
   depth: the CSP already blocks every network path).
5. **The page trusts nothing the frame sends** (a plugin can post any message). Every `fetch` request is validated by the same rules
   the relay applies (`plugins/rules.js`), headers are sanitised again, requests are counted, the response is size-checked, and the
   result is re-parsed (`parseResult`: public http(s) URLs only, 100 streams, header limits). Only messages from the frame's window
   with origin `null` are read.
6. Per-run limits are enforced by the page: **20 s** wall clock for the whole run (script download, execution, waiting), then the
   iframe is removed (which kills the Worker); **30 requests** (redirect hops reported by the relay count); 5 MB per response; 15 s per
   request; at most 3 plugins at a time and 4 downloads at a time. Scripts and manifests are downloaded through the relay too, and scripts are cached in memory for 10 minutes.
   `cheerio-without-node-native` 0.20.2 and `crypto-js` 4.2.0 are the same unmodified MIT files as Android (`plugins/vendor/`,
   `NOTICE.txt`; a test checks they are byte-identical to the Android assets) and are handed to the Worker as text when the script
   mentions `require`. Any other `require` throws.

### The relay: `POST /api/relay`

`cloudflare/src/relay.js`, rules in `site/watch/plugins/rules.js` (shared with the page). Request `{ url, method, headers, body }`
(JSON, `Content-Type: application/json`, the usual CSRF checks); answer `{ status, statusText, url, headers, body, binary, hops }`.

| rule | value |
| --- | --- |
| who | signed-in users only (session cookie; the cookie is read by the Worker and is **never** forwarded) |
| methods | `GET`, `HEAD`, `POST` (a body only on `POST`) |
| targets | `http`/`https` only; **host must be a public DNS name**: IP literals (v4, v6 and numeric forms such as `2130706433`), `localhost`, `.local`, `.internal`, `.lan`, `.home.arpa`, `.test`, single-label names and wildcard-DNS names (`nip.io`, `sslip.io`, ...) are refused; ports 80, 443, 8080, 8443 only; no `user:pass@`; the Worker's own host names, `*.workers.dev` and `liuyingapp.com` are refused (no loops); URLs up to 4096 characters. Workers cannot check where a name resolves, so a public name that points at a private address is not detected here (Cloudflare's network cannot reach private addresses, which is the real protection) |
| request headers | `Cookie`, `Cookie2`, `Authorization`, `Proxy-Authorization`, `Host`, `Content-Length`, hop-by-hop headers, `Forwarded`, `Via`, `X-Forwarded-*`, `X-Real-IP`, `True-Client-IP`, `CF-*`, `Sec-*`, `Accept-Encoding` and header names or values that are not valid are dropped (the plugin still gets its request, without them); at most 30 headers. `User-Agent` defaults to `Liuying/1.0 (Web plugin relay; ...)`, a plugin may set its own |
| redirects | followed **by hand**, at most 5, every hop re-validated like the first request (a redirect to a private or own address is refused: `Redirect refused: ...`); 301/302/303 turn a body request into a `GET` |
| request body | at most **64 KB** (UTF-8 bytes) |
| response | streamed and counted, aborted over **5 MB** (also by `Content-Length`); text is decoded with the response charset; `Set-Cookie` is removed; binary content types come back as base64 with `binary: true`, at most **512 KB** (base64 is CPU work on the free plan) |
| time | 15 s per call (including redirects) |
| per user | **120 calls per minute** (sliding window) and **3000 per day** (UTC day; one counter row in `rate_hits`), then `429` with `Retry-After` |
| privacy | nothing is stored and nothing sensitive is logged; the sites a plugin contacts see the Worker's address, not the user's |
| CPU | I/O only (the upstream body is never parsed); session lookup and the limiter are two D1 calls |

Refusals are `400 blocked_target` (target not allowed), `405` (method), `413` (body), `401`, `429`; upstream problems are `502`
(`Could not reach <host> (...)`, `too_large`, `too_many_redirects`) or `504 timeout`.

### Differences from Android

| | Android | Web |
| --- | --- | --- |
| signed in | not needed | **required** (relay) |
| engine | QuickJS | the browser's own JS engine in a sandboxed Worker; `WebAssembly` is removed to match |
| request body | 1 MB | 64 KB |
| methods | GET, HEAD, POST, PUT, PATCH, DELETE | GET, HEAD, POST |
| request headers | everything but a few | also no `Cookie`/`Authorization`, so plugins that need a bearer token or cookie for an API cannot work on the web |
| ports | any | 80, 443, 8080, 8443 |
| `*.workers.dev` hosts | allowed | refused as request targets (stream links on them are fine) |
| binary responses | decoded as text | `arrayBuffer()` is real bytes (up to 512 KB) |
| streams that need headers | player sends them | cannot play in a browser: marked, link can be copied |
| streams on public IP literals | allowed | dropped |
| DNS | own resolver with DoH fallback | Cloudflare's |

### What cannot work in a browser

* Plugins that need request **headers on the media request** (Referer, cookies, tokens): the browser's `<video>` cannot send them. The stream is listed, marked, and can be copied.
* Plugins that need a header the relay strips (Cookie, Authorization), a method other than GET/HEAD/POST, a port outside the list, or more than 64 KB of request body.
* Sites that block Cloudflare Workers' addresses (many scrapers target such sites): they answer the relay with an error page or a 403 and the plugin fails or returns nothing. This is a property of the data-centre address, not a bug.
* Streams from hosts that are HTTP only (the page is HTTPS, the browser blocks mixed content) and media the browser cannot decode.
* A hostile plugin can still use its 30 requests per run and 3000 per day to make the relay fetch public pages; it cannot reach private addresses, the user's session or any other origin.

### Tests

`cloudflare/test/relay.test.js` (validator tables, header stripping, redirect re-validation, size and time caps, binary, auth, CSRF,
rate limits, session cookie never forwarded), `cloudflare/test/headers.test.js` (generated policies), `site/watch/test/plugins.test.mjs`
(manifest and result parsing, request validation, the engine in a fake worker including `require` of both modules, file integrity) and
`site/watch/test/e2e/plugins_e2e.py` (Playwright against `wrangler dev`, with a mock relay and fixture plugins under
`plugin-fixtures/`: settings flow, consent, Test, streams page, grouped failures and Retry, the 30-request cap, a tight-loop plugin
stopped at 20 s while the page keeps ticking, and a hostile plugin whose every escape attempt is checked).

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

## Verification status (Android)

See the build notes in the commit message and the project report: automated tests cover the URL rules, the HTTP door,
the contract and the engine on the JVM. The Android build of the engine, the settings UI and the Details list are not
exercised on a device by the test suite.
