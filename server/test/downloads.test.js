import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { setup, closeAll } from './helpers.js';

after(() => closeAll());

function siteWithDownloads() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'liuying-site-'));
  fs.mkdirSync(path.join(dir, 'downloads'));
  fs.writeFileSync(path.join(dir, 'index.html'), '<h1>home</h1>');
  fs.writeFileSync(path.join(dir, 'download.html'), '<h1>download</h1>');
  fs.writeFileSync(path.join(dir, 'downloads', 'Liuying-9.9.9.apk'), Buffer.from([0x50, 0x4b, 3, 4, 1, 2, 3]));
  fs.writeFileSync(path.join(dir, 'downloads', 'latest.json'), JSON.stringify({ versionName: '9.9.9', versionCode: 99 }));
  return dir;
}

test('APK is served with the Android MIME type and range support', async () => {
  const t = await setup({ siteDir: siteWithDownloads() });
  const r = await t.request().get('/downloads/Liuying-9.9.9.apk').buffer(true).parse((res, cb) => {
    const chunks = []; res.on('data', (c) => chunks.push(c)); res.on('end', () => cb(null, Buffer.concat(chunks)));
  });
  assert.equal(r.status, 200);
  assert.equal(r.headers['content-type'], 'application/vnd.android.package-archive');
  assert.match(r.headers['content-disposition'], /attachment; filename="Liuying-9.9.9\.apk"/);
  assert.equal(r.headers['accept-ranges'], 'bytes');
  assert.equal(r.body.length, 7);
  assert.match(r.headers['content-security-policy'], /default-src 'self'/);
});

test('latest.json is JSON and never cached', async () => {
  const t = await setup({ siteDir: siteWithDownloads() });
  const r = await t.request().get('/downloads/latest.json');
  assert.equal(r.status, 200);
  assert.match(r.headers['content-type'], /^application\/json/);
  assert.equal(r.headers['cache-control'], 'no-cache');
  assert.equal(r.body.versionCode, 99);
});

test('missing downloads and directory listing are 404; /download serves the page', async () => {
  const t = await setup({ siteDir: siteWithDownloads() });
  assert.equal((await t.request().get('/downloads/nope.apk')).status, 404);
  assert.equal((await t.request().get('/downloads/')).status, 404);
  const r = await t.request().get('/download');
  assert.equal(r.status, 200);
  assert.match(r.text, /download/);
});
