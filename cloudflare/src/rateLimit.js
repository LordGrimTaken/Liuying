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
};
export const SYNC_LIMIT = { limit: 60, windowMs: MIN };
const MAX_WINDOW_MS = 60 * MIN;

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
