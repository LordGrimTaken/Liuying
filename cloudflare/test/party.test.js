// Watch party: room lifecycle, host authority, membership and expiry.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setup } from './helpers.js';

const PW = 'correct horse battery';
const HOUR = 60 * 60 * 1000;

async function joined(ctx, name) {
  const c = new ctx.Client();
  const res = await c.post('/api/auth/signup', { username: name, password: PW });
  assert.equal(res.status, 201, `signup ${name}`);
  return c;
}

const newParty = (c, over = {}) => c.post('/api/party', {
  mediaId: 'tt0133093', episodeId: null, positionMs: 0, playing: false, ...over,
});

test('create returns a 6-letter code, rev 1 and the host flag', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  const res = await newParty(host, { positionMs: 1234, playing: true });
  assert.equal(res.status, 201);
  assert.match(res.data.code, /^[A-Z]{6}$/);
  assert.equal(res.data.rev, 1);
  assert.equal(res.data.host, true);
  assert.equal(res.data.state.mediaId, 'tt0133093');
  assert.equal(res.data.state.positionMs, 1234);
  assert.equal(res.data.state.playing, true);
  assert.equal(res.data.members.length, 1);
});

test('a guest joins, sees host=false, and appears in members', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  const guest = await joined(ctx, 'guest');
  const { code } = (await newParty(host)).data;

  const j = await guest.post(`/api/party/${code}/join`, {});
  assert.equal(j.status, 200);
  assert.equal(j.data.host, false);
  assert.equal(j.data.members.length, 2);

  const poll = await guest.get(`/api/party/${code}`);
  assert.equal(poll.status, 200);
  assert.equal(poll.data.host, false);
  assert.equal(poll.data.rev, 1);
});

test('only the host may update; rev increments and guests see it', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  const guest = await joined(ctx, 'guest');
  const { code } = (await newParty(host)).data;
  await guest.post(`/api/party/${code}/join`, {});

  const denied = await guest.put(`/api/party/${code}`, { positionMs: 5000, playing: true });
  assert.equal(denied.status, 403);
  assert.equal(denied.data.error, 'forbidden');

  const ok = await host.put(`/api/party/${code}`, { positionMs: 5000, playing: true });
  assert.equal(ok.status, 200);
  assert.equal(ok.data.rev, 2);
  assert.equal(ok.data.state.positionMs, 5000);

  const poll = await guest.get(`/api/party/${code}`);
  assert.equal(poll.data.rev, 2);
  assert.equal(poll.data.state.playing, true);
});

test('a guest leaving only removes them; the host leaving ends the party', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  const guest = await joined(ctx, 'guest');
  const { code } = (await newParty(host)).data;
  await guest.post(`/api/party/${code}/join`, {});

  assert.equal((await guest.post(`/api/party/${code}/leave`, {})).status, 204);
  assert.equal((await host.get(`/api/party/${code}`)).data.members.length, 1);

  assert.equal((await host.post(`/api/party/${code}/leave`, {})).status, 204);
  assert.equal((await host.get(`/api/party/${code}`)).status, 404);
});

test('ending a party requires the host and removes it for everyone', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  const guest = await joined(ctx, 'guest');
  const { code } = (await newParty(host)).data;
  await guest.post(`/api/party/${code}/join`, {});

  assert.equal((await guest.del(`/api/party/${code}`)).status, 403);
  assert.equal((await host.del(`/api/party/${code}`)).status, 204);
  assert.equal((await guest.get(`/api/party/${code}`)).status, 404);
});

test('unknown and malformed codes are 404', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  assert.equal((await host.get('/api/party/ZZZZZZ')).status, 404);
  assert.equal((await host.get('/api/party/abc')).status, 404);
});

test('authentication is required', async () => {
  const ctx = setup();
  const anon = new ctx.Client();
  assert.equal((await anon.get('/api/party/ABCDEF')).status, 401);
  assert.equal((await anon.post('/api/party', { mediaId: 'x', positionMs: 0, playing: false })).status, 401);
});

test('validation rejects bad bodies', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  assert.equal((await host.post('/api/party', { positionMs: 0, playing: false })).status, 400);
  assert.equal((await host.post('/api/party', { mediaId: 'x', positionMs: -1, playing: false })).status, 400);
  assert.equal((await host.post('/api/party', { mediaId: 'x', positionMs: 0, playing: 'yes' })).status, 400);
  const { code } = (await newParty(host)).data;
  assert.equal((await host.put(`/api/party/${code}`, { positionMs: 1.5, playing: true })).status, 400);
});

test('a stale room is treated as gone', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  const { code } = (await newParty(host)).data;
  await ctx.db.prepare('UPDATE party_rooms SET updated_at = ?2 WHERE code = ?1')
    .bind(code, ctx.clock.t - 13 * HOUR).run();
  assert.equal((await host.get(`/api/party/${code}`)).status, 404);
});

test('member last_seen refreshes on poll', async () => {
  const ctx = setup();
  const host = await joined(ctx, 'host');
  const guest = await joined(ctx, 'guest');
  const { code } = (await newParty(host)).data;
  await guest.post(`/api/party/${code}/join`, {});
  const before = (await guest.get(`/api/party/${code}`)).data.members.find((m) => m.userId !== 1) ?? null;
  ctx.clock.t += 5000;
  const after = (await guest.get(`/api/party/${code}`)).data.members;
  assert.equal(after.length, 2);
  if (before) assert.ok(after.find((m) => m.userId === before.userId).lastSeen >= before.lastSeen);
});
