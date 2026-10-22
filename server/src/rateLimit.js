// In-memory sliding-window limiter. check() records a hit and reports whether it is allowed.
export function createLimiter({ limit, windowMs, now = () => Date.now(), maxKeys = 50000 }) {
  const hits = new Map(); // key -> array of timestamps (ascending)

  function prune(arr, t) {
    let i = 0;
    while (i < arr.length && arr[i] <= t - windowMs) i++;
    if (i) arr.splice(0, i);
  }

  return {
    // Returns { allowed, retryAfterSec }. Blocked attempts are not recorded.
    check(key) {
      const t = now();
      let arr = hits.get(key);
      if (!arr) {
        if (hits.size >= maxKeys) this.sweep();
        arr = [];
        hits.set(key, arr);
      }
      prune(arr, t);
      if (arr.length >= limit) {
        const retryAfterSec = Math.max(1, Math.ceil((arr[0] + windowMs - t) / 1000));
        return { allowed: false, retryAfterSec };
      }
      arr.push(t);
      return { allowed: true, retryAfterSec: 0 };
    },
    sweep() {
      const t = now();
      for (const [k, arr] of hits) {
        prune(arr, t);
        if (!arr.length) hits.delete(k);
      }
    },
    reset() { hits.clear(); },
  };
}
