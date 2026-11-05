# Watch party

Two or more people watch the same title together: one host drives play, pause and seek; everyone else
follows within a couple of seconds. No video is relayed — each participant streams from their own source,
only the playback position is shared.

## Model

- A **room** is created by a signed-in host and identified by a short code (`ABCDEF`, 6 chars, no vowels).
- The room holds: the title (`mediaId`, optional `episodeId`), the playback position in ms, whether it is
  playing, an incrementing `rev`, and the host's user id.
- Participants **poll** `GET /api/party/:code` every 2 seconds. Polling (rather than WebSockets) keeps the
  feature on the free Cloudflare plan and works identically from the Android app and the browser.
- Only the host writes state. A guest's player applies the host's state when it drifts by more than 2 s or
  when `playing` differs.
- The host is authoritative: a guest's local pause is ignored until the guest takes over (host transfer is
  out of scope for v1 — leaving ends the room for everyone).

## API

All routes require the `liuying_sid` session cookie, the usual `Origin` check on writes, and JSON bodies.

| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/api/party` | `{mediaId, episodeId?, positionMs, playing, name?}` | `201 {code, rev, state, members}` |
| `GET` | `/api/party/:code` | — | `200 {code, rev, state, members}` |
| `PUT` | `/api/party/:code` | `{positionMs, playing}` | `200 {rev, state}` (host only, `403` otherwise) |
| `POST` | `/api/party/:code/join` | `{}` | `200 {code, rev, state, host:false}` |
| `POST` | `/api/party/:code/leave` | `{}` | `204` |
| `DELETE` | `/api/party/:code` | — | `204` (host only) |

`state` is `{mediaId, episodeId, positionMs, playing, updatedAt, hostName}`. `rev` increments on every
host write; clients only apply a state whose `rev` is newer than the last one they saw.

Errors reuse the existing codes: `unauthenticated`, `not_found`, `forbidden`, `bad_request`,
`rate_limited`, `too_many_items`.

## Storage

D1 migration `0003_party.sql`:

- `party_rooms(code TEXT PRIMARY KEY, host_user INTEGER NOT NULL, media_id TEXT NOT NULL, episode_id TEXT,
  position_ms INTEGER NOT NULL, playing INTEGER NOT NULL, rev INTEGER NOT NULL DEFAULT 1,
  host_name TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`
- `party_members(code TEXT NOT NULL, user_id INTEGER NOT NULL, joined_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL, PRIMARY KEY (code, user_id))`

Rooms expire after 12 hours without an update; `GET` on an expired or unknown code returns `404`.

## Clients

- **Web** (`site/watch`): a "Watch party" button in the player chrome creates a room and copies
  `?party=CODE`; opening that link joins automatically. The same poll loop applies the host's state.
- **Android**: a party entry in the player overflow creates or joins a room; the player follows the host
  and the host's controls publish position changes, throttled to one write per 2 seconds.

## Out of scope for v1

Voice/video chat, guest control, host migration, per-participant buffers, DRM, and any relay of media
bytes.
