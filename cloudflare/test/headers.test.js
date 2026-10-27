// The generated _headers: the /watch policy must stay as strict as before, and only the plugin sandbox page may have 'unsafe-eval'.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { CSP_WATCH, CSP_BASE, cspString } from '../src/http.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
execFileSync(process.execPath, ['scripts/build-assets.mjs'], { cwd: root, stdio: 'pipe' });
const text = fs.readFileSync(path.join(root, 'dist/_headers'), 'utf8');
const blocks = Object.fromEntries(text.trim().split(/\n\s*\n/).map((b) => { const [p, ...l] = b.split('\n'); return [p.trim(), l.map((x) => x.trim())]; }));
const header = (p, name) => (blocks[p].find((l) => l.toLowerCase().startsWith(name.toLowerCase() + ':')) || '').slice(name.length + 1).trim();

test('the /watch CSP is unchanged: no unsafe-eval, no unsafe-inline, same directives as before', () => {
  const csp = header('/watch/*', 'Content-Security-Policy');
  assert.equal(csp, cspString(CSP_WATCH));
  assert.ok(!/unsafe-/.test(csp));
  assert.match(csp, /script-src 'self';/);
  assert.match(csp, /connect-src 'self' https:;/);
  assert.match(csp, /frame-ancestors 'none'/);
  assert.equal(header('/*', 'Content-Security-Policy'), cspString(CSP_BASE));
  assert.ok(blocks['/watch/*'].includes('! Content-Security-Policy'), 'the /watch rule detaches the inherited policy first');
});

test("'unsafe-eval' exists only in the sandbox page's own policy", () => {
  for (const [p, lines] of Object.entries(blocks)) {
    const has = lines.some((l) => /unsafe-eval/.test(l));
    assert.equal(has, p === '/watch/plugins/sandbox.htm', p);
  }
});

test('sandbox page policy: no network of any kind, opaque origin, framed only by the player', () => {
  const p = '/watch/plugins/sandbox.htm';
  const csp = header(p, 'Content-Security-Policy');
  assert.match(csp, /^default-src 'none';/);
  assert.ok(!/connect-src|img-src|frame-src|style-src|font-src|media-src/.test(csp), 'nothing opens a network path');
  assert.match(csp, /script-src 'unsafe-eval' 'unsafe-inline'/);
  assert.match(csp, /worker-src blob:/);
  assert.match(csp, /sandbox allow-scripts/);
  assert.ok(!/allow-same-origin/.test(csp));
  assert.match(csp, /form-action 'none'/);
  assert.match(csp, /base-uri 'none'/);
  assert.match(csp, /frame-ancestors 'self'/);
  assert.ok(blocks[p].includes('! Content-Security-Policy') && blocks[p].includes('! X-Frame-Options'));
  assert.equal(header(p, 'X-Frame-Options'), 'SAMEORIGIN');
  assert.match(header(p, 'Content-Type'), /text\/html/);
  // the rule must come after /* and /watch/* so that the detach removes their values
  const order = Object.keys(blocks);
  assert.ok(order.indexOf(p) > order.indexOf('/watch/*') && order.indexOf('/watch/*') > order.indexOf('/*'));
});

test('the sandbox page and the engine are real static files (a .htm path, so Workers Assets does not redirect it)', () => {
  for (const f of ['watch/plugins/sandbox.htm', 'watch/plugins/engine.js', 'watch/plugins/vendor/cheerio.js', 'watch/plugins/vendor/crypto-js.js', 'watch/plugins/vendor/NOTICE.txt']) {
    assert.ok(fs.existsSync(path.join(root, 'dist', f)), f);
  }
  assert.ok(!fs.existsSync(path.join(root, 'dist/watch/test')), 'unit tests and e2e fixtures are not published');
});
