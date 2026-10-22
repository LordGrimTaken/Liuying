// Per-source test (L081): asks a source for each resource its manifest declares and reports pass/fail with timing.
// Pure: the network calls are injected so the logic is testable.
import { normalizeResources, catalogCapabilities, resourceApplies } from './protocol.js';

export const RESOURCE_LABEL = { manifest: 'Manifest', catalog: 'Catalogs', meta: 'Details', stream: 'Streams', subtitles: 'Subtitles' };
const ORDER = ['catalog', 'meta', 'stream', 'subtitles'];
/** A well-known public title, used when the source has no catalog to take a sample from. */
export const PROBE = { type: 'movie', id: 'tt0111161' };

export const declaredResources = (m) => {
  const names = new Set(normalizeResources(m).map((r) => r.name));
  if ((m?.catalogs || []).length) names.add('catalog');
  return ORDER.filter((n) => names.has(n));
};

/**
 * fns: { manifest(url), catalog(source, cat, extra), meta(source, type, id), stream(source, type, id), subtitles(source, type, id, extra), describe(err) }
 * Returns [{resource, status: 'pass'|'fail'|'skip', ms, detail}].
 */
export async function testSource(source, fns, { now = () => Date.now(), onStep } = {}) {
  const out = [];
  const push = (r) => { out.push(r); onStep?.(r, out); return r; };
  const timed = async (resource, fn, okText) => {
    const t = now();
    try { const v = await fn(); return push({ resource, status: 'pass', ms: now() - t, detail: okText(v), value: v }); }
    catch (e) { return push({ resource, status: 'fail', ms: now() - t, detail: fns.describe ? fns.describe(e) : String(e?.message || e) }); }
  };
  let m = source.manifest;
  const mr = await timed('manifest', async () => { const r = await fns.manifest(source.url); m = r.manifest || r; return m; }, (v) => `${v.name || 'Source'}${v.version ? ' v' + v.version : ''}`);
  if (mr.status === 'fail') return out;
  const names = declaredResources(m);
  if (!names.length) { push({ resource: 'catalog', status: 'skip', ms: 0, detail: 'This manifest declares no resources.' }); return out; }
  let sample = null;
  if (names.includes('catalog')) {
    const cat = (m.catalogs || []).find((c) => { const k = catalogCapabilities(c); return !k.searchRequired && !k.otherRequired.length; });
    if (!cat) push({ resource: 'catalog', status: 'skip', ms: 0, detail: 'Every catalog needs input, so there is nothing to sample.' });
    else {
      const extra = (() => { const k = catalogCapabilities(cat); return k.genreRequired && k.genreOptions.length ? { genre: k.genreOptions[0] } : undefined; })();
      const r = await timed('catalog', () => fns.catalog(source, cat, extra), (v) => `${v.length} title${v.length === 1 ? '' : 's'} in ${cat.name || cat.id}`);
      if (r.status === 'pass' && r.value.length) sample = { type: r.value[0].type || cat.type, id: r.value[0].id };
    }
  }
  if (!sample) {
    const res = normalizeResources(m).find((r) => r.name === 'stream' || r.name === 'meta');
    if (res && resourceApplies(m, res.name, PROBE.type, PROBE.id)) sample = { ...PROBE };
  }
  for (const name of names.filter((n) => n !== 'catalog')) {
    if (!sample || !resourceApplies(m, name, sample.type, sample.id)) { push({ resource: name, status: 'skip', ms: 0, detail: 'No sample title this source handles.' }); continue; }
    if (name === 'meta') await timed('meta', () => fns.meta(source, sample.type, sample.id), (v) => v.name || 'Answered');
    else if (name === 'stream') await timed('stream', () => fns.stream(source, sample.type, sample.id), (v) => (v.length ? `${v.length} stream${v.length === 1 ? '' : 's'}` : 'Answered, no streams for this title'));
    else if (name === 'subtitles') await timed('subtitles', () => fns.subtitles(source, sample.type, sample.id, {}), (v) => (v.length ? `${v.length} subtitle${v.length === 1 ? '' : 's'}` : 'Answered, none for this title'));
  }
  return out.map(({ value, ...r }) => r);
}

export const summarize = (results) => {
  const fail = results.filter((r) => r.status === 'fail').length;
  const pass = results.filter((r) => r.status === 'pass').length;
  return fail ? `${fail} of ${pass + fail} checks failed` : `All ${pass} checks passed`;
};
