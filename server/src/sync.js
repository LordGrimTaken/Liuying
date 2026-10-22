// Authenticated per-user sync: sources, library, progress, settings.
// Every query is parameterised and scoped by the session user id (never by a client-supplied id).
import express from 'express';
import { SESSION_COOKIE, hashToken, parseCookies } from './auth.js';
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

function chunks(arr, n) {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
}

// Multi-row INSERT with $n placeholders; `tail` is appended (e.g. ON CONFLICT ...).
async function insertMany(q, table, cols, rows, tail = '') {
  for (const part of chunks(rows, Math.max(1, Math.floor(30000 / cols.length)))) {
    const params = [];
    const values = part.map((r) => `(${r.map((v) => { params.push(v); return `$${params.length}`; }).join(', ')})`);
    await q.query(`INSERT INTO ${table} (${cols.join(', ')}) VALUES ${values.join(', ')} ${tail}`, params);
  }
}

export function createSyncRouter({ db, now, limiter }) {
  const router = express.Router();
  const tx = (fn) => (db.transaction ? db.transaction(fn) : fn(db));

  // 1. authenticate (session cookie) 2. per-user rate limit 3. body parser sized per route
  router.use(async (req, res, next) => {
    try {
      const token = parseCookies(req.headers.cookie)[SESSION_COOKIE];
      let userId = null;
      if (token) {
        const r = await db.query(
          'SELECT user_id FROM sessions WHERE token_hash = $1 AND expires_at > $2',
          [hashToken(token), new Date(now())],
        );
        userId = r.rows[0]?.user_id ?? null;
      }
      if (!userId) throw new ApiError(401, 'unauthenticated', 'You are not signed in.');
      req.userId = userId;
      const rl = limiter.check(String(userId));
      if (!rl.allowed) {
        throw new ApiError(429, 'rate_limited', 'Too many requests. Please slow down.',
          { 'Retry-After': String(rl.retryAfterSec) });
      }
      next();
    } catch (err) { next(err); }
  });
  router.use('/settings', express.json({ limit: '16kb' }));
  router.use(express.json({ limit: '256kb' }));

  const body = (req) => {
    if (!isObj(req.body)) throw bad('Request body must be a JSON object.');
    return req.body;
  };

  // ---- GET /state ----
  router.get('/state', async (req, res) => {
    const u = req.userId;
    const [src, lib, prog, set] = await Promise.all([
      db.query('SELECT url, enabled FROM user_sources WHERE user_id = $1 ORDER BY position ASC', [u]),
      db.query('SELECT id, type, name, poster, added_at FROM user_library WHERE user_id = $1 ORDER BY added_at ASC, id ASC', [u]),
      db.query(`SELECT media_id, episode_id, position_ms, duration_ms, watched, dismissed, updated_at
                FROM user_progress WHERE user_id = $1 ORDER BY updated_at DESC, media_id ASC, episode_id ASC`, [u]),
      db.query('SELECT data FROM user_settings WHERE user_id = $1', [u]),
    ]);
    let settings = {};
    if (set.rows[0]) { try { settings = JSON.parse(set.rows[0].data); } catch { settings = {}; } }
    res.status(200).json({
      sources: src.rows.map((r) => ({ url: r.url, enabled: r.enabled })),
      library: lib.rows.map((r) => ({
        id: r.id, type: r.type, name: r.name, poster: r.poster ?? null,
        addedAt: new Date(r.added_at).toISOString(),
      })),
      progress: prog.rows.map((r) => ({
        mediaId: r.media_id, episodeId: r.episode_id === '' ? null : r.episode_id,
        positionMs: Number(r.position_ms), durationMs: Number(r.duration_ms),
        watched: r.watched, dismissed: r.dismissed, updatedAt: Number(r.updated_at),
      })),
      settings,
      serverTime: now(),
    });
  });

  // ---- PUT /sources ----
  router.put('/sources', async (req, res) => {
    const { sources } = body(req);
    if (!Array.isArray(sources)) throw bad('sources must be an array.');
    if (sources.length > LIMITS.maxSources) {
      throw tooMany(400, `At most ${LIMITS.maxSources} sources are allowed.`);
    }
    const seen = new Set();
    const list = [];
    for (const s of sources) {
      if (!isObj(s) || !isHttpUrl(s.url)) throw bad('Each source needs an http(s) url of at most 2048 characters.');
      if (s.enabled !== undefined && typeof s.enabled !== 'boolean') throw bad('source.enabled must be a boolean.');
      if (seen.has(s.url)) continue;
      seen.add(s.url);
      list.push({ url: s.url, enabled: s.enabled !== false });
    }
    await tx(async (q) => {
      await q.query('DELETE FROM user_sources WHERE user_id = $1', [req.userId]);
      await insertMany(q, 'user_sources', ['user_id', 'url', 'position', 'enabled'],
        list.map((s, i) => [req.userId, s.url, i, s.enabled]));
    });
    res.status(200).json({ sources: list });
  });

  // ---- POST /library ----
  router.post('/library', async (req, res) => {
    const { add = [], remove = [] } = body(req);
    if (!Array.isArray(add) || !Array.isArray(remove)) throw bad('add and remove must be arrays.');
    if (add.length > LIMITS.maxLibrary || remove.length > LIMITS.maxLibrary) {
      throw tooMany(400, `At most ${LIMITS.maxLibrary} items per list.`);
    }
    const adds = new Map(); // last occurrence of an id wins
    for (const it of add) {
      if (!isObj(it) || !isId(it.id)) throw bad('Each item needs an id of 1-128 characters.');
      if (!TYPES.has(it.type)) throw bad('type must be one of movie, series, channel, tv, other.');
      if (typeof it.name !== 'string' || it.name.length < 1 || it.name.length > LIMITS.maxName) {
        throw bad('name must be 1-300 characters.');
      }
      if (it.poster !== undefined && it.poster !== null && !isHttpUrl(it.poster)) {
        throw bad('poster must be an http(s) url of at most 2048 characters, or null.');
      }
      adds.set(it.id, [it.id, it.type, it.name, it.poster ?? null]);
    }
    for (const id of remove) if (!isId(id)) throw bad('Each removed id must be a string of 1-128 characters.');
    const removes = [...new Set(remove)];

    const count = await tx(async (q) => {
      // Check the cap BEFORE writing so a 409 never leaves partial changes.
      const present = new Set();
      for (const part of chunks([...new Set([...adds.keys(), ...removes])], 500)) {
        const ph = part.map((_, i) => `$${i + 2}`).join(', ');
        const r = await q.query(`SELECT id FROM user_library WHERE user_id = $1 AND id IN (${ph})`, [req.userId, ...part]);
        for (const row of r.rows) present.add(row.id);
      }
      const removeSet = new Set(removes);
      const removedExisting = removes.filter((id) => present.has(id)).length;
      const addsAlreadyThere = [...adds.keys()].filter((id) => present.has(id) && !removeSet.has(id)).length;
      const cur = Number((await q.query('SELECT count(*) AS c FROM user_library WHERE user_id = $1', [req.userId])).rows[0].c);
      const final = cur - removedExisting + adds.size - addsAlreadyThere;
      if (final > LIMITS.maxLibrary) throw tooMany(409, `The library is limited to ${LIMITS.maxLibrary} items.`);

      for (const part of chunks(removes, 500)) {
        const ph = part.map((_, i) => `$${i + 2}`).join(', ');
        await q.query(`DELETE FROM user_library WHERE user_id = $1 AND id IN (${ph})`, [req.userId, ...part]);
      }
      const t = new Date(now());
      await insertMany(q, 'user_library', ['user_id', 'id', 'type', 'name', 'poster', 'added_at'],
        [...adds.values()].map(([id, type, name, poster]) => [req.userId, id, type, name, poster, t]),
        'ON CONFLICT (user_id, id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, poster = EXCLUDED.poster');
      return final;
    });
    res.status(200).json({ count });
  });

  // ---- POST /progress ----
  router.post('/progress', async (req, res) => {
    const { items } = body(req);
    if (!Array.isArray(items)) throw bad('items must be an array.');
    if (items.length > LIMITS.maxProgressPerCall) {
      throw tooMany(400, `At most ${LIMITS.maxProgressPerCall} items per call.`);
    }
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
      if (!prev || it.updatedAt > prev[7]) {
        byKey.set(key, [req.userId, it.mediaId, it.episodeId ?? '', it.positionMs, it.durationMs,
          it.watched === true, it.dismissed === true, it.updatedAt]);
      }
    }
    const rows = [...byKey.values()];

    const accepted = await tx(async (q) => {
      // Decide first (LWW + cap), then write, so a 409 never leaves partial changes.
      const inserts = []; const updates = [];
      for (const r of rows) {
        const cur = await q.query(
          'SELECT updated_at FROM user_progress WHERE user_id = $1 AND media_id = $2 AND episode_id = $3',
          [r[0], r[1], r[2]],
        );
        if (!cur.rows[0]) inserts.push(r);
        else if (Number(cur.rows[0].updated_at) < r[7]) updates.push(r); // older or equal: ignored
      }
      if (inserts.length) {
        const c = Number((await q.query('SELECT count(*) AS c FROM user_progress WHERE user_id = $1', [req.userId])).rows[0].c);
        if (c + inserts.length > LIMITS.maxProgressRows) {
          throw tooMany(409, `Progress is limited to ${LIMITS.maxProgressRows} rows.`);
        }
      }
      for (const r of updates) {
        await q.query(
          `UPDATE user_progress SET position_ms = $4, duration_ms = $5, watched = $6, dismissed = $7, updated_at = $8
           WHERE user_id = $1 AND media_id = $2 AND episode_id = $3`, r);
      }
      await insertMany(q, 'user_progress',
        ['user_id', 'media_id', 'episode_id', 'position_ms', 'duration_ms', 'watched', 'dismissed', 'updated_at'], inserts);
      return inserts.length + updates.length;
    });
    res.status(200).json({ accepted });
  });

  // ---- PUT /settings ----
  router.put('/settings', async (req, res) => {
    const { settings } = body(req);
    if (!isObj(settings) || Object.getPrototypeOf(settings) !== Object.prototype) {
      throw bad('settings must be a JSON object.');
    }
    const data = JSON.stringify(settings);
    if (Buffer.byteLength(data) > LIMITS.settingsBytes) {
      throw new ApiError(413, 'payload_too_large', 'settings must be at most 16 KB.');
    }
    const t = new Date(now());
    await db.query(
      `INSERT INTO user_settings (user_id, data, updated_at) VALUES ($1, $2, $3)
       ON CONFLICT (user_id) DO UPDATE SET data = EXCLUDED.data, updated_at = EXCLUDED.updated_at`,
      [req.userId, data, t],
    );
    res.status(200).json({ settings });
  });

  // ---- DELETE /all ----
  router.delete('/all', async (req, res) => {
    await tx(async (q) => {
      for (const t of ['user_sources', 'user_library', 'user_progress', 'user_settings']) {
        await q.query(`DELETE FROM ${t} WHERE user_id = $1`, [req.userId]);
      }
    });
    res.status(204).end();
  });

  return router;
}
