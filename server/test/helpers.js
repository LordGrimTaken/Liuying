import { fileURLToPath } from 'node:url';
import request from 'supertest';
import { createApp } from '../src/app.js';
import { createMemoryDb, migrate } from '../src/db.js';

export const SITE = fileURLToPath(new URL('./site', import.meta.url));
const FAST = { N: 2 ** 10, r: 8, p: 1, keylen: 64 };
const silent = { info() {}, error() {} };

export async function setup(opts = {}) {
  const db = await createMemoryDb();
  await migrate(db);
  const clock = { t: Date.now() };
  const app = createApp({
    db, siteDir: SITE, logger: silent, hashParams: FAST, now: () => clock.t, ...opts,
  });
  return { db, app, clock, agent: () => request.agent(app), request: () => request(app) };
}

export const json = (r) => r.set('Content-Type', 'application/json');
export const post = (t, url, body) => t.post(url).set('Content-Type', 'application/json').send(JSON.stringify(body));
