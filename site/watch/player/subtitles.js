// Subtitle helpers: SRT -> WebVTT, text decoding, language names, ranking. Pure (decode uses TextDecoder).

/** Convert SRT (or already-WebVTT) text to WebVTT. Returns {vtt, cues} where cues is the count. */
export function srtToVtt(input) {
  let text = String(input ?? '').replace(/^﻿/, '').replace(/\r\n?/g, '\n').trim();
  if (/^WEBVTT/.test(text)) return { vtt: text + '\n', cues: (text.match(/-->/g) || []).length };
  const blocks = text.split(/\n{2,}/);
  const out = ['WEBVTT', ''];
  let cues = 0;
  for (const block of blocks) {
    const lines = block.split('\n');
    let i = 0;
    if (lines[i] && !lines[i].includes('-->') && /^\d+$/.test(lines[i].trim())) i++;
    const t = lines[i];
    if (!t || !t.includes('-->')) continue;
    const m = t.match(/(\d{1,2}:)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[,.](\d{1,3})(.*)$/);
    if (!m) continue;
    const ts = (h, mi, s, ms) => `${String(h ? parseInt(h) : 0).padStart(2, '0')}:${mi.padStart(2, '0')}:${s}.${ms.padEnd(3, '0')}`;
    const body = lines.slice(i + 1).join('\n').replace(/\{\\an?\d\}/g, '').replace(/<\/?font[^>]*>/gi, '').trim();
    if (!body) continue;
    out.push(`${ts(m[1], m[2], m[3], m[4])} --> ${ts(m[5], m[6], m[7], m[8])}`);
    out.push(escapeVttBody(body), '');
    cues++;
  }
  return { vtt: out.join('\n'), cues };
}

// Keep simple formatting tags (<i> <b> <u>), neutralise the rest of the markup.
function escapeVttBody(s) {
  return s.split(/(<\/?[ibu]>)/i).map((part, idx) => (idx % 2 ? part.toLowerCase() : part
    .replace(/&(?!(amp|lt|gt|nbsp|lrm|rlm);)/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'))).join('');
}

/** Decode subtitle bytes: UTF-8 (with/without BOM), UTF-16 BOM, else Windows-1252. */
export function decodeSubtitleBytes(buf, override) {
  const u8 = new Uint8Array(buf);
  if (override) { try { return new TextDecoder(override).decode(u8); } catch { /* fall through */ } }
  if (u8[0] === 0xff && u8[1] === 0xfe) return new TextDecoder('utf-16le').decode(u8);
  if (u8[0] === 0xfe && u8[1] === 0xff) return new TextDecoder('utf-16be').decode(u8);
  try { return new TextDecoder('utf-8', { fatal: true }).decode(u8); } catch { return new TextDecoder('windows-1252').decode(u8); }
}

const LANGS = {
  en: 'English', eng: 'English', es: 'Spanish', spa: 'Spanish', fr: 'French', fre: 'French', fra: 'French',
  de: 'German', ger: 'German', deu: 'German', it: 'Italian', ita: 'Italian', pt: 'Portuguese', por: 'Portuguese',
  pob: 'Portuguese (Brazil)', 'pt-br': 'Portuguese (Brazil)', ru: 'Russian', rus: 'Russian', ja: 'Japanese', jpn: 'Japanese',
  ko: 'Korean', kor: 'Korean', zh: 'Chinese', chi: 'Chinese', zho: 'Chinese', zht: 'Chinese (Traditional)', zhs: 'Chinese (Simplified)',
  'zh-cn': 'Chinese (Simplified)', 'zh-tw': 'Chinese (Traditional)', ar: 'Arabic', ara: 'Arabic', hi: 'Hindi', hin: 'Hindi',
  tr: 'Turkish', tur: 'Turkish', nl: 'Dutch', dut: 'Dutch', nld: 'Dutch', pl: 'Polish', pol: 'Polish', sv: 'Swedish', swe: 'Swedish',
  da: 'Danish', dan: 'Danish', no: 'Norwegian', nor: 'Norwegian', fi: 'Finnish', fin: 'Finnish', cs: 'Czech', cze: 'Czech', ces: 'Czech',
  el: 'Greek', gre: 'Greek', ell: 'Greek', he: 'Hebrew', heb: 'Hebrew', hu: 'Hungarian', hun: 'Hungarian', ro: 'Romanian', rum: 'Romanian', ron: 'Romanian',
  th: 'Thai', tha: 'Thai', vi: 'Vietnamese', vie: 'Vietnamese', id: 'Indonesian', ind: 'Indonesian', uk: 'Ukrainian', ukr: 'Ukrainian',
  bg: 'Bulgarian', bul: 'Bulgarian', hr: 'Croatian', hrv: 'Croatian', sr: 'Serbian', srp: 'Serbian', fa: 'Persian', per: 'Persian', fas: 'Persian',
};
const CANON = { eng: 'en', spa: 'es', fre: 'fr', fra: 'fr', ger: 'de', deu: 'de', ita: 'it', por: 'pt', rus: 'ru', jpn: 'ja', kor: 'ko',
  chi: 'zh', zho: 'zh', ara: 'ar', hin: 'hi', tur: 'tr', dut: 'nl', nld: 'nl', pol: 'pl', swe: 'sv', dan: 'da', nor: 'no', fin: 'fi',
  cze: 'cs', ces: 'cs', gre: 'el', ell: 'el', heb: 'he', hun: 'hu', rum: 'ro', ron: 'ro', tha: 'th', vie: 'vi', ind: 'id', ukr: 'uk',
  bul: 'bg', hrv: 'hr', srp: 'sr', per: 'fa', fas: 'fa' };

/** Canonical 2-letter-ish code for comparisons ("eng" -> "en"); unknown codes pass through lowercased. */
export function langCode(code) {
  const c = String(code || '').toLowerCase().trim();
  return CANON[c] || c;
}
export const languageName = (code) => LANGS[String(code || '').toLowerCase().trim()] || (code ? String(code) : 'Unknown');

export const COMMON_LANGS = ['en', 'es', 'fr', 'de', 'it', 'pt', 'ru', 'ja', 'ko', 'zh', 'ar', 'hi', 'tr', 'nl', 'pl', 'sv'];

/**
 * Normalise subtitle entries from several origins to
 * {key,url,lang,langName,label,source,origin,forced,sdh}
 */
export function normalizeSubtitles(groups) {
  const out = [];
  const counts = new Map();
  for (const { source, origin, items } of groups) {
    for (const s of items) {
      if (!s?.url) continue;
      const lang = langCode(s.lang || s.language || 'und');
      const idText = `${s.id || ''} ${s.label || ''} ${s.url}`;
      const forced = /forced/i.test(idText);
      const sdh = /\b(sdh|hearing|hi)\b|\.hi\./i.test(idText);
      const n = (counts.get(lang) || 0) + 1;
      counts.set(lang, n);
      out.push({
        key: `${origin}:${source || ''}:${s.id || s.url}`,
        url: s.url, lang, langName: languageName(s.lang || lang), source: source || '', origin, forced, sdh, n,
        label: `${languageName(s.lang || lang)}${n > 1 ? ' ' + n : ''}`,
      });
    }
  }
  return out;
}

/** Order: preferred languages first (in preference order), then rest alphabetical; non-SDH, non-forced first. */
export function sortSubtitles(list, preferred = []) {
  const pref = preferred.map(langCode);
  const rank = (s) => { const i = pref.indexOf(s.lang); return i < 0 ? 999 : i; };
  return [...list].sort((a, b) =>
    rank(a) - rank(b) || (a.langName.localeCompare(b.langName)) || (a.forced - b.forced) || (a.sdh - b.sdh) || (a.n - b.n));
}

/** Auto selection: best subtitle in a preferred language, or null. */
export function pickAutoSubtitle(list, preferred = [], mode = 'on') {
  if (mode === 'off') return null;
  const pref = preferred.map(langCode);
  for (const lang of pref) {
    const cands = list.filter((s) => s.lang === lang);
    if (!cands.length) continue;
    const full = cands.filter((s) => !s.forced);
    const pool = full.length ? full : cands;
    return pool.find((s) => !s.sdh) || pool[0];
  }
  return null;
}

/** Shift cue text? no. Format helper for delay display: +0.5 s */
export const fmtDelay = (sec) => `${sec > 0 ? '+' : ''}${(Math.round(sec * 10) / 10).toFixed(1)} s`;
