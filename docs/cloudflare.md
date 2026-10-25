# Liuying on Cloudflare (Workers Free + D1)

Everything (site, web player, APK downloads, accounts and sync API) runs on one Cloudflare Worker with static
assets and a D1 database. No paid plan, no card, no expiry. Source: `cloudflare/`. The Express server in `server/`
is kept until cutover is done.

## Architecture

| Piece | What it is |
| --- | --- |
| `cloudflare/src/worker.js` | Worker entry. Handles `/api/*`, `/health` and the `/watch` SPA routes. |
| `cloudflare/src/api.js`, `sync.js` | Port of `server/src/app.js` (auth) and `server/src/sync.js`. Same endpoints, JSON shapes, status codes and error codes, `liuying_sid` cookie, CSRF checks. |
| `cloudflare/src/auth.js` | Username rules (NFKC, 2-24 code points), password hashing (PBKDF2), recovery codes, cookies. |
| `cloudflare/src/rateLimit.js` | Sliding-window limiter in D1 (credentials) and in memory (sync). |
| `cloudflare/scripts/build-assets.mjs` | Builds `cloudflare/dist` from `site/`: drops `watch/test`, keeps only the APK named in `downloads/latest.json`, adds `404.html` and `_headers`. |
| `cloudflare/migrations/` | D1 schema (SQLite translation of `server/src/migrations.sql`). Timestamps are integer ms, booleans 0/1. |
| `cloudflare/wrangler.jsonc` | Worker `liuying`, assets, D1 binding `DB`, rate-limit binding `SYNC_RATE`, var `PBKDF2_ITERATIONS`. |

Static files are served by Workers Assets directly (free, not counted as Worker requests). Only `/api/*`,
`/health`, `/watch` and extension-less `/watch/*` deep links run the Worker (`assets.run_worker_first`).
Security headers (CSP, HSTS, nosniff, ...) for static files come from the generated `_headers`; `/watch/*` gets the
relaxed CSP (`connect-src https:` etc.) and `/watch/sw.js` is `no-cache` with `Service-Worker-Allowed: /watch/`.
The APK has its Android MIME type, `Content-Disposition: attachment` and `max-age=3600`; `latest.json` is `no-cache`.

Current deployment: Worker `liuying`, D1 database `liuying` (id in `wrangler.jsonc`), account workers.dev
subdomain `liuyingapp`, so the URL is `https://liuying.liuyingapp.workers.dev`.

## Deploy

```sh
cd cloudflare
npm ci
export CLOUDFLARE_API_TOKEN=...      # or: npx wrangler login
npm run deploy                       # build dist + apply D1 migrations (remote) + wrangler deploy
```

The token needs: Workers Scripts: Edit, D1: Edit, and (for custom domains) Zone: Workers Routes: Edit and
DNS: Edit on `liuyingapp.com`. Other scripts: `npm test` (unit + D1-shim tests), `npm run dev` (local, with a local D1),
`npm run backup` (SQL export), `node scripts/verify.mjs <url> <phase>` (end-to-end checks, see below).

Releasing a new APK: put `Liuying-x.y.z.apk` in `site/downloads/`, update `latest.json`, run `npm run deploy`.
Only the APK that `latest.json` names is uploaded (Workers Free: 25 MiB per asset, 20,000 files).

## Cutting over liuyingapp.com

Do this only after `https://liuying.liuyingapp.workers.dev` has been checked.

1. Optional data move first (below).
2. In the Cloudflare DNS dashboard delete the records that point `liuyingapp.com` and `www` at Render
   (the apex A/AAAA or CNAME, and the `www` CNAME). A Custom Domain cannot be created over an existing record.
3. Add to `wrangler.jsonc`:
   ```jsonc
   "routes": [
     { "pattern": "liuyingapp.com", "custom_domain": true },
     { "pattern": "www.liuyingapp.com", "custom_domain": true }
   ]
   ```
   then `npm run deploy`. Cloudflare creates the DNS records and certificates itself (a minute or two).
   (Dashboard alternative: Workers & Pages > liuying > Settings > Domains & Routes > Add > Custom domain.)
4. Check `https://www.liuyingapp.com/health`, `/downloads/latest.json` and a login. `latest.json` already points the
   app's updater at `https://www.liuyingapp.com/downloads/...`. If the old site redirected apex to www, add a
   redirect rule (Rules > Redirect Rules) for the apex; the Worker serves both hosts the same.
5. Keep Render running for a day or two, then delete the Render service and database (the free DB expires 2026-11-21).

**Rollback:** remove the two custom domains (dashboard, or delete the `routes` block and redeploy), then recreate the
old DNS records pointing at Render. Accounts created on Cloudflare in the meantime do not exist on Render.

## Moving the existing accounts (Render Postgres to D1)

Old scrypt hashes cannot be verified on the Free plan (not enough CPU), so passwords do not move. Data does:

```sh
cd cloudflare
# 1. export (read-only; DATABASE_URL is the Render EXTERNAL url, never printed; add DATABASE_SSL=true)
DATABASE_URL=... DATABASE_SSL=true node scripts/migrate-render-to-d1.mjs export --out legacy-export.sql
npx wrangler d1 execute liuying --remote --file legacy-export.sql
```

Imported users have `password_hash = NULL` and `needs_new_password = 1`: they cannot log in with a password. If a user
had a recovery code, it still works (recovery codes are SHA-256 and move unchanged), which sets a new password.
For the owner, or anyone without a code: sign up on the new site under a temporary name, then re-parent the old data:

```sh
node scripts/migrate-render-to-d1.mjs reparent --from OldName --to TempName --take-username --out reparent.sql
npx wrangler d1 execute liuying --remote --file reparent.sql
```

This moves sources, library, progress and settings to the new account, deletes the imported shell and (with
`--take-username`) renames the new account to `OldName`. Safe to run twice; if `TempName` does not exist yet nothing is
deleted. Sessions are not migrated, everyone signs in again. The generated `.sql` files contain user data: do not
commit them (they are git-ignored). Tested against a local Postgres 16 and a local D1.

## Password hashing trade-off (read this)

* Express used scrypt (N=32768). Workers Free allows 10 ms CPU per request and cannot run scrypt or Argon2.
* The Worker uses **PBKDF2-HMAC-SHA256 via native WebCrypto**. Workers caps PBKDF2 at 100,000 iterations.
* Measured on the real edge (colo IAD, Workers Free, analytics `cpuTime` P50): about **0.2 ms per 1,000 iterations**
  plus about 2.5 ms for any request that touches D1. Probe numbers: 10k iterations 2.7 ms, 25k 5.7 ms, 50k 10.1 ms,
  100k 21.2 ms. A full login at 100k measured 26.6 ms P50.
* **Default: 15,000 iterations** (`PBKDF2_ITERATIONS` var). Measured with that setting on the real edge: the full
  account test run (54 requests: signup, logins, change password with two derivations, recovery, delete) had CPU
  P50 2.0 ms, P90 8.2 ms, max 9.9 ms. Logins from fresh connections (cold isolates) measured P50 7.8 ms, P90 14.8 ms.
  Cloudflare did not kill any request in any test, even at 100k iterations (about 27 ms login, about 60 ms
  change-password, 100 chained derivations in one request), so the 10 ms limit is not enforced to the millisecond, but it is
  the documented limit and could be enforced, so the default stays at what fits it for typical requests.
* The iteration count is stored in each hash (`pbkdf2-sha256$<iterations>$<salt>$<hash>`). Raising `PBKDF2_ITERATIONS`
  (up to 100000) re-hashes each account transparently at its next login. Hashes are never downgraded.
  On Workers Paid (30 s CPU) set it to 100000, the platform maximum; for stronger hashing than that a different algorithm
  would be needed (Argon2 in WASM), which is a code change.
* Compensating controls: per-user (40 / 15 min), per-IP (30 / 15 min) and per-IP+user (10 / 15 min) login limits stored in
  D1 so they are shared by every Cloudflare location; signup 5 / hour / IP; recovery 10 / hour / IP and 5 / hour / user;
  password-change and delete-account 10 / 15 min / user. Compared with scrypt, an attacker who stole the D1 data could test
  guesses much faster, so users should use long passwords; the 8-character minimum and common-password list are unchanged.
* **Recovery codes** are 100-bit random strings generated by the server (not chosen by humans), so a single SHA-256 is
  sufficient: brute force is infeasible regardless of hash speed, and a slow hash would only burn CPU budget.
* Unknown-user logins and migrated accounts run a dummy derivation, so response time does not reveal which usernames exist.

## Differences from the Express server

* Password hash format and strength (above). Legacy accounts need a new password (above).
* Sync rate limit (60/min/user) is enforced by an exact per-isolate counter plus the Workers rate-limiting binding
  (approximate, per location). Auth-related limits are exact (D1).
* Library and progress caps are checked just before the write inside the same Worker call, not under a lock, so two
  simultaneous requests could overshoot a cap by one request's worth of items. Writes themselves are atomic (D1 batch).
* `/login.html` and similar `.html` URLs redirect to the clean URL (Workers Assets `auto-trailing-slash`); Express
  served them directly. A missing file under `/watch` gets the branded HTML 404 instead of a plain-text 404.
* Static responses carry Cloudflare's cache headers (`public, max-age=0, must-revalidate`) instead of Express's
  `public, max-age=0`; JavaScript is served as `text/javascript`.
* `Secure` on the session cookie follows the request scheme (always set on the real site, omitted on `http://localhost`).
* Client IP comes from `CF-Connecting-IP`, which Cloudflare sets and clients cannot forge.
* Request bodies are size-capped while streaming (2 KB auth, 256 KB sync, 16 KB settings), same error codes.

## Limits to know (Workers Free)

100,000 Worker requests per day (static assets do not count), 10 ms CPU per request, 50 subrequests (a D1 query counts
toward the 1,000 internal-service limit), D1: 5 million rows read and 100,000 rows written per day (enforced since
2026-09-01), 5 GB storage. Each login writes roughly 8-10 rows (limiter hits and the session). Lists in sync requests
travel as one JSON parameter expanded with `json_each()` because D1 allows only 100 bound parameters per statement.

## Backups and ops

* `npm run backup` writes `liuying-backup.sql` (git-ignored). D1 Time Travel also keeps point-in-time history:
  `npx wrangler d1 time-travel info liuying`.
* Logs: `npx wrangler tail liuying` (Workers Logs are enabled in `wrangler.jsonc`).
* Clear the limiter table if you lock yourself out: `npx wrangler d1 execute liuying --remote --command "DELETE FROM rate_hits"`.

## Verification

`node scripts/verify.mjs https://liuying.liuyingapp.workers.dev <static|account|sync|limits>` runs about 200 checks (static
headers and CSP, 404/HEAD, APK sha256 against `latest.json`, signup incl. CJK and NFKC variants, CSRF, login/logout/me,
sessions, password change, recovery flow, delete account, every sync endpoint incl. last-write-wins, limits and 429s). It
deletes every account it creates. Signup is limited to 5 per hour per IP, so on a deployed site run the phases one at a
time and run `DELETE FROM rate_hits` between them.

Automatic deploys: pushes to main build and deploy through Cloudflare Workers Builds (root directory cloudflare).
