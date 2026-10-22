// Stremio addon protocol helpers: pure functions, no network, no DOM.

/** Turn whatever the user pasted into a normalised https manifest URL, or throw a friendly Error. */
export function normalizeManifestUrl(input) {
  let s = String(input ?? '').trim();
  if (!s) throw new Error('Paste a manifest link first.');
  s = s.replace(/^stremio:\/\//i, 'https://');
  s = s.replace(/^web\+stremio:\/\//i, 'https://');
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) {
    if (/\s/.test(s) || !/[.:]|localhost/.test(s)) throw new Error('That does not look like a link.');
    s = 'https://' + s;
  }
  let u;
  try { u = new URL(s); } catch { throw new Error('That does not look like a valid link.'); }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') throw new Error('Only http(s) and stremio:// links are supported.');
  if (!/\/manifest\.json$/i.test(u.pathname)) {
    throw new Error('The link must end with /manifest.json. Copy the manifest link from your source, not its home page.');
  }
  u.hash = '';
  u.search = '';
  return u.href;
}

/** Base URL (no trailing slash) used to build resource URLs. */
export function baseUrl(manifestUrl) {
  return String(manifestUrl).replace(/[?#].*$/, '').replace(/\/manifest\.json$/i, '').replace(/\/+$/, '');
}

/** Serialise an extra object ({genre:'Action', skip:20}) as protocol path segment, '' when empty. */
export function buildExtra(extra) {
  if (!extra) return '';
  const parts = [];
  for (const [k, v] of Object.entries(extra)) {
    if (v === undefined || v === null || v === '') continue;
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  }
  return parts.join('&');
}

function encodeId(id) {
  // keep ':' literal (series:season:episode ids) – encode everything else that would break a path segment
  return encodeURIComponent(String(id)).replace(/%3A/gi, ':');
}

/** Build {base}/{resource}/{type}/{id}[/{extra}].json */
export function buildResourceUrl(manifestUrl, resource, type, id, extra) {
  const ex = buildExtra(extra);
  return `${baseUrl(manifestUrl)}/${resource}/${encodeURIComponent(type)}/${encodeId(id)}${ex ? '/' + ex : ''}.json`;
}

/** catalog URLs use the catalog id where meta/stream use an item id. */
export const buildCatalogUrl = (manifestUrl, type, catalogId, extra) =>
  buildResourceUrl(manifestUrl, 'catalog', type, catalogId, extra);

/** Resource entries normalised to {name, types, idPrefixes}. Supports string and object forms. */
export function normalizeResources(manifest) {
  const out = [];
  for (const r of manifest?.resources ?? []) {
    if (typeof r === 'string') out.push({ name: r, types: manifest.types ?? null, idPrefixes: manifest.idPrefixes ?? null });
    else if (r && typeof r.name === 'string') {
      out.push({ name: r.name, types: r.types ?? manifest.types ?? null, idPrefixes: r.idPrefixes ?? manifest.idPrefixes ?? null });
    }
  }
  return out;
}

/** Should this source be asked for `resource` of (type,id)? Catalog is handled separately. */
export function resourceApplies(manifest, resource, type, id) {
  for (const r of normalizeResources(manifest)) {
    if (r.name !== resource) continue;
    if (Array.isArray(r.types) && r.types.length && !r.types.includes(type)) continue;
    if (Array.isArray(r.idPrefixes) && r.idPrefixes.length && !r.idPrefixes.some((p) => String(id).startsWith(p))) continue;
    return true;
  }
  return false;
}

export const declaresResource = (manifest, name) => normalizeResources(manifest).some((r) => r.name === name);

/** Describe a catalog's extras: supports search / skip / genre, and which other extras are required. */
export function catalogCapabilities(cat) {
  const extra = Array.isArray(cat?.extra) ? cat.extra : [];
  const supported = new Set(Array.isArray(cat?.extraSupported) ? cat.extraSupported : []);
  const required = new Set(Array.isArray(cat?.extraRequired) ? cat.extraRequired : []);
  for (const e of extra) {
    if (!e || !e.name) continue;
    supported.add(e.name);
    if (e.isRequired) required.add(e.name);
  }
  const g = extra.find((e) => e?.name === 'genre');
  const genreOptions = Array.isArray(g?.options) ? g.options.map(String) : [];
  const otherRequired = [...required].filter((n) => !['search', 'genre', 'skip'].includes(n));
  return {
    search: supported.has('search'),
    searchRequired: required.has('search'),
    skip: supported.has('skip'),
    genre: supported.has('genre'),
    genreOptions,
    genreRequired: required.has('genre'),
    otherRequired,
  };
}

/** A catalog can appear on Today when it needs nothing we cannot supply. */
export function isHomeEligible(cat) {
  const c = catalogCapabilities(cat);
  return !c.searchRequired && c.otherRequired.length === 0 && !(c.genreRequired && c.genreOptions.length === 0);
}

/** Search-capable catalogs (search extra supported). */
export const isSearchable = (cat) => catalogCapabilities(cat).search && catalogCapabilities(cat).otherRequired.length === 0;

/** Minimal manifest validation; returns normalised manifest or throws Error with a specific message. */
export function validateManifest(m) {
  if (!m || typeof m !== 'object' || Array.isArray(m)) throw new Error('The source answered, but not with a manifest.');
  if (typeof m.id !== 'string' || !m.id) throw new Error('This manifest has no id.');
  if (typeof m.name !== 'string' || !m.name) throw new Error('This manifest has no name.');
  if (!Array.isArray(m.resources)) throw new Error('This manifest does not list any resources.');
  return {
    ...m,
    version: String(m.version ?? ''),
    types: Array.isArray(m.types) ? m.types : [],
    catalogs: Array.isArray(m.catalogs) ? m.catalogs.filter((c) => c && c.id && c.type) : [],
  };
}

export const isAdult = (m) => !!(m?.behaviorHints?.adult);
export const isConfigurable = (m) => !!(m?.behaviorHints?.configurable);
export const configurationRequired = (m) => !!(m?.behaviorHints?.configurationRequired);

/** Configure page lives at {base}/configure. */
export const configureUrl = (manifestUrl) => baseUrl(manifestUrl) + '/configure';

/** Stable key for a catalog within a source. */
export const catalogKey = (sourceUrl, cat) => `${sourceUrl}#${cat.type}/${cat.id}`;

/** Human catalog title, e.g. "Popular – Movies". */
export function catalogTitle(cat) {
  const t = { movie: 'Movies', series: 'Series', tv: 'TV', channel: 'Channels' }[cat.type] || cat.type;
  return `${cat.name || cat.id} – ${t}`;
}
