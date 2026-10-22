# Web and server audit (2026-10-22)

Scope: `server/`, `site/` (marketing + auth pages), `site/watch/` (web player). Not touched: `app/`, release script, `site/downloads/`.
Severity: H high, M medium, L low. Status: Fixed / Open / Note.

## Findings

| # | Sev | Area / file | Finding | Status |
|---|---|---|---|---|
| 1 | H | API (`server/src/app.js`) | No change-password, delete-account or session management for a username+password-only system (no recovery path, no way to evict a stolen session). Added `POST /api/auth/password`, `POST /api/auth/delete`, `GET /api/auth/sessions`, `DELETE /api/auth/sessions/:id`, `POST /api/auth/sessions/revoke-others`, `POST /api/auth/logout-all`; account page UI; `server/test/account.test.js`. Password change revokes all other sessions and rotates the current one; both password-gated endpoints are rate limited per user. | Fixed |
| 2 | H | Rate limits / IP | Behind Cloudflare + Render, `trust proxy = 1` may resolve every visitor to a shared proxy address (one bucket for all users, or a spoofable bucket). New `server/src/clientIp.js` walks `X-Forwarded-For` from the right, skipping Cloudflare/private ranges, so forged left entries are ignored. `TRUST_PROXY_HOPS` env kept for `req.protocol`. Tests in `clientip.test.js`. | Fixed (live bucket identity could not be observed; Render request logs are empty) |
| 3 | M | Login | Only per-IP and per-IP+username limits: a distributed guesser against one account was uncapped. Added per-username limiter (40 / 15 min) independent of IP. | Fixed |
| 4 | M | `rateLimit.js` | If 50k live keys were reached the map kept growing. Now evicts the oldest key. | Fixed |
| 5 | M | Logging | DATABASE_URL was never logged, but error messages from `pg` could embed a connection string. Logger now redacts the env value, its password and any `postgres://` URL; `unhandledRejection` handler uses the same redaction. | Fixed |
| 6 | M | Sessions | Unbounded sessions per user. Capped at 20 (oldest dropped). | Fixed |
| 7 | M | CSRF | Origin vs Host check was sound (www, apex and onrender.com each work only when the page and API share the host; apex is redirected to www). Added `Sec-Fetch-Site: cross-site` rejection as a second layer. Verified live: evil origin 403, apex origin 403, www 204. | Fixed |
| 8 | L | Headers | Helmet sent `X-Frame-Options: SAMEORIGIN` while CSP says `frame-ancestors 'none'`. Now `DENY`. | Fixed |
| 9 | L | Static | Unit tests under `/watch/test/` were publicly served. Now 404. Path traversal checked live (`%2e%2e`, `..`): 400/404, nothing served outside `site/`. | Fixed |
| 10 | L | `index.js` | Shutdown could hang on open keep-alive connections. 8 s hard exit added. | Fixed |
| 11 | M | Web sync (`site/watch/sync.js`) | Library was a pure union: removing a title on device B was undone by device A on its next sync. Added `libSynced` history so remote removals propagate; first sync stays a union. | Fixed |
| 12 | M | Web sync | Other devices' changes were only pulled at page load. Now pulled when the tab becomes visible and every 2 min (not during playback), and the view only re-renders if something changed. | Fixed |
| 13 | M | Progress write throttling | While playing, progress was saved every 10 s and each save triggered a server push (about 6 requests/min, close to the 60/min limit with other sync). Now at most one push per 30 s during playback plus an immediate push when playback ends. | Fixed |
| 14 | M | Player | `retries` never reset, so four transient network errors in one viewing session ended playback with a fatal error. Reset on `playing`. HLS `recoverMediaError` could loop forever; capped at 3 (second attempt swaps audio codec). | Fixed |
| 15 | L | Account | `GET /api/auth/me` and sync auth are fine; scrypt N=2^15,r=8,p=1, 16-byte salt, timingSafeEqual, dummy hash for unknown users, NFKC normalisation, parameters stored for upgrade, session token 256-bit random, stored as SHA-256. Session id rotated on login/signup/password change. | Verified, no change |
| 16 | L | SQL | All queries parameterised; the only interpolated identifiers are constant table names and a numeric LIMIT constant. | Verified |
| 17 | L | `DELETE /api/sync/all` | Deletes sources, library, progress and settings for the session user only; account and sessions kept; Origin check applies; 204. Other devices drop their synced library on next pull (finding 11). | Verified |
| 18 | L | Cookies live | `liuying_sid` is `HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=30d`; cleared with the same flags. `Cache-Control: no-store` on all `/api`. | Verified live |
| 19 | L | Render config | Service has no health check path configured (`/health` exists). Free plan sleeps after inactivity, first request is slow. | Open (dashboard setting, not in repo) |
| 20 | L | Web player | Signing out leaves sources/shelf/progress in the browser (local-first by design) and a different account signing in on the same browser merges that local data into its account. | Note |
| 21 | L | Web player | Same-origin signup/login pages and the player share one origin, so the strict CSP on `/watch` (needed for arbitrary https addons and streams) applies to the player only; account pages keep the strict policy. | Note |

## Spec acceptance (web player) - gaps

Implemented and e2e-verified earlier or in this pass: add/preview/replace sources, per-catalog visibility and order, Today with Continue Watching, browse with genre and skip paging, search with debounce and cancellation, details with seasons/episodes/watched/save, streams grouped by source with progressive loading, HLS/direct playback, audio and subtitle selection, subtitle delay, resume ask/auto, next-episode overlay, auto-select cancellable, backup export/import, typed-confirm reset, offline PWA shell, account sync.

Not possible or not done in a browser:
- DASH playback (`.mpd`): needs a vendored dash.js; streams are classified as direct and fail with the format error. Open.
- Stream `proxyHeaders.request`: a browser cannot set arbitrary request headers. Marked "use the Android app". Note.
- Torrent / debrid / plugin script execution: intentionally not implemented (plugins can be saved but never run).
- Plain `http://` sources: blocked by CSP and mixed-content; shown as Blocked. Note.
- Headphone-unplug pause, audio focus: not exposed to web pages. Note.
- Spec asks for a 5 to 30 min catalog cache; the web player caches manifests (12 h) and meta only. Open (low).

## Live checks (https://www.liuyingapp.com)

- `http://` and apex redirect with 301 to https and then to `https://www.liuyingapp.com/`; HSTS `max-age=31536000; includeSubDomains`.
- CSP strict on all pages; relaxed (https media/connect, blob worker) only under `/watch`; `upgrade-insecure-requests` on both.
- `/nope` and `/watch/nope.js` return 404 text/plain; `/watch/<route>` returns the app shell.
- `/watch/sw.js`: `Cache-Control: no-cache`, `Service-Worker-Allowed: /watch/`; manifest has `application/manifest+json`.
- `/downloads/*.apk`: `application/vnd.android.package-archive`, `Content-Disposition: attachment`, 1 h cache; `latest.json` `no-cache`.
- Signup, me, sessions, cross-origin rejection and delete-account run against production with a throwaway user (deleted afterwards).
