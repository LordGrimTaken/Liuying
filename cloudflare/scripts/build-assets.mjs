// Builds ./dist, the directory Workers Assets serves:
//  - copies ../site, minus anything that must not be public (web-player unit tests, dotfiles) and minus
//    every APK except the one named by downloads/latest.json (Workers Free: 25 MiB per asset; one APK is enough)
//  - writes 404.html (the branded page) and _headers (security headers, cache rules, /watch CSP)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { securityHeaders } from '../src/http.js';
import { NOT_FOUND_HTML } from '../src/notFoundPage.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const site = path.resolve(root, '../site');
const dist = path.join(root, 'dist');
const MAX_ASSET = 25 * 1024 * 1024;

fs.rmSync(dist, { recursive: true, force: true });

const latest = JSON.parse(fs.readFileSync(path.join(site, 'downloads/latest.json'), 'utf8'));
const keepApk = path.basename(new URL(latest.apkUrl).pathname);
if (!fs.existsSync(path.join(site, 'downloads', keepApk))) throw new Error(`latest.json points at ${keepApk}, which is missing`);

let files = 0; let bytes = 0;
(function copy(src, dst, rel) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const r = `${rel}${e.name}`;
    if (e.name.startsWith('.')) continue;
    // The web player is paused: do not ship it at all (the Worker redirects /watch to /download).
    if (rel === '' && e.name === 'watch') continue;
    if (rel === 'watch/' && e.name === 'test') continue;
    if (e.isDirectory()) { copy(path.join(src, e.name), path.join(dst, e.name), `${r}/`); continue; }
    if (rel === 'downloads/' && e.name.endsWith('.apk') && e.name !== keepApk) continue;
    const size = fs.statSync(path.join(src, e.name)).size;
    if (size > MAX_ASSET) throw new Error(`${r} is ${size} bytes, over the 25 MiB Workers asset limit`);
    fs.copyFileSync(path.join(src, e.name), path.join(dst, e.name));
    files++; bytes += size;
  }
})(site, dist, '');

fs.writeFileSync(path.join(dist, '404.html'), NOT_FOUND_HTML);

// _headers: rules combine, and a repeated header is joined with a comma. So the /watch rule first detaches the
// strict CSP inherited from /* and then sets the relaxed one.
const lines = [];
const rule = (p, h, detach = []) => {
  lines.push(p);
  for (const d of detach) lines.push(`  ! ${d}`);
  for (const [k, v] of Object.entries(h)) lines.push(`  ${k}: ${v}`);
  lines.push('');
};
rule('/*', securityHeaders(false));
// The web player is paused, so no /watch rules are emitted; the Worker redirects those paths.
rule('/downloads/latest.json', { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-cache' });
rule(`/downloads/${keepApk}`, {
  'Content-Type': 'application/vnd.android.package-archive',
  'Content-Disposition': `attachment; filename="${keepApk}"`,
  'Cache-Control': 'public, max-age=3600',
});
fs.writeFileSync(path.join(dist, '_headers'), lines.join('\n'));

console.log(`dist ready: ${files} files, ${(bytes / 1048576).toFixed(1)} MiB, APK ${keepApk}`);
