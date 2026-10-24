// Account primitives for the Workers runtime: WebCrypto only (no node:crypto), so it also runs under
// Node 22 for the unit tests. Username rules, cookie names and recovery-code format match server/src/auth.js.

export const SESSION_COOKIE = 'liuying_sid';
export const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;

// ---- Password hashing: PBKDF2-HMAC-SHA256 (native WebCrypto) ----
// Workers Free allows 10 ms CPU per request, which rules out scrypt/argon2 in JS or WASM. PBKDF2 runs natively.
// Workers caps PBKDF2 at 100000 iterations. The count actually used is PBKDF2_ITERATIONS below (chosen by
// measurement, see docs/cloudflare.md). It is stored in every hash, so it can be raised on a paid plan:
// verifyPassword honours the stored value and needsRehash() upgrades hashes on the next successful login.
// Format: pbkdf2-sha256$<iterations>$<saltB64>$<hashB64>
export const PBKDF2_ITERATIONS = 15000; // overridable with the PBKDF2_ITERATIONS env var (see paramsFromEnv)
export const MAX_ITERATIONS = 100000; // Workers hard cap
const KEYLEN = 32;
const enc = new TextEncoder();

export function paramsFromEnv(env) {
  const n = Number.parseInt(env?.PBKDF2_ITERATIONS ?? '', 10);
  return { iterations: Number.isInteger(n) && n >= 1000 && n <= MAX_ITERATIONS ? n : PBKDF2_ITERATIONS };
}

const b64 = (bytes) => {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
};
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export const b64url = (bytes) => b64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
export const toHex = (buf) => [...new Uint8Array(buf)].map((x) => x.toString(16).padStart(2, '0')).join('');

async function pbkdf2(password, salt, iterations, bytes) {
  const key = await crypto.subtle.importKey('raw', enc.encode(password.normalize('NFKC')), 'PBKDF2', false, ['deriveBits']);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, key, bytes * 8));
}

export function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a[i] ^ b[i];
  return d === 0;
}

export async function hashPassword(password, params = { iterations: PBKDF2_ITERATIONS }) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const key = await pbkdf2(password, salt, params.iterations, KEYLEN);
  return ['pbkdf2-sha256', params.iterations, b64(salt), b64(key)].join('$');
}

export function parseHash(stored) {
  const parts = String(stored ?? '').split('$');
  if (parts.length !== 4 || parts[0] !== 'pbkdf2-sha256') return null;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > MAX_ITERATIONS) return null;
  try {
    const salt = unb64(parts[2]); const hash = unb64(parts[3]);
    if (!salt.length || !hash.length) return null;
    return { iterations, salt, hash };
  } catch { return null; }
}

export async function verifyPassword(password, stored) {
  const s = parseHash(stored);
  if (!s) return false;
  const key = await pbkdf2(password, s.salt, s.iterations, s.hash.length);
  return timingSafeEqual(key, s.hash);
}

/** Burn the same CPU as a real verification (unknown user / migrated account without a hash). */
export async function dummyVerify(password, params = { iterations: PBKDF2_ITERATIONS }) {
  await pbkdf2(String(password), new Uint8Array(16), params.iterations, KEYLEN);
  return false;
}

export function needsRehash(stored, params = { iterations: PBKDF2_ITERATIONS }) {
  const s = parseHash(stored);
  return !s || s.iterations < params.iterations || s.hash.length !== KEYLEN; // upgrade only, never downgrade
}

const COMMON = new Set(`password 12345678 123456789 1234567890 qwertyui qwertyuiop 11111111 00000000 87654321
password1 password12 password123 passw0rd p@ssw0rd p@ssword abc12345 abcd1234 abcdefgh 1q2w3e4r 1qaz2wsx
qwerty123 qwerty12 iloveyou1 iloveyou letmein1 letmein12 welcome1 welcome123 admin123 administrator
trustno1 monkey123 dragon123 football1 baseball1 superman1 sunshine1 princess1 master123 shadow123
changeme changeme1 12341234 123123123 111111111 123456789a a1234567 zaq12wsx asdfghjk asdfghjkl
liuying1 liuying123 liuying12`.split(/\s+/).filter(Boolean));

// Unicode usernames: letters, numbers, combining marks (after a base character) and underscore,
// 2-24 code points after NFKC. Whitespace, control and format characters never match.
const USERNAME_RE = /^[\p{L}\p{N}_][\p{L}\p{N}\p{M}_]*$/u;
export const USERNAME_MSG = 'Username must be 2-24 characters: letters (any language), numbers or underscore.';

/** NFKC-normalised, trimmed display form. */
export function normalizeUsername(u) {
  return typeof u === 'string' ? u.normalize('NFKC').trim() : '';
}
/** Case-folded unique key (NFKC, then lower-case, then NFKC again). */
export function usernameKey(u) {
  return normalizeUsername(u).toLowerCase().normalize('NFKC');
}

export function validateUsername(u) {
  if (typeof u !== 'string' || u.length > 96) return USERNAME_MSG;
  const n = u.normalize('NFKC');
  const len = [...n].length;
  if (n !== n.trim() || len < 2 || len > 24 || !USERNAME_RE.test(n)) return USERNAME_MSG;
  return null;
}

export function validatePassword(p, username) {
  if (typeof p !== 'string' || p.length < 8 || p.length > 128) {
    return 'Password must be 8-128 characters.';
  }
  if (typeof username === 'string' && p.toLowerCase() === username.toLowerCase() || usernameKey(p) === usernameKey(username)) {
    return 'Password must not be the same as your username.';
  }
  if (COMMON.has(p.toLowerCase())) {
    return 'That password is too common. Please choose a different one.';
  }
  return null;
}

// ---- Sessions ----
export function newToken() {
  return b64url(crypto.getRandomValues(new Uint8Array(32)));
}
export async function sha256Hex(text) {
  return toHex(await crypto.subtle.digest('SHA-256', enc.encode(text)));
}
export const hashToken = (token) => sha256Hex(token);

export function parseCookies(header) {
  const out = {};
  if (!header) return out;
  for (const part of header.split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    const k = part.slice(0, i).trim();
    if (k && !(k in out)) {
      try { out[k] = decodeURIComponent(part.slice(i + 1).trim()); } catch { /* ignore */ }
    }
  }
  return out;
}

/** Set-Cookie value matching Express res.cookie / res.clearCookie with the original options. */
export function sessionCookie(token, { secure = true } = {}) {
  const exp = new Date(Date.now() + SESSION_TTL_MS).toUTCString();
  return `${SESSION_COOKIE}=${encodeURIComponent(token)}; Max-Age=${Math.floor(SESSION_TTL_MS / 1000)}; Path=/; Expires=${exp}; HttpOnly; ${secure ? 'Secure; ' : ''}SameSite=Lax`;
}
export function clearedCookie({ secure = true } = {}) {
  return `${SESSION_COOKIE}=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; ${secure ? 'Secure; ' : ''}SameSite=Lax`;
}

// ---- Recovery codes (optional, no email) ----
// 20 characters of Crockford base32 = 100 bits of randomness, shown once as XXXXX-XXXXX-XXXXX-XXXXX.
// The codes are machine-generated (not human-chosen), so a single SHA-256 is sufficient: brute force is
// infeasible at 100 bits regardless of hash speed, and a slow hash would only burn the 10 ms CPU budget.
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
export function newRecoveryCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(20));
  let out = '';
  for (let i = 0; i < 20; i++) out += CROCKFORD[bytes[i] & 31];
  return out.match(/.{5}/g).join('-');
}
/** Uppercase, Crockford look-alikes folded (O->0, I/L->1), separators dropped. Returns '' unless 20 valid characters. */
export function normalizeRecoveryCode(input) {
  if (typeof input !== 'string' || input.length > 64) return '';
  const s = input.toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
  return /^[0-9A-HJKMNP-TV-Z]{20}$/.test(s) ? s : '';
}
export const hashRecoveryCode = (normalized) => sha256Hex(`liuying-recovery:${normalized}`);
export async function recoveryMatches(normalized, storedHex) {
  if (!normalized || typeof storedHex !== 'string' || storedHex.length !== 64) return false;
  const a = enc.encode(await hashRecoveryCode(normalized));
  const b = enc.encode(storedHex);
  return timingSafeEqual(a, b);
}
