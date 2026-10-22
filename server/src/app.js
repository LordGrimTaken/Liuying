import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';
import helmet from 'helmet';
import {
  DEFAULT_PARAMS, SESSION_COOKIE, SESSION_TTL_MS, cookieOptions, hashPassword, hashToken,
  needsRehash, newToken, parseCookies, validatePassword, validateUsername, verifyPassword,
} from './auth.js';
import { createLimiter } from './rateLimit.js';
import { ApiError } from './errors.js';
import { createSyncRouter } from './sync.js';

const DEFAULT_SITE = fileURLToPath(new URL('../../site', import.meta.url));
const MIN = 60 * 1000;

const jsonLogger = {
  info: (o) => console.log(JSON.stringify({ level: 'info', ...o })),
  error: (o) => console.error(JSON.stringify({ level: 'error', ...o })),
};

export function createApp({
  db,
  siteDir = DEFAULT_SITE,
  production = process.env.NODE_ENV === 'production',
  logger = jsonLogger,
  hashParams = DEFAULT_PARAMS,
  now = () => Date.now(),
  limits = {},
} = {}) {
  const app = express();
  app.disable('x-powered-by');
  if (production) app.set('trust proxy', 1);

  const loginPair = createLimiter({ limit: 10, windowMs: 15 * MIN, now, ...limits.loginPair });
  const loginIp = createLimiter({ limit: 30, windowMs: 15 * MIN, now, ...limits.loginIp });
  const signupIp = createLimiter({ limit: 5, windowMs: 60 * MIN, now, ...limits.signupIp });

  const syncLimiter = createLimiter({ limit: 60, windowMs: MIN, now, ...limits.sync });

  const cookieOpts = cookieOptions(production);
  const setSessionCookie = (res, token) =>
    res.cookie(SESSION_COOKIE, token, { ...cookieOpts, maxAge: SESSION_TTL_MS });
  const clearSessionCookie = (res) => res.clearCookie(SESSION_COOKIE, cookieOpts);

  // Dummy hash so unknown-user logins cost the same as real ones.
  const dummyHash = hashPassword('dummy-password-for-timing', hashParams);

  // Strict CSP everywhere except the web player under /watch, which must fetch manifests and
  // streams from arbitrary https addon hosts (see README, "Content-Security-Policy").
  const cspDirectives = (extra) => ({
    'default-src': ["'self'"],
    'script-src': ["'self'"],
    'style-src': ["'self'"],
    'img-src': ["'self'", 'data:'],
    'font-src': ["'self'"],
    'connect-src': ["'self'"],
    'object-src': ["'none'"],
    'base-uri': ["'self'"],
    'form-action': ["'self'"],
    'frame-ancestors': ["'none'"],
    ...extra,
    ...(production ? { 'upgrade-insecure-requests': [] } : {}),
  });
  const helmetWith = (directives) => helmet({
    contentSecurityPolicy: { useDefaults: false, directives },
    strictTransportSecurity: production,
  });
  const strictSecurity = helmetWith(cspDirectives());
  const watchSecurity = helmetWith(cspDirectives({
    'connect-src': ["'self'", 'https:'],
    'img-src': ["'self'", 'data:', 'blob:', 'https:'],
    'media-src': ["'self'", 'blob:', 'https:'],
    'worker-src': ["'self'", 'blob:'],
  }));
  const isWatchPath = (p) => { const l = p.toLowerCase(); return l === '/watch' || l.startsWith('/watch/'); };
  app.use((req, res, next) => (isWatchPath(req.path) ? watchSecurity : strictSecurity)(req, res, next));

  app.get('/health', async (req, res) => {
    try {
      if (db?.persistent) await db.query('SELECT 1');
      res.status(200).type('text/plain').send('ok');
    } catch (err) {
      logger.error({ msg: 'health check failed', error: err.message });
      res.status(503).type('text/plain').send('database unavailable');
    }
  });

  // ---- API ----
  const api = express.Router();
  api.use((req, res, next) => { res.set('Cache-Control', 'no-store'); next(); });

  // CSRF: Origin (when present) must match Host; JSON content type required on POST.
  api.use((req, res, next) => {
    if (req.method === 'GET' || req.method === 'HEAD' || req.method === 'OPTIONS') return next();
    const origin = req.get('origin');
    if (origin !== undefined) {
      let ok = false;
      try { ok = new URL(origin).host === req.get('host'); } catch { /* invalid */ }
      if (!ok) return next(new ApiError(403, 'forbidden', 'Cross-origin request rejected.'));
    }
    // Bodiless DELETE (e.g. DELETE /api/sync/all) needs only the Origin check.
    if (req.method !== 'DELETE' && !/^application\/json\s*(;|$)/i.test(req.get('content-type') || '')) {
      return next(new ApiError(415, 'unsupported_media_type', 'Content-Type must be application/json.'));
    }
    next();
  });

  // Sync routes authenticate first and size their own body parsers (256kb / 16kb).
  api.use('/sync', createSyncRouter({ db, now, limiter: syncLimiter }));
  api.use(express.json({ limit: '2kb' }));

  const bodyObject = (req) => {
    const b = req.body;
    if (!b || typeof b !== 'object' || Array.isArray(b)) {
      throw new ApiError(400, 'bad_request', 'Request body must be a JSON object.');
    }
    return b;
  };

  const limited = (retryAfterSec) =>
    new ApiError(429, 'rate_limited', 'Too many attempts. Please try again later.',
      { 'Retry-After': String(retryAfterSec) });

  const purgeExpired = () =>
    db.query('DELETE FROM sessions WHERE expires_at <= $1', [new Date(now())])
      .catch((err) => logger.error({ msg: 'session purge failed', error: err.message }));

  async function createSession(res, userId, req) {
    // rotate: drop any session presented with this request
    const old = parseCookies(req.headers.cookie)[SESSION_COOKIE];
    if (old) await db.query('DELETE FROM sessions WHERE token_hash = $1', [hashToken(old)]);
    const token = newToken();
    const t = now();
    await db.query(
      'INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES ($1, $2, $3, $4)',
      [hashToken(token), userId, new Date(t), new Date(t + SESSION_TTL_MS)],
    );
    setSessionCookie(res, token);
    purgeExpired();
  }

  api.post('/auth/signup', async (req, res) => {
    const ip = req.ip;
    const rl = signupIp.check(ip);
    if (!rl.allowed) throw limited(rl.retryAfterSec);

    const { username, password } = bodyObject(req);
    const uErr = validateUsername(username);
    if (uErr) throw new ApiError(400, 'invalid_username', uErr);
    const pErr = validatePassword(password, username);
    if (pErr) throw new ApiError(400, 'invalid_password', pErr);

    const lower = username.toLowerCase();
    const taken = new ApiError(409, 'username_taken', 'That username is already taken.');
    const existing = await db.query('SELECT 1 FROM users WHERE username_lower = $1', [lower]);
    if (existing.rows.length) throw taken;

    const id = crypto.randomUUID();
    const hash = await hashPassword(password, hashParams);
    try {
      await db.query(
        'INSERT INTO users (id, username, username_lower, password_hash, created_at) VALUES ($1, $2, $3, $4, $5)',
        [id, username, lower, hash, new Date(now())],
      );
    } catch (err) {
      if (err.code === '23505' || /unique|duplicate/i.test(err.message)) throw taken;
      throw err;
    }
    await createSession(res, id, req);
    res.status(201).json({ user: { username } });
  });

  api.post('/auth/login', async (req, res) => {
    const { username, password } = bodyObject(req);
    if (typeof username !== 'string' || typeof password !== 'string') {
      throw new ApiError(400, 'bad_request', 'Username and password are required.');
    }
    const ip = req.ip;
    const key = `${ip}|${username.toLowerCase().slice(0, 64)}`;
    const a = loginPair.check(key);
    const b = loginIp.check(ip);
    if (!a.allowed || !b.allowed) throw limited(Math.max(a.retryAfterSec, b.retryAfterSec));

    const bad = new ApiError(401, 'invalid_credentials', 'Incorrect username or password.');
    const validShape = !validateUsername(username) && password.length >= 1 && password.length <= 128;
    let user = null;
    if (validShape) {
      const r = await db.query(
        'SELECT id, username, password_hash FROM users WHERE username_lower = $1', [username.toLowerCase()],
      );
      user = r.rows[0] || null;
    }
    const ok = await verifyPassword(validShape ? password : 'x', user ? user.password_hash : await dummyHash);
    if (!user || !ok) throw bad;

    if (needsRehash(user.password_hash, hashParams)) {
      try {
        await db.query('UPDATE users SET password_hash = $1 WHERE id = $2',
          [await hashPassword(password, hashParams), user.id]);
      } catch (err) { logger.error({ msg: 'rehash failed', error: err.message }); }
    }
    await createSession(res, user.id, req);
    res.status(200).json({ user: { username: user.username } });
  });

  api.post('/auth/logout', async (req, res) => {
    const token = parseCookies(req.headers.cookie)[SESSION_COOKIE];
    if (token) await db.query('DELETE FROM sessions WHERE token_hash = $1', [hashToken(token)]);
    clearSessionCookie(res);
    res.status(204).end();
  });

  api.get('/auth/me', async (req, res) => {
    const token = parseCookies(req.headers.cookie)[SESSION_COOKIE];
    if (token) {
      const r = await db.query(
        `SELECT u.username FROM sessions s JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = $1 AND s.expires_at > $2`,
        [hashToken(token), new Date(now())],
      );
      if (r.rows[0]) return res.status(200).json({ user: { username: r.rows[0].username } });
      purgeExpired();
      clearSessionCookie(res);
    }
    res.status(401).json({ error: 'unauthenticated', message: 'You are not signed in.' });
  });

  api.use((req, res) => {
    res.status(404).json({ error: 'not_found', message: 'Not found.' });
  });

  // Express 4 does not catch async rejections: wrap handlers.
  const wrapAsync = (router) => {
    for (const layer of router.stack) {
      if (layer.route) {
        for (const l of layer.route.stack) {
          const fn = l.handle;
          l.handle = (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
        }
      } else if (layer.handle?.stack) wrapAsync(layer.handle);
    }
  };
  wrapAsync(api);
  app.use('/api', api);

  // ---- Static site ----
  const pages = { '/login': 'login.html', '/signup': 'signup.html', '/account': 'account.html' };
  for (const [route, file] of Object.entries(pages)) {
    app.get(route, (req, res, next) => {
      const full = path.join(siteDir, file);
      fs.access(full, (err) => (err ? notFound(req, res) : res.sendFile(full, (e) => e && next(e))));
    });
  }
  // Web player: /watch and /watch/* serve the SPA shell; real files under site/watch are served as-is.
  const watchDir = path.join(siteDir, 'watch');
  app.use('/watch', express.static(watchDir, { index: false, dotfiles: 'ignore', redirect: false }));
  app.get(['/watch', '/watch/*'], (req, res, next) => {
    if (path.extname(req.path)) return notFound(req, res); // missing asset, not an app route
    res.sendFile('index.html', { root: watchDir, dotfiles: 'allow' }, (e) => {
      if (!e) return;
      if (e.code === 'ENOENT' || e.status === 404) return notFound(req, res);
      next(e);
    });
  });
  app.use(express.static(siteDir, { index: 'index.html', dotfiles: 'ignore', redirect: false }));

  function notFound(req, res) {
    res.status(404).type('text/plain').send('Not found');
  }
  app.use(notFound);

  // ---- Errors ----
  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    let status = err.status || err.statusCode || 500;
    let code = err.code && err.status ? err.code : null;
    let message = err.message;
    if (err instanceof ApiError) {
      if (err.headers) res.set(err.headers);
    } else if (err.type === 'entity.parse.failed') {
      status = 400; code = 'bad_request'; message = 'Request body is not valid JSON.';
    } else if (err.type === 'entity.too.large') {
      if (req.path.startsWith('/api/sync/')) { status = 413; code = 'payload_too_large'; message = 'Request body is too large.'; }
      else { status = 400; code = 'bad_request'; message = 'Request body is too large.'; }
    } else if (status >= 400 && status < 500) {
      code = 'bad_request'; message = 'Bad request.';
    } else {
      status = 500; code = 'internal_error'; message = 'Something went wrong. Please try again.';
      // Log message/name only: never bodies, never stacks to clients.
      logger.error({ msg: 'unhandled error', method: req.method, path: req.path, error: err.message, stack: err.stack });
    }
    if (res.headersSent) return;
    if (req.path.startsWith('/api/') || status === 500) res.status(status).json({ error: code, message });
    else res.status(status).type('text/plain').send(message);
  });

  return app;
}
