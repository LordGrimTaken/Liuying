// Stream classification and best-effort quality parsing. Pure.

const RES = [
  [/\b(2160p|4k|uhd)\b/i, '4K'],
  [/\b1440p\b/i, '1440p'],
  [/\b1080p|1080i|fhd\b/i, '1080p'],
  [/\b720p|hd720\b/i, '720p'],
  [/\b576p|480p|360p|\bsd\b/i, 'SD'],
];

/** Flatten a stream's text. */
export function streamText(s) {
  return [s?.name, s?.title, s?.description].filter((x) => typeof x === 'string').join('\n');
}

export function parseResolution(text) {
  for (const [re, label] of RES) if (re.test(text)) return label;
  return null;
}

export const RES_RANK = { '4K': 5, '1440p': 4, '1080p': 3, '720p': 2, SD: 1 };

/** Return badges [{kind,label}] found in the text. Never hides the raw text. */
export function parseBadges(text) {
  const t = String(text || '');
  const out = [];
  const res = parseResolution(t);
  if (res) out.push({ kind: 'res', label: res });
  if (/dolby[\s.-]*vision|\bDV\b|\bdovi\b/i.test(t)) out.push({ kind: 'hdr', label: 'Dolby Vision' });
  else if (/hdr10\+/i.test(t)) out.push({ kind: 'hdr', label: 'HDR10+' });
  else if (/\bhdr(10)?\b/i.test(t)) out.push({ kind: 'hdr', label: 'HDR' });
  if (/\b(hevc|h\.?265|x265)\b/i.test(t)) out.push({ kind: 'codec', label: 'HEVC' });
  else if (/\bav1\b/i.test(t)) out.push({ kind: 'codec', label: 'AV1' });
  else if (/\b(h\.?264|x264|avc)\b/i.test(t)) out.push({ kind: 'codec', label: 'H.264' });
  if (/atmos/i.test(t)) out.push({ kind: 'audio', label: 'Atmos' });
  else if (/\b(dts[-\s]?hd|dts)\b/i.test(t)) out.push({ kind: 'audio', label: 'DTS' });
  else if (/\b(ddp?|eac3|dolby digital)\s?\+?\s?(5\.1|7\.1|2\.0)?\b/i.test(t) && /\b(dd\+?|ddp|eac3|dolby digital)/i.test(t)) out.push({ kind: 'audio', label: 'Dolby Digital' });
  else if (/\baac\b/i.test(t)) out.push({ kind: 'audio', label: 'AAC' });
  const ch = t.match(/\b(7\.1|5\.1|2\.0)\b/);
  if (ch) out.push({ kind: 'audio', label: ch[1] });
  const size = t.match(/(\d+(?:[.,]\d+)?)\s?(GB|MB|GiB|MiB)\b/i);
  if (size) out.push({ kind: 'size', label: `${size[1].replace(',', '.')} ${size[2].replace(/i/i, '').toUpperCase()}` });
  const seeds = t.match(/(?:\u{1F464}|seeds?:?|seeders?:?)\s*(\d+)/iu);
  if (seeds) out.push({ kind: 'seeds', label: `${seeds[1]} seeds` });
  if (/\bmulti[\s-]?(audio|lang)/i.test(t)) out.push({ kind: 'lang', label: 'Multi audio' });
  const flags = t.match(/[\u{1F1E6}-\u{1F1FF}]{2}/gu);
  if (flags) for (const f of [...new Set(flags)].slice(0, 3)) out.push({ kind: 'lang', label: f });
  const grp = t.match(/-([A-Za-z0-9]{2,12})(?:\.[a-z0-9]{2,4})?\s*$/m);
  if (grp && /(x26[45]|hevc|bluray|web[-.]?dl|webrip)/i.test(t)) out.push({ kind: 'group', label: grp[1] });
  return out;
}

/** Numeric size in bytes if the text mentions one (for sorting), else 0. */
export function parseSizeBytes(text) {
  const m = String(text || '').match(/(\d+(?:[.,]\d+)?)\s?(GB|MB|GiB|MiB)\b/i);
  if (!m) return 0;
  const n = parseFloat(m[1].replace(',', '.'));
  return /g/i.test(m[2]) ? n * 1e9 : n * 1e6;
}

/**
 * Classify a stream object for the browser.
 * kind: 'direct' | 'hls' | 'torrent' | 'external' | 'youtube' | 'blocked' | 'unsupported'
 * playable: can be played in-page. note: user-facing explanation (when not playable).
 */
export function classifyStream(s, { pageSecure = true } = {}) {
  const bh = s?.behaviorHints || {};
  if (typeof s?.url === 'string' && s.url) {
    let u;
    try { u = new URL(s.url); } catch { return { kind: 'unsupported', playable: false, note: 'This stream has an invalid link.' }; }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      return { kind: 'unsupported', playable: false, note: 'This stream type is not supported in the browser.' };
    }
    if (u.protocol === 'http:' && pageSecure) {
      return { kind: 'blocked', playable: false, note: 'Blocked by your browser (not secure)' };
    }
    if (bh.proxyHeaders && bh.proxyHeaders.request && Object.keys(bh.proxyHeaders.request).length) {
      return { kind: 'unsupported', playable: false, note: 'Needs custom request headers, which a browser cannot send. Use the Android app.' };
    }
    const hls = /\.m3u8$/i.test(u.pathname);
    return { kind: hls ? 'hls' : 'direct', playable: true, warn: bh.notWebReady ? 'May not play in a browser' : '' };
  }
  if (typeof s?.infoHash === 'string' && s.infoHash) {
    return { kind: 'torrent', playable: false, note: 'Not playable in the browser — use the Android app' };
  }
  if (typeof s?.externalUrl === 'string' && s.externalUrl) {
    return { kind: 'external', playable: false, openUrl: safeHref(s.externalUrl), note: 'Opens in a new tab' };
  }
  if (typeof s?.ytId === 'string' && s.ytId) {
    return { kind: 'youtube', playable: false, openUrl: 'https://www.youtube.com/watch?v=' + encodeURIComponent(s.ytId), note: 'Opens YouTube in a new tab' };
  }
  return { kind: 'unsupported', playable: false, note: 'This stream type is not supported in the browser.' };
}

export function safeHref(u) {
  try {
    const x = new URL(u);
    return x.protocol === 'http:' || x.protocol === 'https:' ? x.href : '';
  } catch { return ''; }
}

/** Decorate raw stream objects from one source with display data. */
export function describeStream(raw, source, index, opts) {
  const cls = classifyStream(raw, opts);
  const text = streamText(raw);
  const first = String(raw.name ?? '').replace(/\s*\n\s*/g, ' ').trim();
  const desc = String(raw.description ?? raw.title ?? '').trim();
  return {
    raw, source, index, cls,
    title: first || desc.split('\n')[0] || 'Stream',
    desc: first ? desc : desc.split('\n').slice(1).join('\n'),
    badges: parseBadges(text),
    langs: streamLanguages(text),
    res: parseResolution(text),
    size: parseSizeBytes(text),
    bingeGroup: raw.behaviorHints?.bingeGroup || '',
  };
}

/** Pick the stream most similar to a previous one (binge group, then same source+resolution). */
export function pickSimilar(streams, prev) {
  const playable = streams.filter((s) => s.cls.playable);
  if (!playable.length || !prev) return null;
  if (prev.bingeGroup) {
    const m = playable.find((s) => s.bingeGroup === prev.bingeGroup);
    if (m) return m;
  }
  if (prev.sourceUrl) {
    const m = playable.find((s) => s.source.url === prev.sourceUrl && (!prev.res || s.res === prev.res));
    if (m) return m;
  }
  return null;
}

export function sortStreams(list, how) {
  const a = [...list];
  if (how === 'resolution') a.sort((x, y) => (RES_RANK[y.res] || 0) - (RES_RANK[x.res] || 0));
  else if (how === 'resolution-size') a.sort((x, y) => (RES_RANK[y.res] || 0) - (RES_RANK[x.res] || 0) || y.size - x.size);
  else if (how === 'size') a.sort((x, y) => y.size - x.size);
  else if (how === 'name') a.sort((x, y) => x.title.localeCompare(y.title));
  return a;
}

// ---------- languages (L065) ----------
const FLAG_LANG = { '🇬🇧': 'English', '🇺🇸': 'English', '🇪🇸': 'Spanish', '🇲🇽': 'Spanish', '🇫🇷': 'French', '🇩🇪': 'German', '🇮🇹': 'Italian', '🇵🇹': 'Portuguese', '🇧🇷': 'Portuguese', '🇷🇺': 'Russian', '🇯🇵': 'Japanese', '🇰🇷': 'Korean', '🇨🇳': 'Chinese', '🇹🇼': 'Chinese', '🇮🇳': 'Hindi', '🇸🇦': 'Arabic', '🇹🇷': 'Turkish', '🇳🇱': 'Dutch', '🇵🇱': 'Polish' };
const LANG_WORDS = [[/\b(english|eng)\b/i, 'English'], [/\b(spanish|espa[nñ]ol|castellano|latino)\b/i, 'Spanish'], [/\b(french|fran[cç]ais|vff|vostfr|truefrench)\b/i, 'French'], [/\b(german|deutsch|ger)\b/i, 'German'], [/\b(italian|italiano|ita)\b/i, 'Italian'], [/\b(portuguese|portugu[eê]s|dublado)\b/i, 'Portuguese'], [/\b(russian|rus)\b/i, 'Russian'], [/\b(japanese|jpn|jap)\b/i, 'Japanese'], [/\b(korean|kor)\b/i, 'Korean'], [/\b(chinese|mandarin|cantonese|chi)\b/i, 'Chinese'], [/\bhindi\b/i, 'Hindi'], [/\barabic\b/i, 'Arabic'], [/\bturkish\b/i, 'Turkish']];
/** Languages a stream advertises (flag emoji or language words in its text), plus "Multi audio". */
export function streamLanguages(text) {
  const t = String(text || '');
  const out = [];
  for (const f of t.match(/[\u{1F1E6}-\u{1F1FF}]{2}/gu) || []) if (FLAG_LANG[f] && !out.includes(FLAG_LANG[f])) out.push(FLAG_LANG[f]);
  for (const [re, name] of LANG_WORDS) if (re.test(t) && !out.includes(name)) out.push(name);
  if (/\bmulti[\s-]?(audio|lang)|\bdual[\s-]?audio\b/i.test(t) && !out.includes('Multi audio')) out.push('Multi audio');
  return out;
}

/** Best playable stream for an automatic pick: highest resolution, then largest size. */
export function pickBestPlayable(list) {
  const playable = list.filter((s) => s.cls.playable);
  return sortStreams(playable, 'resolution-size')[0] || null;
}
