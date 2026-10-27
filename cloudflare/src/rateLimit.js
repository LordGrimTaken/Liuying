import { LIMITS as RELAY } from '../../site/watch/plugins/rules.js';

// Two limiters with the same semantics as server/src/rateLimit.js (sliding window, blocked attempts not recorded).
//
// 1. createLimiter: in-memory, per isolate. Used as a fallback for the sync limiter.
// 2. checkD1: D1-backed, shared by every isolate and location. Used for everything that guards credentials
//    (login, signup, recover, password change), where a per-isolate counter would be trivially bypassed.

export function createLimiter({ limit, windowMs, now = () => Date.now(), maxKeys = 50000 }) {
  const hits = new Map();
  function prune(arr, t) {
    let i = 0;
    while (i < arr.length && arr[i] <= t - windowMs) i++;
    if (i) arr.splice(0, i);
  }
  return {
    check(key) {
      const t = now();
      let arr = hits.get(key);
      if (!arr) {
        if (hits.size >= maxKeys) {
          this.sweep();
          if (hits.size >= maxKeys) hits.delete(hits.keys().next().value);
        }
        arr = [];
        hits.set(key, arr);
      }
      prune(arr, t);
      if (arr.length >= limit) {
        return { allowed: false, retryAfterSec: Math.max(1, Math.ceil((arr[0] + windowMs - t) / 1000)) };
      }
      arr.push(t);
      return { allowed: true, retryAfterSec: 0 };
    },
    sweep() {
      const t = now();
      for (const [k, arr] of hits) { prune(arr, t); if (!arr.length) hits.delete(k); }
    },
    reset() { hits.clear(); },
  };
}

const MIN = 60 * 1000;
// name -> { limit, windowMs }. Same numbers as the Express server.
export const LIMITS = {
  loginPair: { limit: 10, windowMs: 15 * MIN },
  loginIp: { limit: 30, windowMs: 15 * MIN },
  loginUser: { limit: 40, windowMs: 15 * MIN },
  signupIp: { limit: 5, windowMs: 60 * MIN },
  pwChange: { limit: 10, windowMs: 15 * MIN },
  recoverIp: { limit: 10, windowMs: 60 * MIN },
  recoverUser: { limit: 5, windowMs: 60 * MIN },
  relayMin: { limit: RELAY.PER_MINUTE, windowMs: MIN }, // plugin relay calls per user (sliding window, a few rows)
};
export const SYNC_LIMIT = { limit: 60, windowMs: MIN };
const MAX_WINDOW_MS = 60 * MIN;
const DAY_MS = 24 * 60 * MIN;

const INSERT_SQL = `INSERT INTO rate_hits (k, ts) SELECT ?1, ?2
  WHERE (SELECT count(*) FROM rate_hits WHERE k = ?1 AND ts > ?3) < ?4 RETURNING ts`;

/**
 * Checks several limiters in one D1 round trip. `checks` is [[limiterName, key], ...].
 * Every limiter records its own hit when it allows (like the Express server evaluating all of them).
 * Returns [{ allowed, retryAfterSec }, ...].
 */
export async function checkD1(db, checks, now = Date.now()) {
  const stmts = checks.map(([name, key]) => {
    const { limit, windowMs } = LIMITS[name];
    return db.prepare(INSERT_SQL).bind(`${name}:${key}`, now, now - windowMs, limit);
  });
  // Housekeeping on ~4% of calls: drop everything older than the longest window.
  if (Math.random() < 0.04) stmts.push(db.prepare('DELETE FROM rate_hits WHERE ts <= ?').bind(now - MAX_WINDOW_MS));
  const res = await db.batch(stmts);
  const out = checks.map((_, i) => ({ allowed: (res[i].results?.length ?? 0) > 0, retryAfterSec: 0 }));
  const blocked = out.map((o, i) => (o.allowed ? -1 : i)).filter((i) => i >= 0);
  if (blocked.length) {
    const rows = await db.batch(blocked.map((i) => {
      const [name, key] = checks[i];
      return db.prepare('SELECT min(ts) AS oldest FROM rate_hits WHERE k = ? AND ts > ?')
        .bind(`${name}:${key}`, now - LIMITS[name].windowMs);
    }));
    blocked.forEach((i, j) => {
      const oldest = rows[j].results?.[0]?.oldest ?? now;
      out[i].retryAfterSec = Math.max(1, Math.ceil((oldest + LIMITS[checks[i][0]].windowMs - now) / 1000));
    });
  }
  return out;
}

// ---- daily counter (plugin relay, 3000/day/user) -----------------------------------------------------------------
// A sliding window of 3000 rows would make every call read thousands of D1 rows (5 million rows/day on the free plan), so the
// daily cap is ONE row per user and UTC day in rate_hits (no schema change): `ts` holds DAY_BASE + count. DAY_BASE is far in the
// future so the housekeeping delete of old hits (`ts <= now - window`) never removes a counter; old counters are pruned by key.
export const DAY_BASE = 4e12;
const dayKey = (name, key, now) => `${name}:${new Date(now).toISOString().slice(0, 10)}:${key}`;
const DAY_UPDATE = 'UPDATE rate_hits SET ts = ts + 1 WHERE k = ?1 AND ts < ?2 RETURNING ts';
const DAY_INSERT = 'INSERT INTO rate_hits (k, ts) SELECT ?1, ?2 WHERE NOT EXISTS (SELECT 1 FROM rate_hits WHERE k = ?1) RETURNING ts';

/**
 * Relay limits in one D1 batch: the per-minute sliding window and the per-day counter. Returns { allowed, retryAfterSec, reason }.
 * Both count the attempt; a request refused by the minute window still uses a unit of the day (a flood should not get a free day).
 */
export async function checkRelay(db, userId, dayLimit, now = Date.now()) {
  const { limit, windowMs } = LIMITS.relayMin;
  const dk = dayKey('relayDay', userId, now);
  const stmts = [
    db.prepare(INSERT_SQL).bind(`relayMin:${userId}`, now, now - windowMs, limit),
    db.prepare(DAY_UPDATE).bind(dk, DAY_BASE + dayLimit),
    db.prepare(DAY_INSERT).bind(dk, DAY_BASE + 1),
  ];
  if (Math.random() < 0.04) {
    stmts.push(db.prepare('DELETE FROM rate_hits WHERE ts <= ?').bind(now - MAX_WINDOW_MS));
    stmts.push(db.prepare("DELETE FROM rate_hits WHERE k >= 'relayDay:' AND k < ?").bind(`relayDay:${new Date(now - 2 * DAY_MS).toISOString().slice(0, 10)}`));
  }
  const res = await db.batch(stmts);
  const minuteOk = (res[0].results?.length ?? 0) > 0;
  const dayOk = (res[1].results?.length ?? 0) > 0 || (res[2].results?.length ?? 0) > 0;
  if (minuteOk && dayOk) return { allowed: true, retryAfterSec: 0, reason: '' };
  if (!dayOk) {
    const midnight = Date.UTC(new Date(now).getUTCFullYear(), new Date(now).getUTCMonth(), new Date(now).getUTCDate() + 1);
    return { allowed: false, retryAfterSec: Math.max(1, Math.ceil((midnight - now) / 1000)), reason: 'day' };
  }
  const row = await db.prepare('SELECT min(ts) AS oldest FROM rate_hits WHERE k = ? AND ts > ?').bind(`relayMin:${userId}`, now - windowMs).first();
  return { allowed: false, retryAfterSec: Math.max(1, Math.ceil(((row?.oldest ?? now) + windowMs - now) / 1000)), reason: 'minute' };
}
