import crypto from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(crypto.scrypt);

export const DEFAULT_PARAMS = { N: 2 ** 15, r: 8, p: 1, keylen: 64 };
const MAX_N = 2 ** 17; // refuse absurd stored params

export const SESSION_COOKIE = 'liuying_sid';
export const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;

const opts = (N, r, p) => ({ N, r, p, maxmem: 256 * N * r + 1024 * 1024 });

// Format: scrypt$N$r$p$saltB64$hashB64  (params are stored so they can be upgraded)
export async function hashPassword(password, params = DEFAULT_PARAMS) {
  const salt = crypto.randomBytes(16);
  const key = await scrypt(password.normalize('NFKC'), salt, params.keylen, opts(params.N, params.r, params.p));
  return ['scrypt', params.N, params.r, params.p, salt.toString('base64'), key.toString('base64')].join('$');
}

function parse(stored) {
  const parts = String(stored).split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return null;
  const [N, r, p] = parts.slice(1, 4).map(Number);
  if (![N, r, p].every(Number.isInteger) || N < 2 || N > MAX_N || r < 1 || p < 1) return null;
  const salt = Buffer.from(parts[4], 'base64');
  const hash = Buffer.from(parts[5], 'base64');
  if (!salt.length || !hash.length) return null;
  return { N, r, p, salt, hash };
}

export async function verifyPassword(password, stored) {
  const s = parse(stored);
  if (!s) return false;
  const key = await scrypt(password.normalize('NFKC'), s.salt, s.hash.length, opts(s.N, s.r, s.p));
  return key.length === s.hash.length && crypto.timingSafeEqual(key, s.hash);
}

export function needsRehash(stored, params = DEFAULT_PARAMS) {
  const s = parse(stored);
  return !s || s.N !== params.N || s.r !== params.r || s.p !== params.p || s.hash.length !== params.keylen;
}

const COMMON = new Set(`password 12345678 123456789 1234567890 qwertyui qwertyuiop 11111111 00000000 87654321
password1 password12 password123 passw0rd p@ssw0rd p@ssword abc12345 abcd1234 abcdefgh 1q2w3e4r 1qaz2wsx
qwerty123 qwerty12 iloveyou1 iloveyou letmein1 letmein12 welcome1 welcome123 admin123 administrator
trustno1 monkey123 dragon123 football1 baseball1 superman1 sunshine1 princess1 master123 shadow123
changeme changeme1 12341234 123123123 111111111 123456789a a1234567 zaq12wsx asdfghjk asdfghjkl
liuying1 liuying123 liuying12`.split(/\s+/).filter(Boolean));

const USERNAME_RE = /^[A-Za-z0-9_]{3,24}$/;

export function validateUsername(u) {
  if (typeof u !== 'string' || !USERNAME_RE.test(u)) {
    return 'Username must be 3-24 characters: letters, digits or underscore.';
  }
  return null;
}

export function validatePassword(p, username) {
  if (typeof p !== 'string' || p.length < 8 || p.length > 128) {
    return 'Password must be 8-128 characters.';
  }
  if (typeof username === 'string' && p.toLowerCase() === username.toLowerCase()) {
    return 'Password must not be the same as your username.';
  }
  if (COMMON.has(p.toLowerCase())) {
    return 'That password is too common. Please choose a different one.';
  }
  return null;
}

export function newToken() {
  return crypto.randomBytes(32).toString('base64url');
}
export function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

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

export function cookieOptions(production) {
  return { httpOnly: true, sameSite: 'lax', path: '/', secure: !!production };
}
