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

// ---------- encoding detection (L069) ----------
const SCRIPTS = {
  kana: /[぀-ヿ]/g, han: /[一-鿿]/g, hangul: /[가-힣]/g, cyr: /[Ѐ-ӿ]/g,
  greek: /[Ͱ-Ͽ]/g, hebrew: /[֐-׿]/g, arabic: /[؀-ۿ]/g, thai: /[฀-๿]/g,
};
const count = (s, re) => (s.match(re) || []).length;
/** Language code -> the legacy encoding such subtitle files usually use. */
const LANG_ENCODING = [[/^(zh-?(tw|hk|hant)|zht|big5)$/, 'big5'], [/^(zh|zh-?(cn|hans)|zhs|chi|zho|gbk)$/, 'gb18030'], [/^(ja|jpn)$/, 'shift_jis'], [/^(ko|kor)$/, 'euc-kr'],
  [/^(ru|rus|uk|ukr|bg|bul|sr|srp|be|mk)$/, 'windows-1251'], [/^(el|gre|ell)$/, 'windows-1253'], [/^(tr|tur)$/, 'windows-1254'], [/^(he|heb)$/, 'windows-1255'], [/^(ar|ara|fa|per|fas)$/, 'windows-1256'],
  [/^(pl|pol|cs|cze|ces|hu|hun|ro|rum|ron|hr|hrv|sk|slo|sl|slv)$/, 'windows-1250'], [/^(th|tha)$/, 'windows-874'], [/^(vi|vie)$/, 'windows-1258']];
export const lang2encoding = (lang) => { const l = String(lang || '').toLowerCase(); const m = LANG_ENCODING.find(([re]) => re.test(l)); return m ? m[1] : ''; };
export const ENCODING_CHOICES = [['', 'Automatic'], ['utf-8', 'Unicode (UTF-8)'], ['utf-16le', 'Unicode (UTF-16)'], ['windows-1252', 'Western (Windows-1252)'], ['windows-1250', 'Central European (1250)'], ['windows-1251', 'Cyrillic (1251)'],
  ['windows-1253', 'Greek (1253)'], ['windows-1254', 'Turkish (1254)'], ['windows-1255', 'Hebrew (1255)'], ['windows-1256', 'Arabic (1256)'], ['gb18030', 'Chinese, simplified (GBK)'], ['big5', 'Chinese, traditional (Big5)'], ['shift_jis', 'Japanese (Shift-JIS)'], ['euc-kr', 'Korean (EUC-KR)']];

function tryDecode(u8, enc, fatal = false) { try { return new TextDecoder(enc, { fatal }).decode(u8); } catch { return null; } }
/** Share of characters that are U+FFFD or control junk. */
const junk = (t) => (t.length ? (count(t, /�/g) + count(t, /[\u0000-\u0008\u000e-\u001f]/g)) / t.length : 1);

/**
 * Guess the text encoding of subtitle bytes. BOMs first, then BOM-less UTF-16, then valid UTF-8, then the encoding the
 * subtitle's language usually uses (verified by decoding), then statistics over the common legacy encodings.
 * Returns an encoding label accepted by TextDecoder.
 */
export function detectEncoding(buf, { lang = '' } = {}) {
  const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  if (u8[0] === 0xef && u8[1] === 0xbb && u8[2] === 0xbf) return 'utf-8';
  if (u8[0] === 0xff && u8[1] === 0xfe) return 'utf-16le';
  if (u8[0] === 0xfe && u8[1] === 0xff) return 'utf-16be';
  const head = u8.subarray(0, 4096);
  let even0 = 0, odd0 = 0;
  for (let i = 0; i < head.length; i++) if (head[i] === 0) { if (i % 2) odd0++; else even0++; }
  const pairs = head.length / 2;
  if (pairs > 8 && odd0 / pairs > 0.3 && even0 / pairs < 0.05) return 'utf-16le';
  if (pairs > 8 && even0 / pairs > 0.3 && odd0 / pairs < 0.05) return 'utf-16be';
  if (tryDecode(u8, 'utf-8', true) !== null) return 'utf-8';
  const hinted = lang2encoding(lang);
  if (hinted) { const t = tryDecode(u8, hinted); if (t !== null && junk(t) < 0.02) return hinted; }
  // no usable hint: look at how much of the file is high bytes
  let high = 0, lowCyr = 0, cyr = 0;
  for (const b of u8) { if (b >= 0x80) high++; if (b >= 0xc0) { cyr++; if (b >= 0xe0) lowCyr++; } }
  const ratio = high / Math.max(1, u8.length);
  if (ratio < 0.25) return 'windows-1252'; // some accented letters (accent-heavy Latin text tops out near 20%): Western European
  if (cyr > 20 && lowCyr / cyr > 0.72) return 'windows-1251'; // mostly lowercase Cyrillic bytes
  const wide = (t) => Math.max(1, t.replace(/[\u0000-\u007f]/g, '').length); // non-ASCII characters
  const cand = ['shift_jis', 'euc-kr', 'big5', 'gb18030'].map((enc) => ({ enc, t: tryDecode(u8, enc, true) })).filter((c) => c.t !== null);
  const sj = cand.find((c) => c.enc === 'shift_jis');
  if (sj && count(sj.t, SCRIPTS.kana) / wide(sj.t) > 0.3) return 'shift_jis'; // real Japanese is mostly kana
  const kr = cand.find((c) => c.enc === 'euc-kr');
  if (kr && count(kr.t, SCRIPTS.hangul) / wide(kr.t) > 0.9) return 'euc-kr'; // real Korean is almost all Hangul
  // Big5 uses trail bytes 0x40-0x7e often; GBK trail bytes are almost always >= 0x80 for common characters
  let trailLow = 0, trailAll = 0;
  for (let i = 0; i < u8.length - 1; i++) if (u8[i] >= 0x81 && u8[i] <= 0xfe) { trailAll++; if (u8[i + 1] < 0x7f) trailLow++; i++; }
  if (cand.find((c) => c.enc === 'big5') && trailAll && trailLow / trailAll > 0.25) return 'big5';
  if (cand.find((c) => c.enc === 'gb18030')) return 'gb18030';
  return 'windows-1252';
}

/** Decode subtitle bytes. `override` forces an encoding; otherwise it is detected (the language hint sharpens the guess). */
export function decodeSubtitleBytes(buf, override, lang = '') {
  const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  const enc = override || detectEncoding(u8, { lang });
  let text = tryDecode(u8, enc);
  if (text === null) text = new TextDecoder('windows-1252').decode(u8);
  return text.replace(/^﻿/, '');
}

// ---------- style presets (L070) ----------
export const SUB_PRESETS = [
  { id: 'standard', label: 'Standard', hint: 'White with an outline shadow', values: { subSize: 100, subColor: '#ffffff', subBg: 'shadow', subBold: false, subBgOpacity: 0.6 } },
  { id: 'contrast', label: 'High contrast', hint: 'Bold white on solid black', values: { subSize: 120, subColor: '#ffffff', subBg: 'box', subBold: true, subBgOpacity: 1 } },
  { id: 'yellow', label: 'Yellow on black', hint: 'Bold yellow on solid black', values: { subSize: 120, subColor: '#ffe27a', subBg: 'box', subBold: true, subBgOpacity: 1 } },
  { id: 'large', label: 'Large print', hint: 'Bigger white text on a dark box', values: { subSize: 160, subColor: '#ffffff', subBg: 'box', subBold: true, subBgOpacity: 0.8 } },
];
/** The preset whose values all match the settings, or 'custom'. */
export function presetOf(s) {
  const hit = SUB_PRESETS.find((p) => Object.entries(p.values).every(([k, v]) => (k === 'subBgOpacity' && p.values.subBg !== 'box') ? true : s[k] === v));
  return hit ? hit.id : 'custom';
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
