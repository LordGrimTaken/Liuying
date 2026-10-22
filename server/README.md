# Liuying account server

One Express 4 service that serves the static site from `../site` and a JSON API under `/api`
(same origin, so the session cookie needs no CORS). PostgreSQL via `pg`.

## Run

    cd server && npm ci
    npm start          # http://localhost:3000
    npm test           # node:test + supertest + pg-mem

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

## Security notes

- scrypt N=2^15 r=8 p=1, 16-byte salt, 64-byte key, stored as `scrypt$N$r$p$salt$hash`; hashes
  are upgraded transparently on login if the parameters change. Needs ~32 MB RAM per hash.
- Session token: 32 random bytes in cookie `liuying_sid` (HttpOnly, SameSite=Lax, Path=/, 30 days,
  Secure in production); only its SHA-256 is stored. Login rotates the session.
- Rate limits (in memory, per process, reset on restart): login 10/15 min per IP+username and
  30/15 min per IP; signup 5/hour per IP. Every attempt counts, successful or not.
- CSP allows only `'self'` (plus `data:` images): no inline scripts or `style=""` attributes, no CDNs.
- Request bodies are never logged.
