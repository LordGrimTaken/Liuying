// Pure player logic (no DOM): stall detection, plain-language errors, sleep timer, stats text, audio choice.
import { langCode, languageName } from './subtitles.js';

// ---------- stall watch (L048) ----------
export const STALL_MS = 15000;
/**
 * Calls onStall once when buffering has lasted `ms`. begin() on "waiting", end() on "playing"/"canplay".
 * After a recovery the next stall can fire again.
 */
export function createStallWatch({ ms = STALL_MS, onStall, setT = setTimeout, clearT = clearTimeout } = {}) {
  let t = null;
  return {
    begin() { if (t === null) t = setT(() => { t = null; onStall?.(); }, ms); },
    end() { if (t !== null) { clearT(t); t = null; } },
    get active() { return t !== null; },
  };
}

// ---------- plain-language errors (L050) ----------
const ACTIONS = { retry: 'Retry', another: 'Try another stream', close: 'Close' };
export const ERROR_ACTIONS = ACTIONS;

/**
 * Explain a playback failure. Input is whatever the player knows: the MediaError code (2 network, 3 decode, 4 unsupported),
 * an HTTP status if one was observed, an hls.js error type/details, and whether the browser is offline.
 * Returns {id, title, why, next, primary, actions}. Every class says what happened, why it likely happened, and what to do.
 */
export function playbackErrorInfo({ mediaCode = 0, status = 0, hlsType = '', hlsDetail = '', offline = false, timedOut = false, noHls = false } = {}) {
  const make = (id, title, why, next, primary, actions = ['retry', 'another', 'close']) => ({ id, title, why, next, primary, actions });
  if (noHls) return make('nohls', "This browser can't play HLS streams", 'HLS (.m3u8) needs a modern browser; this one has no support for it.', 'Try another stream, or open Liuying in Chrome, Edge or Firefox.', 'another', ['another', 'close']);
  if (offline) return make('offline', "You're offline", 'The network connection dropped, so the stream cannot load.', 'Reconnect, then retry. Your place is saved.', 'retry');
  if (status === 401 || status === 403) return make('forbidden', 'The source refused this link', `The server answered “${status} ${status === 401 ? 'unauthorised' : 'forbidden'}”. The link has probably expired, or it only works for the person or region it was made for.`, 'Choose the stream again for a fresh link, or try another stream.', 'another');
  if (status === 404 || status === 410) return make('gone', 'This stream is no longer there', `The server answered “${status}”: the file was moved or removed.`, 'Try another stream.', 'another', ['another', 'retry', 'close']);
  if (status === 429) return make('busy', 'The source is busy', 'It is limiting how fast it will serve you (HTTP 429).', 'Wait a minute and retry, or try another stream.', 'retry');
  if (status >= 500) return make('server', "The stream's server had a problem", `It answered with an error (HTTP ${status}). This is usually temporary.`, 'Retry in a moment, or try another stream.', 'retry');
  if (timedOut) return make('timeout', 'The stream took too long to start', 'The server did not respond in time. It may be overloaded, or your connection is slow.', 'Retry, or try another stream. Patient mode in Settings waits longer.', 'retry');
  const decode = mediaCode === 3 || /bufferAddCodecError|bufferIncompatibleCodecsError|fragParsingError|manifestIncompatibleCodecsError|bufferAppendError|fragDecryptError/i.test(hlsDetail) || /^mediaError$/i.test(hlsType);
  if (decode) return make('codec', "Your browser can't decode this video", 'The video or audio uses a format this browser does not support (for example HEVC/H.265 10-bit, AC3 or DTS audio).', 'Try another stream, ideally one labelled H.264 or AAC, or use the Android app for exotic formats.', 'another');
  if (mediaCode === 2 || /^networkError$/i.test(hlsType)) return make('network', 'The connection to this stream was lost', 'The data stopped arriving. This is usually your connection or the source.', 'Retry. If it keeps happening, try another stream.', 'retry');
  if (mediaCode === 4) return make('format', "This format isn't supported, or the link expired", "Either the browser doesn't understand this file type, or the source's link has stopped working (links often expire after a while).", 'Choose the stream again for a fresh link, or try another stream.', 'another');
  return make('unknown', 'This stream could not be played', 'Something went wrong that the browser did not explain.', 'Retry, or try another stream.', 'retry');
}

// ---------- sleep timer (L052) ----------
export const SLEEP_CHOICES = [['off', 'Off'], ['15', '15 minutes'], ['30', '30 minutes'], ['60', '60 minutes'], ['end', 'End of this episode']];
/** timer = {mode:'minutes', endsAt} | {mode:'end'} | null. */
export function startSleep(choice, now = Date.now()) {
  if (choice === 'end') return { mode: 'end' };
  const m = Number(choice);
  return Number.isFinite(m) && m > 0 ? { mode: 'minutes', endsAt: now + m * 60000 } : null;
}
/** {remainingMs, expired}; 'end' mode never expires on the clock (the player stops it at the end of the video). */
export function sleepStatus(timer, now = Date.now()) {
  if (!timer) return { remainingMs: null, expired: false };
  if (timer.mode === 'end') return { remainingMs: null, expired: false };
  const remainingMs = Math.max(0, timer.endsAt - now);
  return { remainingMs, expired: remainingMs <= 0 };
}
export function sleepLabel(timer, now = Date.now()) {
  if (!timer) return '';
  if (timer.mode === 'end') return 'End of episode';
  const s = Math.ceil(sleepStatus(timer, now).remainingMs / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

// ---------- stats overlay (L049) ----------
const fmtMbps = (bps) => (bps >= 1e6 ? `${(bps / 1e6).toFixed(1)} Mbps` : `${Math.round(bps / 1000)} kbps`);
/** Rows [label, value] for the stats overlay. Unknown values are left out, never shown as 0. */
export function statsRows({ width = 0, height = 0, bandwidth = 0, levelBitrate = 0, dropped, total, bufferAhead, speed = 1, kind = '', host = '' } = {}) {
  const rows = [];
  if (width && height) rows.push(['Resolution', `${width}×${height}`]);
  if (levelBitrate) rows.push(['Stream bitrate', fmtMbps(levelBitrate)]);
  if (bandwidth) rows.push(['Connection speed', fmtMbps(bandwidth)]);
  if (Number.isFinite(dropped) && Number.isFinite(total)) rows.push(['Dropped frames', `${dropped} of ${total}`]);
  if (Number.isFinite(bufferAhead)) rows.push(['Buffer ahead', `${bufferAhead.toFixed(1)} s`]);
  rows.push(['Speed', `${speed}×`]);
  if (kind) rows.push(['Type', kind === 'hls' ? 'HLS (adaptive)' : 'Direct file']);
  if (host) rows.push(['Host', host]);
  return rows;
}
/** Seconds of video buffered beyond the playhead. TimeRanges-like: {length,start(i),end(i)}. */
export function bufferAhead(buffered, t) {
  for (let i = 0; i < (buffered?.length || 0); i++) if (buffered.start(i) <= t + 0.5 && buffered.end(i) >= t) return Math.max(0, buffered.end(i) - t);
  return 0;
}

// ---------- audio language memory (L047) ----------
/** Index of the audio track matching a remembered language, or -1. tracks: [{lang, label}]. */
export function pickAudioIndex(tracks, pref) {
  const want = langCode(pref);
  if (!want || want === 'und') return -1;
  const name = languageName(pref).toLowerCase();
  const i = tracks.findIndex((t) => langCode(t.lang) === want);
  if (i >= 0) return i;
  return tracks.findIndex((t) => name !== 'unknown' && String(t.label || '').toLowerCase().includes(name));
}
