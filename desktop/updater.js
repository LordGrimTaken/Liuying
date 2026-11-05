// Update check for the desktop shell.
//
// Deliberately a *notice*, not a silent self-updater: the builds are unsigned, and on macOS Squirrel
// refuses to apply an update to an unsigned bundle anyway. So: ask GitHub Releases whether a newer
// desktop-v* exists, and offer the download page. No binary is fetched by this process.
const RELEASES = 'https://api.github.com/repos/LordGrimTaken/Liuying/releases?per_page=20';

const parseVersion = (v) => String(v || '').replace(/^v/i, '').split('.').map((n) => parseInt(n, 10) || 0);

/** Compare dotted versions. Returns >0 when a is newer than b, 0 equal, <0 older. */
function compare(a, b) {
  const x = parseVersion(a);
  const y = parseVersion(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] || 0) - (y[i] || 0);
    if (d !== 0) return d > 0 ? 1 : -1;
  }
  return 0;
}

const desktopVersionOf = (release) => {
  const tag = String(release.tag_name || '');
  return /^desktop-v/i.test(tag) ? tag.replace(/^desktop-v/i, '') : null;
};

/**
 * @returns {Promise<{available:boolean, current:string, latest:?string, url:?string, name:?string, notes:?string, error?:string}>}
 */
async function checkForUpdate({ currentVersion, fetchImpl = fetch, feed = RELEASES, timeoutMs = 8000 } = {}) {
  const current = String(currentVersion || '0.0.0');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetchImpl(feed, {
      headers: { accept: 'application/vnd.github+json', 'user-agent': 'LiuyingDesktop/' + current },
      signal: controller.signal,
    });
    if (!res.ok) return { available: false, current, latest: null, url: null, name: null, notes: null, error: `http_${res.status}` };
    const list = await res.json();
    let best = null;
    for (const r of Array.isArray(list) ? list : []) {
      if (r.draft || r.prerelease) continue;
      const v = desktopVersionOf(r);
      if (!v) continue;
      if (!best || compare(v, best.version) > 0) best = { version: v, release: r };
    }
    if (!best) return { available: false, current, latest: null, url: null, name: null, notes: null };
    const newer = compare(best.version, current) > 0;
    return {
      available: newer,
      current,
      latest: best.version,
      url: best.release.html_url || null,
      name: best.release.name || `Liuying Desktop ${best.version}`,
      notes: (best.release.body || '').slice(0, 600),
    };
  } catch (e) {
    return { available: false, current, latest: null, url: null, name: null, notes: null, error: String((e && e.message) || e) };
  } finally {
    clearTimeout(timer);
  }
}

module.exports = { checkForUpdate, compare, desktopVersionOf };
