// Authenticated per-user sync: sources, library, progress, settings. Same limits, validation and JSON shapes as
// server/src/sync.js. Every query is scoped by the session user id (never a client-supplied id).
//
// D1 notes: a Worker on the free plan has a small subrequest budget and D1 binds at most 100 parameters per
// statement, so lists are passed as ONE JSON text parameter and expanded in SQL with json_each(). Multi-statement
// writes go through db.batch(), which D1 runs as a single transaction.
import { ApiError } from './errors.js';

export const LIMITS = {
  maxSources: 100,
  maxLibrary: 5000,
  maxProgressPerCall: 200,
  maxProgressRows: 50000,
  maxUrl: 2048,
  maxId: 128,
  maxName: 300,
  settingsBytes: 16 * 1024,
  maxPositionMs: 1e10,
  futureSkewMs: 24 * 60 * 60 * 1000,
  positionToleranceMs: 60000,
};
const TYPES = new Set(['movie', 'series', 'channel', 'tv', 'other']);
const bad = (m) => new ApiError(400, 'bad_request', m);
const tooMany = (status, m) => new ApiError(status, 'too_many_items', m);

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const isHttpUrl = (s) => {
  if (typeof s !== 'string' || !s || s.length > LIMITS.maxUrl) return false;
  try { const u = new URL(s); return u.protocol === 'http:' || u.protocol === 'https:'; } catch { return false; }
};
const isId = (s) => typeof s === 'string' && s.length >= 1 && s.length <= LIMITS.maxId;
const isMs = (n) => Number.isInteger(n) && n >= 0 && n <= LIMITS.maxPositionMs;
const bodyObj = (b) => { if (!isObj(b)) throw bad('Request body must be a JSON object.'); return b; };
const iso = (ms) => new Date(Number(ms)).toISOString();

export async function handleSync({ method, sub, db, userId, now, readBody }) {
  const run = {
    'GET /state': () => state(db, userId, now),
    'PUT /sources': async () => sources(db, userId, bodyObj(await readBody(256 * 1024))),
    'POST /library': async () => library(db, userId, now, bodyObj(await readBody(256 * 1024))),
    'POST /progress': async () => progress(db, userId, now, bodyObj(await readBody(256 * 1024))),
    'PUT /settings': async () => settings(db, userId, now, bodyObj(await readBody(16 * 1024))),
    'DELETE /all': () => deleteAll(db, userId),
  }[`${method} ${sub}`];
  return run ? run() : null;
}

async function state(db, u, now) {
  const [src, lib, prog, set] = await db.batch([
    db.prepare('SELECT url, enabled FROM user_sources WHERE user_id = ? ORDER BY position ASC').bind(u),
    db.prepare('SELECT id, type, name, poster, added_at FROM user_library WHERE user_id = ? ORDER BY added_at ASC, id ASC').bind(u),
    db.prepare(`SELECT media_id, episode_id, position_ms, duration_ms, watched, dismissed, updated_at
                FROM user_progress WHERE user_id = ? ORDER BY updated_at DESC, media_id ASC, episode_id ASC`).bind(u),
    db.prepare('SELECT data FROM user_settings WHERE user_id = ?').bind(u),
  ]);
  let settingsOut = {};
  if (set.results[0]) { try { settingsOut = JSON.parse(set.results[0].data); } catch { settingsOut = {}; } }
  return {
    status: 200,
    body: {
      sources: src.results.map((r) => ({ url: r.url, enabled: !!r.enabled })),
      library: lib.results.map((r) => ({ id: r.id, type: r.type, name: r.name, poster: r.poster ?? null, addedAt: iso(r.added_at) })),
      progress: prog.results.map((r) => ({
        mediaId: r.media_id, episodeId: r.episode_id === '' ? null : r.episode_id,
        positionMs: Number(r.position_ms), durationMs: Number(r.duration_ms),
        watched: !!r.watched, dismissed: !!r.dismissed, updatedAt: Number(r.updated_at),
      })),
      settings: settingsOut,
      serverTime: now(),
    },
  };
}

async function sources(db, u, { sources: input }) {
  if (!Array.isArray(input)) throw bad('sources must be an array.');
  if (input.length > LIMITS.maxSources) throw tooMany(400, `At most ${LIMITS.maxSources} sources are allowed.`);
  const seen = new Set();
  const list = [];
  for (const s of input) {
    if (!isObj(s) || !isHttpUrl(s.url)) throw bad('Each source needs an http(s) url of at most 2048 characters.');
    if (s.enabled !== undefined && typeof s.enabled !== 'boolean') throw bad('source.enabled must be a boolean.');
    if (seen.has(s.url)) continue;
    seen.add(s.url);
    list.push({ url: s.url, enabled: s.enabled !== false });
  }
  const stmts = [db.prepare('DELETE FROM user_sources WHERE user_id = ?').bind(u)];
  if (list.length) {
    stmts.push(db.prepare(`INSERT INTO user_sources (user_id, url, position, enabled)
      SELECT ?1, json_extract(value, '$[0]'), json_extract(value, '$[1]'), json_extract(value, '$[2]') FROM json_each(?2)`)
      .bind(u, JSON.stringify(list.map((s, i) => [s.url, i, s.enabled ? 1 : 0]))));
  }
  await db.batch(stmts);
  return { status: 200, body: { sources: list } };
}

async function library(db, u, now, { add = [], remove = [] }) {
  if (!Array.isArray(add) || !Array.isArray(remove)) throw bad('add and remove must be arrays.');
  if (add.length > LIMITS.maxLibrary || remove.length > LIMITS.maxLibrary) {
    throw tooMany(400, `At most ${LIMITS.maxLibrary} items per list.`);
  }
  const adds = new Map(); // last occurrence of an id wins
  for (const it of add) {
    if (!isObj(it) || !isId(it.id)) throw bad('Each item needs an id of 1-128 characters.');
    if (!TYPES.has(it.type)) throw bad('type must be one of movie, series, channel, tv, other.');
    if (typeof it.name !== 'string' || it.name.length < 1 || it.name.length > LIMITS.maxName) throw bad('name must be 1-300 characters.');
    if (it.poster !== undefined && it.poster !== null && !isHttpUrl(it.poster)) {
      throw bad('poster must be an http(s) url of at most 2048 characters, or null.');
    }
    adds.set(it.id, [it.id, it.type, it.name, it.poster ?? null]);
  }
  for (const id of remove) if (!isId(id)) throw bad('Each removed id must be a string of 1-128 characters.');
  const removes = [...new Set(remove)];
  const touched = [...new Set([...adds.keys(), ...removes])];

  // Check the cap BEFORE writing so a 409 never leaves partial changes.
  const [curQ, presentQ] = await db.batch([
    db.prepare('SELECT count(*) AS c FROM user_library WHERE user_id = ?').bind(u),
    db.prepare('SELECT id FROM user_library WHERE user_id = ?1 AND id IN (SELECT value FROM json_each(?2))').bind(u, JSON.stringify(touched)),
  ]);
  const present = new Set(presentQ.results.map((r) => r.id));
  const removeSet = new Set(removes);
  const removedExisting = removes.filter((id) => present.has(id)).length;
  const addsAlreadyThere = [...adds.keys()].filter((id) => present.has(id) && !removeSet.has(id)).length;
  const final = Number(curQ.results[0].c) - removedExisting + adds.size - addsAlreadyThere;
  if (final > LIMITS.maxLibrary) throw tooMany(409, `The library is limited to ${LIMITS.maxLibrary} items.`);

  const stmts = [];
  if (removes.length) {
    stmts.push(db.prepare('DELETE FROM user_library WHERE user_id = ?1 AND id IN (SELECT value FROM json_each(?2))')
      .bind(u, JSON.stringify(removes)));
  }
  if (adds.size) {
    stmts.push(db.prepare(`INSERT INTO user_library (user_id, id, type, name, poster, added_at)
      SELECT ?1, json_extract(value, '$[0]'), json_extract(value, '$[1]'), json_extract(value, '$[2]'), json_extract(value, '$[3]'), ?3
      FROM json_each(?2) WHERE true
      ON CONFLICT (user_id, id) DO UPDATE SET type = excluded.type, name = excluded.name, poster = excluded.poster`)
      .bind(u, JSON.stringify([...adds.values()]), now()));
  }
  if (stmts.length) await db.batch(stmts);
  return { status: 200, body: { count: final } };
}

async function progress(db, u, now, { items }) {
  if (!Array.isArray(items)) throw bad('items must be an array.');
  if (items.length > LIMITS.maxProgressPerCall) throw tooMany(400, `At most ${LIMITS.maxProgressPerCall} items per call.`);
  const maxUpdated = now() + LIMITS.futureSkewMs;
  const byKey = new Map(); // within one call, highest updatedAt wins
  for (const it of items) {
    if (!isObj(it)) throw bad('Each item must be an object.');
    if (!isId(it.mediaId)) throw bad('mediaId must be a string of 1-128 characters.');
    if (it.episodeId !== undefined && it.episodeId !== null && !isId(it.episodeId)) {
      throw bad('episodeId must be null or a string of 1-128 characters.');
    }
    if (!isMs(it.positionMs) || !isMs(it.durationMs)) throw bad('positionMs and durationMs must be integers >= 0.');
    if (it.positionMs > it.durationMs + LIMITS.positionToleranceMs) throw bad('positionMs exceeds durationMs.');
    for (const k of ['watched', 'dismissed']) {
      if (it[k] !== undefined && typeof it[k] !== 'boolean') throw bad(`${k} must be a boolean.`);
    }
    if (!Number.isInteger(it.updatedAt) || it.updatedAt < 0) throw bad('updatedAt must be a ms epoch integer.');
    if (it.updatedAt > maxUpdated) throw bad('updatedAt is too far in the future.');
    const key = `${it.mediaId}\u0000${it.episodeId ?? ''}`;
    const prev = byKey.get(key);
    if (!prev || it.updatedAt > prev[6]) {
      byKey.set(key, [it.mediaId, it.episodeId ?? '', it.positionMs, it.durationMs, it.watched === true ? 1 : 0, it.dismissed === true ? 1 : 0, it.updatedAt]);
    }
  }
  const rows = [...byKey.values()];
  if (!rows.length) return { status: 200, body: { accepted: 0 } };
  const payload = JSON.stringify(rows);

  // Cap check first (a 409 never leaves partial changes), then ONE last-write-wins upsert:
  // an existing row is only replaced when the incoming updatedAt is strictly newer.
  const [totalQ, existingQ] = await db.batch([
    db.prepare('SELECT count(*) AS c FROM user_progress WHERE user_id = ?').bind(u),
    db.prepare(`SELECT count(*) AS c FROM user_progress p JOIN json_each(?2) j
      ON p.media_id = json_extract(j.value, '$[0]') AND p.episode_id = json_extract(j.value, '$[1]') WHERE p.user_id = ?1`).bind(u, payload),
  ]);
  const newRows = rows.length - Number(existingQ.results[0].c);
  if (newRows > 0 && Number(totalQ.results[0].c) + newRows > LIMITS.maxProgressRows) {
    throw tooMany(409, `Progress is limited to ${LIMITS.maxProgressRows} rows.`);
  }
  const res = await db.prepare(`INSERT INTO user_progress
      (user_id, media_id, episode_id, position_ms, duration_ms, watched, dismissed, updated_at)
    SELECT ?1, json_extract(value, '$[0]'), json_extract(value, '$[1]'), json_extract(value, '$[2]'), json_extract(value, '$[3]'),
      json_extract(value, '$[4]'), json_extract(value, '$[5]'), json_extract(value, '$[6]') FROM json_each(?2) WHERE true
    ON CONFLICT (user_id, media_id, episode_id) DO UPDATE SET
      position_ms = excluded.position_ms, duration_ms = excluded.duration_ms, watched = excluded.watched,
      dismissed = excluded.dismissed, updated_at = excluded.updated_at
      WHERE excluded.updated_at > user_progress.updated_at
    RETURNING 1 AS ok`).bind(u, payload).all();
  return { status: 200, body: { accepted: res.results.length } };
}

async function settings(db, u, now, { settings: s }) {
  if (!isObj(s) || Object.getPrototypeOf(s) !== Object.prototype) throw bad('settings must be a JSON object.');
  const data = JSON.stringify(s);
  if (new TextEncoder().encode(data).length > LIMITS.settingsBytes) throw new ApiError(413, 'payload_too_large', 'settings must be at most 16 KB.');
  await db.prepare(`INSERT INTO user_settings (user_id, data, updated_at) VALUES (?1, ?2, ?3)
    ON CONFLICT (user_id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at`).bind(u, data, now()).run();
  return { status: 200, body: { settings: s } };
}

async function deleteAll(db, u) {
  await db.batch(['user_sources', 'user_library', 'user_progress', 'user_settings']
    .map((t) => db.prepare(`DELETE FROM ${t} WHERE user_id = ?`).bind(u)));
  return { status: 204 };
}
