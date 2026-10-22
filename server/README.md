# Liuying account server

One Express 4 service that serves the static site from `../site` and a JSON API under `/api`
(same origin, so the session cookie needs no CORS). PostgreSQL via `pg`.

## Run

    cd server && npm ci
    npm start          # http://localhost:3000
    npm test           # node:test + supertest + pg-mem (sync, /watch and CSP tests in test/sync.test.js)

Without `DATABASE_URL` (non-production only) it uses an in-memory pg-mem database and logs
`DEV in-memory database, accounts will not persist`. pg-mem is a devDependency, so production
(`npm ci --omit=dev`) requires `DATABASE_URL`; the server exits otherwise.

## Environment variables

| Var | Meaning |
| --- | --- |
| `DATABASE_URL` | Postgres connection string. Required in production. |
| `DATABASE_SSL` | `true` to enable TLS (no cert verification) - needed for Render's *external* DB URL; the internal URL does not need it. |
| `NODE_ENV` | `production` turns on `Secure` cookies, HSTS, `upgrade-insecure-requests` and `trust proxy = 1`. |
| `PORT` | Listen port (default 3000; Render sets it). |

## Render

- Root directory: repo root
- Build command: `cd server && npm ci --omit=dev`
- Start command: `node server/src/index.js`
- Env: `NODE_ENV=production`, `DATABASE_URL` (from the Render Postgres), optionally `DATABASE_SSL`
- Health check path: `/health`

Migrations (`src/migrations.sql`, all `IF NOT EXISTS`) run on every start. The `users` table has no
DB-side uuid default (ids are generated in Node with `crypto.randomUUID`) so the SQL also runs on pg-mem.

## API

All POSTs need `Content-Type: application/json` (send `{}` for logout) and, if an `Origin` header
is present, it must match `Host`. Errors: `{error, message}`.

- `POST /api/auth/signup {username,password}` -> 201 `{user:{username}}` + cookie
- `POST /api/auth/login {username,password}` -> 200 `{user:{username}}` + cookie
- `POST /api/auth/logout` -> 204
- `GET /api/auth/me` -> 200 `{user:{username}}` or 401 `{error:'unauthenticated'}`

Error codes: `invalid_username` 400, `invalid_password` 400, `username_taken` 409,
`invalid_credentials` 401, `rate_limited` 429 (+`Retry-After`), `bad_request` 400,
`unsupported_media_type` 415, `forbidden` 403 (bad Origin), `internal_error` 500, `not_found` 404.

## Sync API (authenticated)

Shared by the web player and the Android app. Every route needs the `liuying_sid` session cookie
(`401 {error:'unauthenticated'}` otherwise), is rate limited to **60 requests/minute per user**
(`429 rate_limited` + `Retry-After`), and every query is scoped by the session user (client ids are
never trusted). Non-GET requests need an `Origin` (if sent) matching `Host`; POST/PUT also need
`Content-Type: application/json` (`415` otherwise). `DELETE /api/sync/all` needs only the Origin check.
Body limits: sync routes 256 KB, `PUT /settings` 16 KB (`413 payload_too_large`); the auth routes keep
the global 2 KB limit (`400 bad_request`). Responses are `Cache-Control: no-store`.

| Route | Body | Success |
| --- | --- | --- |
| `GET /api/sync/state` | - | 200 `{sources:[{url,enabled}], library:[{id,type,name,poster,addedAt}], progress:[{mediaId,episodeId,positionMs,durationMs,watched,dismissed,updatedAt}], settings:{}, serverTime}` |
| `PUT /api/sync/sources` | `{sources:[{url,enabled}]}` | 200 `{sources}` (replaces the list; order = saved order) |
| `POST /api/sync/library` | `{add:[{id,type,name,poster}], remove:[id]}` | 200 `{count}` (items in library after the change) |
| `POST /api/sync/progress` | `{items:[{mediaId,episodeId,positionMs,durationMs,watched,dismissed,updatedAt}]}` | 200 `{accepted:n}` |
| `PUT /api/sync/settings` | `{settings:{...}}` | 200 `{settings}` |
| `DELETE /api/sync/all` | - | 204 (deletes all synced data, not the account) |

Details:

- **State**: `episodeId` is `null` for movies; `serverTime` is ms epoch; `settings` is `{}` if never saved;
  `addedAt` is an ISO string; library is ordered by `addedAt` ascending, progress by `updatedAt` descending.
- **Sources**: max 100 entries (more -> 400 `too_many_items`); `url` must be http(s), <= 2048 chars;
  duplicates are dropped (first occurrence wins); `enabled` defaults to `true`, must be boolean if given.
- **Library**: `add` and `remove` each max 5000 per call (400 `too_many_items`); the library holds at most
  5000 items per user (**409** `too_many_items`, nothing is changed). `id` 1-128 chars; `type` in
  `movie|series|channel|tv|other`; `name` 1-300 chars; `poster` http(s) <= 2048 or `null`/omitted.
  Re-adding an existing id updates type/name/poster but keeps the original `addedAt` (set server-side on
  first add). Removes are applied before adds; unknown ids are ignored.
- **Progress**: max 200 items per call (400 `too_many_items`); any invalid item rejects the whole call
  (400 `bad_request`). `mediaId` 1-128 chars; `episodeId` `null`/omitted or 1-128 chars; `positionMs` and
  `durationMs` integers 0..1e10 with `positionMs <= durationMs + 60000`; `watched`/`dismissed` optional
  booleans (default false); `updatedAt` ms epoch integer, not more than 1 day in the future. Last writer
  wins per `(mediaId, episodeId)`: a row is replaced only if the incoming `updatedAt` is strictly greater;
  older or equal items are silently ignored. Within one call the highest `updatedAt` per key wins.
  `accepted` counts the items that were actually stored (inserted or replaced), so stale items do not
  count. At most 50,000 rows per user (**409** `too_many_items`, nothing is changed).
- **Settings**: must be a plain JSON object (not array/null); request body <= 16 KB (413
  `payload_too_large`); the whole object is replaced (last write wins).
- **Atomicity**: each write runs in a DB transaction and the caps are checked before writing.

Tables (created idempotently by `src/migrations.sql`, all `ON DELETE CASCADE` from `users`):
`user_sources(user_id,url,position,enabled)`, `user_library(user_id,id,type,name,poster,added_at)`,
`user_progress(user_id,media_id,episode_id,...,updated_at)` (`episode_id` is `''` for movies) and
`user_settings(user_id,data,updated_at)`; primary keys all start with `user_id`.

Additional error codes: `payload_too_large` 413, `too_many_items` 400 (per-call limits) / 409 (stored caps).

## Web player (`/watch`)

`GET /watch` and `GET /watch/*` return `site/watch/index.html` (the SPA shell, client-side routing).
Real files under `site/watch/` (and everything else under `site/`, e.g. `site/vendor/`) are served as
static files; a missing asset with a file extension is a plain 404, and if `site/watch/index.html` does
not exist the routes return 404 `Not found`.

### Content-Security-Policy

Every route keeps the strict policy (`default-src 'self'` etc., `connect-src 'self'`, `img-src 'self' data:`).
Only `/watch` and `/watch/*` get this different policy, because the player must fetch addon manifests and
streams from arbitrary https hosts that cannot be listed in advance:

    default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' https:;
    img-src 'self' data: blob: https:; media-src 'self' blob: https:; font-src 'self';
    worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'

Scripts and styles stay `'self'` only (no inline, no remote code), so the relaxation is limited to data
fetches, images and media. `upgrade-insecure-requests` is added to both policies in production.

## Security notes

- scrypt N=2^15 r=8 p=1, 16-byte salt, 64-byte key, stored as `scrypt$N$r$p$salt$hash`; hashes
  are upgraded transparently on login if the parameters change. Needs ~32 MB RAM per hash.
- Session token: 32 random bytes in cookie `liuying_sid` (HttpOnly, SameSite=Lax, Path=/, 30 days,
  Secure in production); only its SHA-256 is stored. Login rotates the session.
- Rate limits (in memory, per process, reset on restart): login 10/15 min per IP+username and
  30/15 min per IP; signup 5/hour per IP. Every attempt counts, successful or not.
- CSP allows only `'self'` (plus `data:` images): no inline scripts or `style=""` attributes, no CDNs.
- Request bodies are never logged.
- Migrations are idempotent on PostgreSQL; pg-mem (dev/test) cannot re-run `CREATE TABLE IF NOT EXISTS` on existing tables, but it only ever starts from an empty database.
