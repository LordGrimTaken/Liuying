// /api/* handler: a port of server/src/app.js (auth routes) onto Workers + D1.
import {
  SESSION_COOKIE, SESSION_TTL_MS, clearedCookie, dummyVerify, hashPassword, hashRecoveryCode, hashToken, needsRehash,
  newRecoveryCode, newToken, normalizeRecoveryCode, normalizeUsername, paramsFromEnv, parseCookies, recoveryMatches,
  sessionCookie, usernameKey, validatePassword, validateUsername, verifyPassword,
} from './auth.js';
import { ApiError } from './errors.js';
import { clientIp } from './clientIp.js';
import { checkD1, checkRelay, createLimiter, SYNC_LIMIT } from './rateLimit.js';
import { parseRelaySpec, relayFetch } from './relay.js';
import { LIMITS as RELAY_LIMITS } from '../../site/watch/plugins/rules.js';
import { handleSync } from './sync.js';
import { json, respond, parseJsonBody } from './http.js';

const MAX_SESSIONS = 20;
const publicId = (tokenHash) => tokenHash.slice(0, 16);
const syncMemLimiter = createLimiter(SYNC_LIMIT); // per-isolate fallback when the rate-limit binding is absent

const limited = (retryAfterSec) =>
  new ApiError(429, 'rate_limited', 'Too many attempts. Please try again later.', { 'Retry-After': String(retryAfterSec) });

function csrf(request, url) {
  const m = request.method;
  if (m === 'GET' || m === 'HEAD' || m === 'OPTIONS') return;
  if (request.headers.get('sec-fetch-site') === 'cross-site') throw new ApiError(403, 'forbidden', 'Cross-origin request rejected.');
  const origin = request.headers.get('origin');
  if (origin !== null) {
    let ok = false;
    try { ok = new URL(origin).host === (request.headers.get('host') || url.host); } catch { /* invalid */ }
    if (!ok) throw new ApiError(403, 'forbidden', 'Cross-origin request rejected.');
  }
  // Bodiless DELETE (e.g. DELETE /api/sync/all) needs only the Origin check.
  if (m !== 'DELETE' && !/^application\/json\s*(;|$)/i.test(request.headers.get('content-type') || '')) {
    throw new ApiError(415, 'unsupported_media_type', 'Content-Type must be application/json.');
  }
}

const bodyObject = (b) => {
  if (!b || typeof b !== 'object' || Array.isArray(b)) throw new ApiError(400, 'bad_request', 'Request body must be a JSON object.');
  return b;
};

export async function handleApi(request, env, ctx, { now = () => Date.now(), relayFetchImpl } = {}) {
  const url = new URL(request.url);
  const secure = url.protocol === 'https:';
  const cookies = { set: [] };
  const db = env.DB;
  const params = paramsFromEnv(env);
  const method = request.method === 'HEAD' ? 'GET' : request.method;
  const wait = (p) => { const q = Promise.resolve(p).catch(() => {}); if (ctx?.waitUntil) ctx.waitUntil(q); return q; };

  try {
    csrf(request, url);

    // Express matches routes case-insensitively and ignores one trailing slash.
    const raw = url.pathname.replace(/^\/api/i, '').replace(/\/+$/, '');
    const path = raw.toLowerCase();

    if (path.startsWith('/sync/') || path === '/sync') {
      const out = await syncRoute();
      if (out) return finish(out.status, out.body);
      return json(404, { error: 'not_found', message: 'Not found.' });
    }
    if (path === '/relay') {
      if (method !== 'POST') throw new ApiError(405, 'method_not_allowed', 'Use POST.', { Allow: 'POST' });
      return await relayRoute();
    }
    const out = await authRoute();
    if (out) return finish(out.status, out.body);
    return json(404, { error: 'not_found', message: 'Not found.' });

    // ------------------------------------------------------------------
    function finish(status, body) {
      if (status === 204) return respond(204, null, { 'Cache-Control': 'no-store' }, { setCookies: cookies.set });
      return json(status, body, { setCookies: cookies.set });
    }

    async function sessionRow(token) {
      return db.prepare(
        `SELECT u.id, u.username, u.password_hash, s.token_hash FROM sessions s JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = ?1 AND s.expires_at > ?2`).bind(await hashToken(token), now()).first();
    }
    async function requireUser() {
      const token = parseCookies(request.headers.get('cookie'))[SESSION_COOKIE];
      const u = token ? await sessionRow(token) : null;
      if (!u) throw new ApiError(401, 'unauthenticated', 'You are not signed in.');
      return u;
    }

    // Plugin relay for the web player's sandbox. Order: session, per-user limits, body, then the outbound request.
    // The session cookie is read here and goes no further: relayFetch only ever sees the plugin's own (sanitised) headers.
    async function relayRoute() {
      const u = await requireUser();
      const rl = await checkRelay(db, u.id, RELAY_LIMITS.PER_DAY, now());
      if (!rl.allowed) {
        throw new ApiError(429, 'rate_limited', rl.reason === 'day' ? 'Daily plugin request limit reached. Try again tomorrow.' : 'Too many plugin requests. Slow down for a moment.',
          { 'Retry-After': String(rl.retryAfterSec) });
      }
      const spec = parseRelaySpec(bodyObject(await parseJsonBody(request, 96 * 1024)));
      const result = await relayFetch(spec, { fetchImpl: relayFetchImpl, ownHosts: [url.hostname, (request.headers.get('host') || '').split(':')[0]].filter(Boolean) });
      return json(200, result);
    }

    async function syncRoute() {
      // 1. authenticate 2. per-user rate limit 3. body parsed per route (256 KB / 16 KB)
      const token = parseCookies(request.headers.get('cookie'))[SESSION_COOKIE];
      let userId = null;
      if (token) {
        const r = await db.prepare('SELECT user_id FROM sessions WHERE token_hash = ?1 AND expires_at > ?2')
          .bind(await hashToken(token), now()).first();
        userId = r?.user_id ?? null;
      }
      if (!userId) throw new ApiError(401, 'unauthenticated', 'You are not signed in.');
      // Two layers: an exact per-isolate sliding window (catches a single client hammering one location) plus
      // the Workers rate-limiting binding (approximate, shared per location). Either one can reject.
      const mem = syncMemLimiter.check(userId);
      let allowed = mem.allowed; const retryAfterSec = mem.allowed ? 60 : mem.retryAfterSec;
      if (allowed && env.SYNC_RATE) allowed = (await env.SYNC_RATE.limit({ key: `sync:${userId}` })).success;
      if (!allowed) {
        throw new ApiError(429, 'rate_limited', 'Too many requests. Please slow down.', { 'Retry-After': String(retryAfterSec) });
      }
      const sub = raw.slice('/sync'.length).toLowerCase().replace(/^$/, '/');
      return handleSync({
        method, sub, db, userId, now,
        readBody: (limit) => parseJsonBody(request, limit, { syncRoute: true }),
      });
    }

    async function createSession(userId, { skipOld = false } = {}) {
      const old = parseCookies(request.headers.get('cookie'))[SESSION_COOKIE];
      const token = newToken();
      const t = now();
      const stmts = [];
      if (old && !skipOld) stmts.push(db.prepare('DELETE FROM sessions WHERE token_hash = ?').bind(await hashToken(old)));
      stmts.push(db.prepare('INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?1, ?2, ?3, ?4)')
        .bind(await hashToken(token), userId, t, t + SESSION_TTL_MS));
      stmts.push(db.prepare('DELETE FROM sessions WHERE expires_at <= ?').bind(t));
      // Keep at most MAX_SESSIONS per user: drop the oldest beyond the cap.
      stmts.push(db.prepare(`DELETE FROM sessions WHERE user_id = ?1 AND token_hash NOT IN
        (SELECT token_hash FROM sessions WHERE user_id = ?1 ORDER BY created_at DESC LIMIT ${MAX_SESSIONS})`).bind(userId));
      cookies.set.push(sessionCookie(token, { secure }));
      return stmts;
    }

    async function checkPassword(u, password) {
      const [rl] = await checkD1(db, [['pwChange', String(u.id)]], now());
      if (!rl.allowed) throw limited(rl.retryAfterSec);
      if (typeof password !== 'string' || !password || password.length > 128 || !(await verifyPassword(password, u.password_hash))) {
        throw new ApiError(403, 'invalid_credentials', 'Incorrect password.');
      }
    }

    async function authRoute() {
      const key = `${method} ${path.replace(/^\/auth/, '')}`;
      const jsonBody = () => parseJsonBody(request, 2048);
      const sessionIdMatch = /^\/auth\/sessions\/([^/]+)$/.exec(raw);

      if (method === 'DELETE' && sessionIdMatch) return revokeOne(decodeURIComponent(sessionIdMatch[1]));
      switch (key) {
        case 'POST /signup': return signup(bodyObject(await jsonBody()));
        case 'POST /login': return login(bodyObject(await jsonBody()));
        case 'POST /logout': { await jsonBody(); return logout(); }
        case 'GET /me': return me();
        case 'POST /recover': return recover(bodyObject(await jsonBody()));
        case 'POST /password': { const u = await requireUser(); return changePassword(u, bodyObject(await jsonBody())); }
        case 'POST /delete': { const u = await requireUser(); return deleteAccount(u, bodyObject(await jsonBody())); }
        case 'GET /recovery': return recoveryStatus(await requireUser());
        case 'POST /recovery': { const u = await requireUser(); return regenRecovery(u, bodyObject(await jsonBody())); }
        case 'GET /sessions': return listSessions(await requireUser());
        case 'POST /sessions/revoke-others': return revokeOthers(await requireUser());
        case 'POST /logout-all': return logoutAll(await requireUser());
        default: return null;
      }
    }

    async function signup({ username, password, recovery }) {
      const [rl] = await checkD1(db, [['signupIp', clientIp(request)]], now());
      if (!rl.allowed) throw limited(rl.retryAfterSec);
      const uErr = validateUsername(username);
      if (uErr) throw new ApiError(400, 'invalid_username', uErr);
      const pErr = validatePassword(password, username);
      if (pErr) throw new ApiError(400, 'invalid_password', pErr);

      const lower = usernameKey(username);
      const display = normalizeUsername(username);
      const taken = new ApiError(409, 'username_taken', 'That username is already taken.');
      if (await db.prepare('SELECT 1 AS x FROM users WHERE username_lower = ?').bind(lower).first()) throw taken;

      const id = crypto.randomUUID();
      const hash = await hashPassword(password, params);
      const recoveryCode = recovery === true ? newRecoveryCode() : null;
      const t = now();
      const sess = await createSession(id);
      try {
        await db.batch([
          db.prepare(`INSERT INTO users (id, username, username_lower, password_hash, created_at, recovery_hash, recovery_created_at)
                      VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)`)
            .bind(id, display, lower, hash, t,
              recoveryCode ? await hashRecoveryCode(normalizeRecoveryCode(recoveryCode)) : null, recoveryCode ? t : null),
          ...sess,
        ]);
      } catch (err) {
        if (/unique|constraint/i.test(String(err?.message) + String(err?.cause?.message))) throw taken;
        throw err;
      }
      // The code is returned exactly once, here; only its hash is kept.
      return { status: 201, body: recoveryCode ? { user: { username: display }, recoveryCode } : { user: { username: display } } };
    }

    async function login({ username, password }) {
      if (typeof username !== 'string' || typeof password !== 'string') {
        throw new ApiError(400, 'bad_request', 'Username and password are required.');
      }
      const ip = clientIp(request);
      const uk = usernameKey(username).slice(0, 64);
      const [a, b, c] = await checkD1(db, [['loginPair', `${ip}|${uk}`], ['loginIp', ip], ['loginUser', uk]], now());
      if (!a.allowed || !b.allowed || !c.allowed) throw limited(Math.max(a.retryAfterSec, b.retryAfterSec, c.retryAfterSec));

      const bad = new ApiError(401, 'invalid_credentials', 'Incorrect username or password.');
      const validShape = !validateUsername(username) && password.length >= 1 && password.length <= 128;
      let user = null;
      if (validShape) {
        user = await db.prepare('SELECT id, username, password_hash FROM users WHERE username_lower = ?').bind(usernameKey(username)).first();
      }
      // Unknown users and migrated accounts without a hash cost the same CPU as a real check.
      const ok = user && user.password_hash ? await verifyPassword(password, user.password_hash) : await dummyVerify(validShape ? password : 'x', params);
      if (!user || !ok) throw bad;

      const stmts = [];
      if (needsRehash(user.password_hash, params)) {
        stmts.push(db.prepare('UPDATE users SET password_hash = ?1 WHERE id = ?2').bind(await hashPassword(password, params), user.id));
      }
      stmts.push(...await createSession(user.id));
      await db.batch(stmts);
      return { status: 200, body: { user: { username: user.username } } };
    }

    async function logout() {
      const token = parseCookies(request.headers.get('cookie'))[SESSION_COOKIE];
      if (token) await db.prepare('DELETE FROM sessions WHERE token_hash = ?').bind(await hashToken(token)).run();
      cookies.set.push(clearedCookie({ secure }));
      return { status: 204 };
    }

    async function me() {
      const token = parseCookies(request.headers.get('cookie'))[SESSION_COOKIE];
      if (token) {
        const r = await sessionRow(token);
        if (r) return { status: 200, body: { user: { username: r.username } } };
        wait(db.prepare('DELETE FROM sessions WHERE expires_at <= ?').bind(now()).run());
        cookies.set.push(clearedCookie({ secure }));
      }
      // ?soft=1: "nobody is signed in" is a normal answer (200 {user:null}) so pages can probe the session quietly.
      if (url.searchParams.get('soft') === '1') return { status: 200, body: { user: null } };
      return { status: 401, body: { error: 'unauthenticated', message: 'You are not signed in.' } };
    }

    // Reset a forgotten password with the recovery code. The code is single-use: a fresh one is returned.
    async function recover({ username, recoveryCode, newPassword }) {
      if (typeof username !== 'string' || typeof newPassword !== 'string') {
        throw new ApiError(400, 'bad_request', 'Username, recovery code and new password are required.');
      }
      const [a, b] = await checkD1(db, [['recoverIp', clientIp(request)], ['recoverUser', usernameKey(username).slice(0, 64)]], now());
      if (!a.allowed || !b.allowed) throw limited(Math.max(a.retryAfterSec, b.retryAfterSec));
      const bad = new ApiError(401, 'invalid_recovery', 'Incorrect username or recovery code.');
      const code = normalizeRecoveryCode(recoveryCode);
      const user = validateUsername(username) ? null
        : await db.prepare('SELECT id, username, recovery_hash FROM users WHERE username_lower = ?').bind(usernameKey(username)).first();
      const ok = await recoveryMatches(code, user?.recovery_hash || await hashRecoveryCode('0'.repeat(20)));
      if (!user || !user.recovery_hash || !ok) throw bad;
      const pErr = validatePassword(newPassword, user.username);
      if (pErr) throw new ApiError(400, 'invalid_password', pErr);
      const fresh = newRecoveryCode();
      const sess = await createSession(user.id, { skipOld: true });
      await db.batch([
        db.prepare('UPDATE users SET password_hash = ?1, recovery_hash = ?2, recovery_created_at = ?3, needs_new_password = 0 WHERE id = ?4')
          .bind(await hashPassword(newPassword, params), await hashRecoveryCode(normalizeRecoveryCode(fresh)), now(), user.id),
        db.prepare('DELETE FROM sessions WHERE user_id = ?').bind(user.id),
        ...sess,
      ]);
      return { status: 200, body: { user: { username: user.username }, recoveryCode: fresh } };
    }

    // Change password: requires the current password; every other session is revoked and the current one rotated.
    async function changePassword(u, { currentPassword, newPassword }) {
      await checkPassword(u, currentPassword);
      const pErr = validatePassword(newPassword, u.username);
      if (pErr) throw new ApiError(400, 'invalid_password', pErr);
      if (newPassword === currentPassword) throw new ApiError(400, 'invalid_password', 'Choose a password different from your current one.');
      const sess = await createSession(u.id, { skipOld: true });
      await db.batch([
        db.prepare('UPDATE users SET password_hash = ?1, needs_new_password = 0 WHERE id = ?2').bind(await hashPassword(newPassword, params), u.id),
        db.prepare('DELETE FROM sessions WHERE user_id = ?').bind(u.id),
        ...sess,
      ]);
      return { status: 200, body: { user: { username: u.username } } };
    }

    // Delete account: requires the password; removes every row owned by the user and all sessions.
    async function deleteAccount(u, { password }) {
      await checkPassword(u, password);
      await db.batch([
        ...['sessions', 'user_sources', 'user_library', 'user_progress', 'user_settings']
          .map((t) => db.prepare(`DELETE FROM ${t} WHERE user_id = ?`).bind(u.id)),
        db.prepare('DELETE FROM users WHERE id = ?').bind(u.id),
      ]);
      cookies.set.push(clearedCookie({ secure }));
      return { status: 204 };
    }

    async function recoveryStatus(u) {
      const row = await db.prepare('SELECT recovery_hash, recovery_created_at FROM users WHERE id = ?').bind(u.id).first() || {};
      return { status: 200, body: { enabled: !!row.recovery_hash, createdAt: row.recovery_created_at ? new Date(Number(row.recovery_created_at)).toISOString() : null } };
    }
    async function regenRecovery(u, { password }) {
      await checkPassword(u, password);
      const code = newRecoveryCode();
      await db.prepare('UPDATE users SET recovery_hash = ?1, recovery_created_at = ?2 WHERE id = ?3')
        .bind(await hashRecoveryCode(normalizeRecoveryCode(code)), now(), u.id).run();
      return { status: 200, body: { recoveryCode: code } };
    }

    async function listSessions(u) {
      const r = await db.prepare('SELECT token_hash, created_at, expires_at FROM sessions WHERE user_id = ?1 AND expires_at > ?2 ORDER BY created_at DESC')
        .bind(u.id, now()).all();
      return { status: 200, body: { sessions: r.results.map((x) => ({
        id: publicId(x.token_hash), current: x.token_hash === u.token_hash,
        createdAt: new Date(Number(x.created_at)).toISOString(), expiresAt: new Date(Number(x.expires_at)).toISOString(),
      })) } };
    }
    async function revokeOthers(u) {
      await db.prepare('DELETE FROM sessions WHERE user_id = ?1 AND token_hash <> ?2').bind(u.id, u.token_hash).run();
      return { status: 204 };
    }
    async function logoutAll(u) {
      await db.prepare('DELETE FROM sessions WHERE user_id = ?').bind(u.id).run();
      cookies.set.push(clearedCookie({ secure }));
      return { status: 204 };
    }
    async function revokeOne(id) {
      const u = await requireUser();
      if (!/^[0-9a-f]{16}$/.test(id)) throw new ApiError(404, 'not_found', 'Session not found.');
      const r = await db.prepare('SELECT token_hash FROM sessions WHERE user_id = ?').bind(u.id).all();
      const hit = r.results.find((x) => publicId(x.token_hash) === id);
      if (!hit) throw new ApiError(404, 'not_found', 'Session not found.');
      await db.prepare('DELETE FROM sessions WHERE token_hash = ?1 AND user_id = ?2').bind(hit.token_hash, u.id).run();
      if (hit.token_hash === u.token_hash) cookies.set.push(clearedCookie({ secure }));
      return { status: 204 };
    }
  } catch (err) {
    return errorResponse(err);
  }
}

export function errorResponse(err, log = console.error) {
  let status; let code; let message; let headers;
  if (err instanceof ApiError) {
    ({ status, code, message, headers } = err);
  } else {
    status = 500; code = 'internal_error'; message = 'Something went wrong. Please try again.';
    // Log message/name only: never bodies, never stacks to clients.
    log(JSON.stringify({ level: 'error', msg: 'unhandled error', error: String(err?.message ?? err).slice(0, 500), cause: String(err?.cause?.message ?? '').slice(0, 300) }));
  }
  return json(status, { error: code, message }, {}, headers);
}
