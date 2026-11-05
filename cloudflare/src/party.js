// Watch party: a host drives playback, participants follow by polling.
//
// Only the host writes state. Guests call GET every couple of seconds, which also refreshes their
// last_seen. The alternative â€” WebSockets or a Durable Object â€” costs more on the free plan and would
// need a second code path in the Android client; polling is identical from both clients.
import { ApiError } from './errors.js';

export const LIMITS = {
  codeLen: 6,
  maxId: 128,
  maxPositionMs: 1e10,
  maxName: 40,
  maxMembers: 16,
  bodyBytes: 4 * 1024,
  ttlMs: 12 * 60 * 60 * 1000,
};

// No vowels: a code should never accidentally spell something.
const ALPHABET = 'BCDFGHJKLMNPQRSTVWXYZ';

const bad = (m) => new ApiError(400, 'bad_request', m);
const notFound = () => new ApiError(404, 'not_found', 'That watch party does not exist or has ended.');
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const bodyObj = (b) => { if (!isObj(b)) throw bad('Request body must be a JSON object.'); return b; };
const isId = (s) => typeof s === 'string' && s.length >= 1 && s.length <= LIMITS.maxId;
const isMs = (n) => Number.isInteger(n) && n >= 0 && n <= LIMITS.maxPositionMs;

const randomCode = (len = LIMITS.codeLen) => {
  const bytes = new Uint8Array(len);
  crypto.getRandomValues(bytes);
  let out = '';
  for (let i = 0; i < len; i++) out += ALPHABET[bytes[i] % ALPHABET.length];
  return out;
};

const stateOf = (row) => ({
  mediaId: row.media_id,
  episodeId: row.episode_id ?? null,
  positionMs: Number(row.position_ms),
  playing: !!row.playing,
  updatedAt: Number(row.updated_at),
  hostName: row.host_name ?? null,
});

async function loadRoom(db, code, now) {
  if (typeof code !== 'string' || !/^[A-Z]{6}$/.test(code)) throw notFound();
  const row = await db.prepare('SELECT * FROM party_rooms WHERE code = ?1 AND updated_at > ?2')
    .bind(code, now - LIMITS.ttlMs).first();
  if (!row) throw notFound();
  return row;
}

async function membersOf(db, code) {
  const res = await db.prepare(
    'SELECT user_id, joined_at, last_seen FROM party_members WHERE code = ?1 ORDER BY joined_at').bind(code).all();
  return (res.results ?? []).map((m) => ({ userId: m.user_id, joinedAt: Number(m.joined_at), lastSeen: Number(m.last_seen) }));
}

async function joinRoom(db, code, userId, now) {
  await db.prepare(
    `INSERT INTO party_members (code, user_id, joined_at, last_seen) VALUES (?1, ?2, ?3, ?3)
     ON CONFLICT(code, user_id) DO UPDATE SET last_seen = excluded.last_seen`).bind(code, userId, now).run();
}

const sameUser = (a, b) => String(a) === String(b);

const view = async (db, row, userId, now) => ({
  code: row.code,
  rev: Number(row.rev),
  state: stateOf(row),
  host: sameUser(row.host_user, userId),
  members: await membersOf(db, row.code),
});

async function create(db, userId, username, now, readBody) {
  const body = bodyObj(await readBody(LIMITS.bodyBytes));
  if (!isId(body.mediaId)) throw bad('mediaId is required (1-128 characters).');
  if (body.episodeId != null && !isId(body.episodeId)) throw bad('episodeId must be 1-128 characters or null.');
  if (!isMs(body.positionMs)) throw bad('positionMs must be a non-negative integer.');
  if (typeof body.playing !== 'boolean') throw bad('playing must be true or false.');
  const name = body.name == null ? null : String(body.name).slice(0, LIMITS.maxName);

  // Opportunistic housekeeping: expired rooms never come back, so drop them here rather than on a cron.
  await db.prepare('DELETE FROM party_rooms WHERE updated_at <= ?1').bind(now - LIMITS.ttlMs).run();
  await db.prepare('DELETE FROM party_members WHERE code NOT IN (SELECT code FROM party_rooms)').run();

  for (let attempt = 0; attempt < 5; attempt++) {
    const code = randomCode();
    await db.prepare(
      `INSERT OR IGNORE INTO party_rooms
       (code, host_user, media_id, episode_id, position_ms, playing, rev, host_name, created_at, updated_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, 1, ?7, ?8, ?8)`)
      .bind(code, userId, body.mediaId, body.episodeId ?? null, body.positionMs, body.playing ? 1 : 0, name, now).run();
    const row = await db.prepare('SELECT * FROM party_rooms WHERE code = ?1 AND host_user = ?2').bind(code, userId).first();
    if (row) {
      await joinRoom(db, code, userId, now);
      return { status: 201, body: await view(db, row, userId, now) };
    }
  }
  throw new ApiError(503, 'unavailable', 'Could not allocate a room code. Try again.');
}

async function get(db, code, userId, now) {
  const row = await loadRoom(db, code, now);
  await db.prepare('UPDATE party_members SET last_seen = ?3 WHERE code = ?1 AND user_id = ?2')
    .bind(row.code, userId, now).run();
  return { status: 200, body: await view(db, row, userId, now) };
}

async function update(db, code, userId, now, readBody) {
  const row = await loadRoom(db, code, now);
  if (!sameUser(row.host_user, userId)) throw new ApiError(403, 'forbidden', 'Only the host can control the party.');
  const body = bodyObj(await readBody(LIMITS.bodyBytes));
  if (!isMs(body.positionMs)) throw bad('positionMs must be a non-negative integer.');
  if (typeof body.playing !== 'boolean') throw bad('playing must be true or false.');

  const nextRev = Number(row.rev) + 1;
  await db.prepare('UPDATE party_rooms SET position_ms = ?2, playing = ?3, rev = ?4, updated_at = ?5 WHERE code = ?1')
    .bind(row.code, body.positionMs, body.playing ? 1 : 0, nextRev, now).run();
  const fresh = { ...row, position_ms: body.positionMs, playing: body.playing ? 1 : 0, rev: nextRev, updated_at: now };
  return { status: 200, body: { rev: nextRev, state: stateOf(fresh) } };
}

async function join(db, code, userId, now) {
  const row = await loadRoom(db, code, now);
  const count = await db.prepare('SELECT COUNT(*) AS n FROM party_members WHERE code = ?1').bind(row.code).first();
  const already = await db.prepare('SELECT 1 AS x FROM party_members WHERE code = ?1 AND user_id = ?2').bind(row.code, userId).first();
  if (!already && Number(count?.n ?? 0) >= LIMITS.maxMembers) {
    throw new ApiError(409, 'too_many_items', `A party holds at most ${LIMITS.maxMembers} people.`);
  }
  await joinRoom(db, row.code, userId, now);
  return { status: 200, body: { ...(await view(db, row, userId, now)), host: sameUser(row.host_user, userId) } };
}

async function leave(db, code, userId, now) {
  const row = await loadRoom(db, code, now);
  if (sameUser(row.host_user, userId)) {
    // The host leaving ends the party for everyone: there is no transfer in v1.
    await db.batch([
      db.prepare('DELETE FROM party_members WHERE code = ?1').bind(row.code),
      db.prepare('DELETE FROM party_rooms WHERE code = ?1').bind(row.code),
    ]);
  } else {
    await db.prepare('DELETE FROM party_members WHERE code = ?1 AND user_id = ?2').bind(row.code, userId).run();
  }
  return { status: 204 };
}

async function end(db, code, userId, now) {
  const row = await loadRoom(db, code, now);
  if (!sameUser(row.host_user, userId)) throw new ApiError(403, 'forbidden', 'Only the host can end the party.');
  await db.batch([
    db.prepare('DELETE FROM party_members WHERE code = ?1').bind(row.code),
    db.prepare('DELETE FROM party_rooms WHERE code = ?1').bind(row.code),
  ]);
  return { status: 204 };
}

export async function handleParty({ method, sub, db, userId, username, now, readBody }) {
  const parts = sub.split('/').filter(Boolean);
  const code = parts[0] ? parts[0].toUpperCase() : null;
  const action = parts[1] ?? null;
  if (parts.length > 2) return null;

  if (!code) return method === 'POST' ? create(db, userId, username, now, readBody) : null;
  if (!action) {
    if (method === 'GET') return get(db, code, userId, now);
    if (method === 'PUT') return update(db, code, userId, now, readBody);
    if (method === 'DELETE') return end(db, code, userId, now);
    return null;
  }
  if (action === 'join' && method === 'POST') return join(db, code, userId, now);
  if (action === 'leave' && method === 'POST') return leave(db, code, userId, now);
  return null;
}

