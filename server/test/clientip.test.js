import { test } from 'node:test';
import assert from 'node:assert/strict';
import { clientIp } from '../src/clientIp.js';

const req = (xff, peer = '10.1.2.3') => ({ headers: xff === undefined ? {} : { 'x-forwarded-for': xff }, socket: { remoteAddress: peer } });

test('picks the rightmost non-proxy address; forged leftmost entries are ignored', () => {
  assert.equal(clientIp(req('203.0.113.9, 172.70.1.1'), { behindProxy: true }), '203.0.113.9');
  assert.equal(clientIp(req('6.6.6.6, 203.0.113.9, 172.70.1.1, 10.0.0.5'), { behindProxy: true }), '203.0.113.9');
  assert.equal(clientIp(req('2001:db8::5, 2606:4700::1'), { behindProxy: true }), '2001:db8::5');
  assert.equal(clientIp(req('203.0.113.9'), { behindProxy: true }), '203.0.113.9');
});
test('falls back to the socket peer; ignores header when not behind a proxy', () => {
  assert.equal(clientIp(req(undefined, '::ffff:198.51.100.7'), { behindProxy: true }), '198.51.100.7');
  assert.equal(clientIp(req('1.2.3.4', '198.51.100.7'), { behindProxy: false }), '198.51.100.7');
  assert.equal(clientIp(req('not-an-ip, 172.70.1.1', '10.1.1.1'), { behindProxy: true }), '10.1.1.1');
});
