import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  hashPassword, verifyPassword, needsRehash, parseHash, validateUsername, validatePassword, usernameKey, normalizeUsername,
  newRecoveryCode, normalizeRecoveryCode, hashRecoveryCode, recoveryMatches, parseCookies, paramsFromEnv, MAX_ITERATIONS,
} from '../src/auth.js';
import { clientIp } from '../src/clientIp.js';
import { createLimiter } from '../src/rateLimit.js';
import { securityHeaders } from '../src/http.js';

test('password hash: self-describing format, verify, rehash policy', async () => {
  const h = await hashPassword('correct horse battery', { iterations: 1000 });
  assert.match(h, /^pbkdf2-sha256\$1000\$[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+$/);
  assert.equal(await verifyPassword('correct horse battery', h), true);
  assert.equal(await verifyPassword('wrong', h), false);
  assert.equal(await verifyPassword('correct horse battery', 'scrypt$32768$8$1$AAAA$BBBB'), false); // legacy scrypt hashes never verify
  assert.equal(await verifyPassword('x', null), false);
  assert.equal(await verifyPassword('x', 'pbkdf2-sha256$999999999$AAAA$BBBB'), false); // above the Workers cap
  assert.equal(needsRehash(h, { iterations: 1000 }), false);
  assert.equal(needsRehash(h, { iterations: 100000 }), true);
  assert.equal(needsRehash(h, { iterations: 500 }), false); // never downgraded
  assert.equal(needsRehash('scrypt$1$2$3$4$5', { iterations: 1000 }), true);
  assert.equal(parseHash(h).iterations, 1000);
  // two hashes of the same password differ (random salt)
  assert.notEqual(h, await hashPassword('correct horse battery', { iterations: 1000 }));
  // NFKC: fullwidth and ASCII forms of a password are the same password
  const f = await hashPassword('ｐａｓｓｗｏｒｄ１２３４', { iterations: 1000 });
  assert.equal(await verifyPassword('password1234', f), true);
});

test('stored iteration count is honoured so it can be raised later', async () => {
  const low = await hashPassword('abcdefgh1!', { iterations: 1000 });
  const high = await hashPassword('abcdefgh1!', { iterations: 3000 });
  assert.equal(await verifyPassword('abcdefgh1!', low), true);
  assert.equal(await verifyPassword('abcdefgh1!', high), true);
  assert.equal(parseHash(high).iterations, 3000);
});

test('PBKDF2_ITERATIONS env override is clamped to the Workers cap', () => {
  assert.equal(paramsFromEnv({ PBKDF2_ITERATIONS: '100000' }).iterations, 100000);
  assert.equal(paramsFromEnv({ PBKDF2_ITERATIONS: '100001' }).iterations, 15000);
  assert.equal(paramsFromEnv({ PBKDF2_ITERATIONS: 'abc' }).iterations, 15000);
  assert.equal(paramsFromEnv({}).iterations, 15000);
  assert.equal(MAX_ITERATIONS, 100000);
});

test('usernames: CJK, code-point length, NFKC folding', () => {
  assert.equal(validateUsername('李雷'), null);
  assert.equal(validateUsername('𠀀'.repeat(24)), null);
  assert.ok(validateUsername('𠀀'.repeat(25)));
  assert.ok(validateUsername('a'));
  assert.ok(validateUsername(' ab'));
  assert.ok(validateUsername('a b'));
  assert.ok(validateUsername('ab​'));
  assert.ok(validateUsername(5));
  assert.equal(validateUsername('Émile_é'), null);
  assert.equal(validateUsername('한국어사용자'), null);
  assert.equal(usernameKey('Émile'), usernameKey('éMILE'));
  assert.equal(normalizeUsername('Ｂｏｂ_1'), 'Bob_1');
  assert.equal(usernameKey('Ｂｏｂ_1'), 'bob_1');
  assert.equal(usernameKey('ｶﾀｶﾅ'), usernameKey('カタカナ'));
  assert.equal(usernameKey(undefined), '');
});

test('password rules', () => {
  assert.ok(validatePassword('short', 'bob'));
  assert.ok(validatePassword('password123', 'bob'));
  assert.ok(validatePassword('BobBobBob', 'bobbobbob'));
  assert.equal(validatePassword('correct horse battery', 'bob'), null);
});

test('recovery codes: format, normalisation, single SHA-256 hash', async () => {
  const c = newRecoveryCode();
  assert.match(c, /^[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}$/);
  const n = normalizeRecoveryCode(c);
  assert.equal(normalizeRecoveryCode(c.toLowerCase().replace(/-/g, ' ')), n);
  assert.equal(normalizeRecoveryCode('0'.repeat(19) + 'O'), '0'.repeat(20));
  assert.equal(normalizeRecoveryCode('short'), '');
  assert.equal(normalizeRecoveryCode(12345), '');
  assert.equal(normalizeRecoveryCode('U'.repeat(20)), '');
  const h = await hashRecoveryCode(n);
  assert.match(h, /^[0-9a-f]{64}$/);
  assert.equal(await recoveryMatches(n, h), true);
  assert.equal(await recoveryMatches(n, await hashRecoveryCode(normalizeRecoveryCode(newRecoveryCode()))), false);
  assert.equal(await recoveryMatches('', h), false);
  // same value the Express server stores, so recovery hashes migrate unchanged
  const crypto = await import('node:crypto');
  assert.equal(h, crypto.createHash('sha256').update(`liuying-recovery:${n}`).digest('hex'));
});

test('cookies and client ip', () => {
  assert.deepEqual(parseCookies('a=1; liuying_sid=abc%20d; a=2'), { a: '1', liuying_sid: 'abc d' });
  const mk = (ip) => new Request('https://x.test/', { headers: ip ? { 'cf-connecting-ip': ip, 'x-forwarded-for': '6.6.6.6' } : {} });
  assert.equal(clientIp(mk('203.0.113.9')), '203.0.113.9');
  assert.equal(clientIp(mk('::ffff:198.51.100.7')), '198.51.100.7');
  assert.equal(clientIp(mk(null)), 'unknown');
});

test('in-memory sliding-window limiter', () => {
  const clock = { t: 1000 };
  const l = createLimiter({ limit: 3, windowMs: 1000, now: () => clock.t });
  assert.deepEqual([l.check('k').allowed, l.check('k').allowed, l.check('k').allowed], [true, true, true]);
  const blocked = l.check('k');
  assert.equal(blocked.allowed, false);
  assert.equal(blocked.retryAfterSec, 1);
  assert.equal(l.check('other').allowed, true);
  clock.t += 1001;
  assert.equal(l.check('k').allowed, true);
});

test('security headers match the Express/helmet set', () => {
  const h = securityHeaders(false);
  assert.match(h['Content-Security-Policy'], /^default-src 'self';script-src 'self';style-src 'self';img-src 'self' data:;/);
  assert.match(h['Content-Security-Policy'], /frame-ancestors 'none';upgrade-insecure-requests$/);
  assert.match(securityHeaders(true)['Content-Security-Policy'], /connect-src 'self' https:/);
  assert.equal(h['X-Frame-Options'], 'DENY');
  assert.equal(h['Strict-Transport-Security'], 'max-age=31536000; includeSubDomains');
});
