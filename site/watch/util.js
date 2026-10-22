// Small shared helpers.
export const clamp = (n, a, b) => Math.min(b, Math.max(a, n));

export function debounce(fn, ms) {
  let t;
  const d = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  d.cancel = () => clearTimeout(t);
  d.flush = (...a) => { clearTimeout(t); fn(...a); };
  return d;
}

/** FNV-1a hash -> short base36 id, used to refer to sources in hashes. */
export function shortId(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; }
  return h.toString(36);
}

/** 3725 -> "1:02:05"; 65 -> "1:05" */
export function fmtTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) sec = 0;
  const s = Math.floor(sec % 60), m = Math.floor((sec / 60) % 60), h = Math.floor(sec / 3600);
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
}

export function fmtDuration(ms) {
  const m = Math.round(ms / 60000);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

/** Upgrade http image urls (CSP only allows https:); reject other schemes. */
export function imgUrl(u) {
  if (typeof u !== 'string' || !u) return '';
  try {
    const x = new URL(u);
    if (x.protocol === 'http:') x.protocol = 'https:';
    return x.protocol === 'https:' ? x.href : '';
  } catch { return ''; }
}

export const yearOf = (m) => {
  const s = String(m?.year ?? m?.releaseInfo ?? m?.released ?? '');
  const x = s.match(/\d{4}/);
  return x ? x[0] : '';
};

export const uniqBy = (arr, key) => { const seen = new Set(); return arr.filter((x) => { const k = key(x); if (seen.has(k)) return false; seen.add(k); return true; }); };

export const typeLabel = (t) => ({ movie: 'Movie', series: 'Series', tv: 'TV', channel: 'Channel' }[t] || (t ? t[0].toUpperCase() + t.slice(1) : 'Other'));
export const VERSION = '0.1.0';
