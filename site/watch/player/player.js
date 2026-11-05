import { h, icon, clear, toast as appToast, trapFocus } from '../ui/dom.js';
import * as store from '../store.js';
import { navigate, href, enc } from '../router.js';
import { applySample, resumeStartSec, normalizeVideos, followingEpisode, previousEpisode, parseEpisodeId } from '../progress.js';
import { loadSubtitles, loadStreams } from '../addon/service.js';
import { pickAutoSubtitle, languageName, langCode, fmtDelay, COMMON_LANGS, SUB_PRESETS, presetOf, ENCODING_CHOICES } from './subtitles.js';
import { createStallWatch, playbackErrorInfo, startSleep, sleepStatus, sleepLabel, SLEEP_CHOICES, statsRows, bufferAhead, pickAudioIndex, ERROR_ACTIONS } from './playback.js';
import { rememberStream } from '../ui/streams.js';
import { pickSimilar, pickBestPlayable, sortStreams } from '../addon/streams.js';
import { fmtBytes } from '../ux.js';
import { SubtitleController } from './subs-controller.js';
import { fmtTime, clamp } from '../util.js';
import { streamsHref } from '../ui/continue.js';
import { loadMeta } from '../addon/service.js';
import { attachParty } from './party.js';

const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const SWATCHES = [['#ffffff', 'c1', 'White'], ['#ffe27a', 'c2', 'Yellow'], ['#8de0ff', 'c3', 'Cyan'], ['#9be58f', 'c4', 'Green'], ['#ffb3a8', 'c5', 'Rose']];

export function player({ params, query }) {
  const { type, id } = params;
  const videoId = query.v || null;
  const np = store.prefs().nowPlaying;
  if (!np || np.mediaId !== id || (np.videoId || null) !== videoId || !np.stream?.url) {
    queueMicrotask(() => navigate(`/streams/${enc(type)}/${enc(id)}`, videoId ? { v: videoId } : undefined, { replace: true }));
    return {};
  }
  let stream = np.stream;
  const view = document.getElementById('view');
  document.body.classList.add('playing');
  const S = () => store.settings();
  let meta = store.cachedMeta(id);
  const lib = store.library().find((i) => i.id === id);
  const prevRec = store.getProgress(id, videoId);
  let title = meta?.name || lib?.name || prevRec?.name || id;
  let poster = meta?.poster || lib?.poster || prevRec?.poster || '';
  let episodes = meta ? normalizeVideos(meta.videos) : [];
  const makeLabel = () => {
    if (!videoId) return '';
    const e = episodes.find((v) => v.id === videoId), pe = parseEpisodeId(videoId);
    return [`S${e?.season ?? pe?.season ?? '?'} E${e?.episode ?? pe?.episode ?? '?'}`, e?.title].filter(Boolean).join(' · ');
  };
  let epLabel = makeLabel();
  const longStep = () => clamp(Number(S().longSkip) || 85, 5, 300);
  const cleanups = [];
  const on = (t, ev, fn, opt) => { t.addEventListener(ev, fn, opt); cleanups.push(() => t.removeEventListener(ev, fn, opt)); };
  const ctl = new AbortController();

  // ---------- DOM ----------
  const video = h('video', { playsinline: true, preload: 'auto', 'aria-label': `${title} video`, crossorigin: null });
  video.setAttribute('playsinline', '');
  const cuesBox = h('div', { class: 'cues', 'aria-hidden': 'true' });
  const surface = h('div', { class: 'surface' });
  const seek = h('input', { type: 'range', min: 0, max: 1000, step: 1, value: 0, 'aria-label': 'Seek', onInput: onScrub, onChange: onScrubEnd });
  const bufEl = h('div', { class: 'buf' });
  const curT = h('time', { text: '0:00' });
  const durT = h('time', { text: '0:00' });
  const volume = h('input', { class: 'rng vol', type: 'range', min: 0, max: 1, step: 0.05, value: 1, 'aria-label': 'Volume', onInput: () => { video.volume = Number(volume.value); video.muted = false; } });
  const playBtns = [];
  const mkPlay = (cls) => { const b = h('button', { class: `ibtn ${cls || ''}`, type: 'button', 'aria-label': 'Play', onClick: togglePlay }, icon('play')); playBtns.push(b); return b; };
  const bigPlay = mkPlay('big');
  const smallPlay = mkPlay('');
  const muteBtn = h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Mute', onClick: toggleMute }, icon('volume'));
  const fsBtn = h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Fullscreen', onClick: toggleFs }, icon('full'));
  const pipBtn = document.pictureInPictureEnabled ? h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Picture in picture', onClick: togglePip }, icon('pip')) : null;
  const ccBtn = h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Subtitles (c)', 'aria-pressed': 'false', onClick: () => openPanel('subs') }, icon('cc'));
  const audioBtn = h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Audio and quality', hidden: true, onClick: () => openPanel('audio') }, icon('audio'));
  const spdBtn = h('button', { class: 'spd', type: 'button', 'aria-label': 'Playback speed', text: '1×', onClick: () => openPanel('play') });
  const lskipBtn = h('button', { class: 'ibtn lskip', type: 'button', 'aria-label': `Skip forward ${longStep()} seconds (S)`, title: `Skip forward ${longStep()} s (S)`, onClick: () => skip(longStep()) }, icon('ffwd', 20), h('span', { class: 'lk', 'aria-hidden': 'true', text: `+${longStep()}` }));
  const prevBtn = h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Previous episode (Shift+P)', hidden: true, onClick: () => goPrev() }, icon('skipPrev', 22));
  const nextBtn = h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Next episode (Shift+N)', hidden: true, onClick: () => goNext() }, icon('skipNext', 22));
  const sleepChip = h('button', { class: 'btn sm sleepchip', type: 'button', hidden: true, 'aria-label': 'Sleep timer. Open to change.', onClick: () => openPanel('play') }, icon('moon', 16), h('span', { class: 'sl-t' }));
  const suggestEl = h('div', { class: 'pl-suggest', role: 'status', hidden: true });
  const statsEl = h('div', { class: 'pl-stats', hidden: true });
  const loadEl = h('div', { class: 'pl-load', role: 'status' }, h('span', { class: 'spin' }), h('span', { text: `Opening ${title}…` }));
  const errEl = h('div', { class: 'pl-err', role: 'alert', hidden: true });
  const toastEl = h('div', { class: 'pl-toast', role: 'status' });
  const nextEl = h('div', { class: 'pl-next', hidden: true });
  const panel = h('div', { class: 'pl-panel', role: 'dialog', 'aria-label': 'Player settings' });
  const resumeEl = h('div', { class: 'pl-center ask', hidden: true });

  const root = h('div', { class: 'player ui loading', tabindex: '-1' },
    video, cuesBox, surface,
    h('div', { class: 'pl-shade top' }), h('div', { class: 'pl-shade bot' }),
    h('div', { class: 'pl-top' },
      h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Back to details', onClick: exit }, icon('back')),
      h('div', { class: 'ttl' }, h('b', { id: 'pl-title', text: title }), h('span', { id: 'pl-ep', text: epLabel })),
      sleepChip, h('button', { class: 'btn sm', type: 'button', onClick: () => openStreams() }, 'Streams')),
    h('div', { class: 'pl-center' }, loadEl, errEl),
    resumeEl,
    h('div', { class: 'pl-mid' },
      h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Back 10 seconds', onClick: () => skip(-step()) }, icon('back10', 30)), bigPlay,
      h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Forward 10 seconds', onClick: () => skip(step()) }, icon('fwd10', 30))),
    h('div', { class: 'pl-bot' },
      h('div', { class: 'pl-seek' }, curT, h('div', { class: 'track' }, h('div', { class: 'base' }), bufEl, seek), durT),
      h('div', { class: 'pl-btns' }, smallPlay, prevBtn,
        h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Back 10 seconds', onClick: () => skip(-step()) }, icon('back10')),
        h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Forward 10 seconds', onClick: () => skip(step()) }, icon('fwd10')),
        lskipBtn, nextBtn,
        muteBtn, volume, h('span', { class: 'sp' }), spdBtn, ccBtn, audioBtn, pipBtn, fsBtn)),
    nextEl, suggestEl, statsEl, panel, toastEl);
  clear(view).append(root);
  const step = () => S().seekStep || 10;
  // fix aria labels with current step
  root.querySelectorAll('[aria-label="Back 10 seconds"]').forEach((b) => b.setAttribute('aria-label', `Back ${step()} seconds`));
  root.querySelectorAll('[aria-label="Forward 10 seconds"]').forEach((b) => b.setAttribute('aria-label', `Forward ${step()} seconds`));

  // ---------- helpers ----------
  let toastT;
  function pToast(msg) { toastEl.textContent = msg; toastEl.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(() => toastEl.classList.remove('on'), 2200); }
  const dur = () => (Number.isFinite(video.duration) ? video.duration : 0);
  // a nudge with a button (the plain toast ignores clicks)
  let suggestT;
  function hideSuggest() { suggestEl.hidden = true; clearTimeout(suggestT); }
  function suggest(msg, label, fn) {
    clear(suggestEl).append(h('span', { text: msg }), h('button', { class: 'btn pri sm', type: 'button', text: label, onClick: () => { hideSuggest(); fn(); } }),
      h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Dismiss', onClick: hideSuggest }, icon('close', 16)));
    suggestEl.hidden = false; showUi(); clearTimeout(suggestT); suggestT = setTimeout(hideSuggest, 25000);
  }
  // Buffering for more than 15 s (or never starting) offers another stream (L048)
  const stall = createStallWatch({ onStall: () => { if (!errEl.hidden || ctl.signal.aborted) return; suggest(video.readyState < 2 && !startedOnce ? 'This stream is slow to start.' : 'Still buffering. This stream may be slow.', ERROR_ACTIONS.another, openStreams); } });

  // ---------- subtitles ----------
  const subs = new SubtitleController(video, cuesBox, { onChange: () => { if (panelOpen) renderPanel(); ccBtn.setAttribute('aria-pressed', String(!!subs.current)); } });
  function applySubStyle() {
    const s = S();
    root.style.setProperty('--sub-size', String((s.subSize || 100) / 100));
    root.style.setProperty('--sub-color', s.subColor || '#fff');
    root.style.setProperty('--sub-bottom', (s.subOffset ?? 8) + '%');
    root.style.setProperty('--sub-bgo', String(s.subBgOpacity ?? 0.6));
    root.style.setProperty('--sub-w', s.subBold ? '700' : '500');
    subs.setStyle({ bg: s.subBg || 'shadow' });
  }
  applySubStyle();
  let manualSub = false;
  const prefLangs = () => {
    const per = store.prefs().subLang?.[id];
    return [...(per && per !== 'off' ? [per] : []), ...(S().subLangs || ['en'])];
  };
  async function autoSubtitle() {
    if (manualSub) return;
    if (store.prefs().subLang?.[id] === 'off') return;
    const pick = pickAutoSubtitle(subs.entries, prefLangs(), S().subMode);
    if (pick && !subs.current) {
      const r = await subs.select(pick);
      if (r.ok && !manualSub) pToast(`Subtitles: ${pick.langName}`);
    }
  }
  function loadSubs() {
    if (stream.subtitles?.length) subs.addGroup({ source: stream.sourceName || 'Stream', origin: 'stream', items: stream.subtitles });
    const h2 = stream.hints || {};
    const extra = {};
    if (h2.videoHash) extra.videoHash = h2.videoHash;
    if (h2.videoSize) extra.videoSize = h2.videoSize;
    if (h2.filename) extra.filename = h2.filename;
    subsState.loading = true;
    loadSubtitles(type, videoId || id, extra, {
      signal: ctl.signal,
      onResult: (r) => {
        if (r.ok) subs.addGroup({ source: r.source.manifest?.name || '', origin: 'source', items: r.value });
        autoSubtitle();
      },
    }).then(() => { subsState.loading = false; subs.onChange(); autoSubtitle(); });
    autoSubtitle();
  }
  const subsState = { loading: false, all: false };

  // ---------- media setup ----------
  let hls = null;
  let resumedAt = 0;
  let startedOnce = false;
  let retries = 0;
  let wantPlaying = true;
  let lastPos = 0;
  let mediaRecoveries = 0;
  let lastStatus = 0;
  let audioApplied = false;
  let hlsLoading = null;
  function loadHls() {
    if (window.Hls) return Promise.resolve();
    if (!hlsLoading) hlsLoading = new Promise((resolve, reject) => {
      const el = document.createElement('script');
      el.src = '/vendor/hls.min.js';
      el.onload = resolve;
      el.onerror = () => { hlsLoading = null; el.remove(); reject(new Error('hls.js failed to load')); };
      document.head.append(el);
    });
    return hlsLoading;
  }
  function attach(startAt) {
    teardownHls();
    clear(errEl); errEl.hidden = true;
    root.classList.remove('err');
    root.classList.add('loading');
    stall.end(); stall.begin(); // opening counts as buffering too
    const url = stream.url;
    const isHls = stream.kind === 'hls';
    // hls.js (400 KB) is loaded on demand; browsers with native HLS (Safari) never need it
    if (isHls && !window.Hls && !video.canPlayType('application/vnd.apple.mpegurl')) {
      loadHls().then(() => { if (!exited && stream.url === url) attach(startAt); }).catch(() => showError("Couldn't load the HLS player. Check your connection and try again."));
      return;
    }
    if (isHls && window.Hls && window.Hls.isSupported()) {
      hls = new window.Hls({ enableWorker: true, startPosition: startAt > 0 ? startAt : -1 });
      hls.on(window.Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal) return;
        lastStatus = data.response?.code || lastStatus;
        if (data.type === window.Hls.ErrorTypes.NETWORK_ERROR) return netError(() => hls?.startLoad());
        if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries < 3) {
          mediaRecoveries++;
          try { if (mediaRecoveries === 2) hls.swapAudioCodec(); hls.recoverMediaError(); return; } catch { /* fall through */ }
        }
        showError(playbackErrorInfo({ status: lastStatus, hlsType: data.type, hlsDetail: data.details, offline: !navigator.onLine }));
      });
      hls.on(window.Hls.Events.MANIFEST_PARSED, () => { refreshAudio(); });
      hls.on(window.Hls.Events.AUDIO_TRACKS_UPDATED, refreshAudio);
      hls.loadSource(url);
      hls.attachMedia(video);
    } else if (isHls && !video.canPlayType('application/vnd.apple.mpegurl')) {
      showError(playbackErrorInfo({ noHls: true }));
      return;
    } else {
      video.src = url;
      if (startAt > 0) video.addEventListener('loadedmetadata', () => { try { video.currentTime = startAt; } catch { /* ignore */ } }, { once: true });
    }
  }
  function teardownHls() { if (hls) { try { hls.destroy(); } catch { /* ignore */ } hls = null; } }

  function netError(retryFn) {
    if (retries >= 4) { showError(playbackErrorInfo({ mediaCode: 2, status: lastStatus, offline: !navigator.onLine })); return; }
    const wait = [1500, 3000, 6000, 12000][retries++];
    loadEl.lastChild.textContent = 'Reconnecting…';
    root.classList.add('loading');
    setTimeout(() => { if (ctl.signal.aborted) return; retryFn ? retryFn() : attach(lastPos); }, wait);
  }

  /** info: playbackErrorInfo() result, or a plain string for one-off messages. Says what happened, why, and what to do. */
  function showError(info) {
    if (typeof info === 'string') info = { title: "This stream didn't play", why: info, next: '', primary: 'retry', actions: ['retry', 'another', 'close'] };
    stall.end(); hideSuggest();
    root.classList.remove('loading'); root.classList.add('ui', 'err');
    const act = {
      retry: () => h('button', { class: 'btn' + (info.primary === 'retry' ? ' pri' : ''), type: 'button', text: ERROR_ACTIONS.retry, onClick: () => { retries = 0; mediaRecoveries = 0; attach(lastPos || 0); } }),
      another: () => h('button', { class: 'btn' + (info.primary === 'another' ? ' pri' : ''), type: 'button', text: ERROR_ACTIONS.another, onClick: () => openStreams() }),
      close: () => h('button', { class: 'btn', type: 'button', text: ERROR_ACTIONS.close, onClick: exit }),
    };
    const order = [info.primary, ...info.actions.filter((a) => a !== info.primary)].filter((a, i, arr) => arr.indexOf(a) === i && info.actions.includes(a));
    clear(errEl).append(h('h3', { text: info.title }), h('p', { class: 'why', text: info.why }), info.next ? h('p', { class: 'next', text: info.next }) : null,
      h('div', { class: 'btns' }, order.map((a) => act[a]())));
    errEl.hidden = false;
    errEl.querySelector('button')?.focus();
  }
  /** Best-effort: ask the server why the video failed (only works when the source allows CORS). */
  async function probeStatus(url) {
    try {
      const c = new AbortController(); const t = setTimeout(() => c.abort(), 3000);
      const res = await fetch(url, { method: 'GET', headers: { Range: 'bytes=0-0' }, credentials: 'omit', signal: c.signal });
      clearTimeout(t);
      try { res.body?.cancel(); } catch { /* ignore */ }
      return res.ok ? 0 : res.status;
    } catch { return 0; }
  }
  on(video, 'error', async () => {
    const c = video.error?.code;
    if (!video.src && !hls) return;
    if (c === 2) return netError();
    const status = c === 3 || c === 4 ? await probeStatus(stream.url) : 0;
    if (exited) return;
    showError(playbackErrorInfo({ mediaCode: c, status, offline: !navigator.onLine }));
  });

  on(video, 'loadedmetadata', () => {
    spdBtn.textContent = '1×';
    video.playbackRate = S().speed || 1; spdBtn.textContent = `${video.playbackRate}×`;
    durT.textContent = fmtTime(dur());
    refreshAudio();
    if (!startedOnce) {
      startedOnce = true;
      const pos = S().resume === 'never' ? 0 : resumeStartSec(prevRec, dur() * 1000, S().threshold, S().resumeRewind);
      if (pos > 1) {
        if (S().resume === 'ask') { askResume(pos); return; }
        try { video.currentTime = pos; } catch { /* ignore */ }
        resumedAt = pos; pToast(`Resumed at ${fmtTime(pos)}`);
      }
      start();
    } else if (wantPlaying) video.play().catch(() => {});
  });
  function askResume(pos) {
    resumeEl.hidden = false; root.classList.add('asking');
    const go = (p) => { resumeEl.hidden = true; root.classList.remove('asking'); try { video.currentTime = p; } catch { /* ignore */ } start(); };
    clear(resumeEl).append(h('div', { class: 'pl-resume' }, h('span', { text: `Resume at ${fmtTime(pos)}?` }), h('button', { class: 'btn pri sm', type: 'button', text: 'Resume', onClick: () => go(pos) }), h('button', { class: 'btn sm', type: 'button', text: 'Start over', onClick: () => go(0) })));
    resumeEl.querySelector('button').focus();
  }
  function start() {
    video.play().catch(() => { wantPlaying = false; syncPlayState(); showUi(true); });
  }
  on(video, 'canplay', () => { root.classList.remove('loading'); retries = 0; stall.end(); });
  on(video, 'playing', () => { retries = 0; mediaRecoveries = 0; root.classList.remove('loading'); errEl.hidden = true; stall.end(); hideSuggest(); syncPlayState(); startSaveTimer(); autoSubtitleOnce(); });
  on(video, 'waiting', () => { loadEl.lastChild.textContent = 'Buffering…'; root.classList.add('loading'); stall.begin(); });
  on(video, 'seeking', () => { if (video.readyState < 3) root.classList.add('loading'); });
  on(video, 'seeked', () => { saveProgress(); });
  on(video, 'pause', () => { stall.end(); syncPlayState(); saveProgress(); });
  on(video, 'play', syncPlayState);
  on(video, 'ratechange', () => { spdBtn.textContent = `${video.playbackRate}×`; });
  on(video, 'volumechange', () => {
    volume.value = video.muted ? 0 : video.volume; paintRange(volume);
    muteBtn.replaceChildren(icon(video.muted || video.volume === 0 ? 'mute' : 'volume'));
    muteBtn.setAttribute('aria-label', video.muted ? 'Unmute' : 'Mute');
    store.setPref('volume', { v: video.volume, m: video.muted });
  });
  on(video, 'durationchange', () => { durT.textContent = fmtTime(dur()); });
  on(video, 'progress', paintBuffer);
  on(video, 'timeupdate', onTime);
  on(video, 'ended', () => { saveProgress(true); if (sleepTimer?.mode === 'end') { fireSleep(); return; } const nx = nextEp(); if (nx && S().autoNext) goNext(); else if (nx) showNext(true); else exitSoon(); });
  let subTried = false;
  function autoSubtitleOnce() { if (!subTried) { subTried = true; loadSubs(); } }

  function syncPlayState() {
    const playing = !video.paused && !video.ended;
    for (const b of playBtns) { b.replaceChildren(icon(playing ? 'pause' : 'play', b.classList.contains('big') ? 32 : 22)); b.setAttribute('aria-label', playing ? 'Pause' : 'Play'); }
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = playing ? 'playing' : 'paused';
    scheduleHide();
  }

  function paintRange(r) { const min = Number(r.min || 0), max = Number(r.max || 1); r.style.setProperty('--v', ((Number(r.value) - min) / (max - min)) * 100 + '%'); }
  function paintBuffer() {
    const d = dur();
    if (!d || !video.buffered.length) return;
    let end = 0;
    for (let i = 0; i < video.buffered.length; i++) if (video.buffered.start(i) <= video.currentTime + 0.5) end = Math.max(end, video.buffered.end(i));
    bufEl.style.setProperty('--b', Math.min(100, (end / d) * 100) + '%');
  }
  let scrubbing = false;
  function onScrub() { scrubbing = true; const d = dur(); curT.textContent = fmtTime((Number(seek.value) / 1000) * d); paintRange(seek); }
  function onScrubEnd() { const d = dur(); if (d) video.currentTime = (Number(seek.value) / 1000) * d; scrubbing = false; }
  let nextShown = false, nextDismissed = false;
  function onTime() {
    const d = dur(), t = video.currentTime;
    lastPos = t;
    if (d && !scrubbing) { seek.value = String(Math.round((t / d) * 1000)); paintRange(seek); curT.textContent = fmtTime(t); }
    seek.setAttribute('aria-valuetext', `${fmtTime(t)} of ${fmtTime(d)}`);
    if (d > 90 && d - t <= 30 && !nextDismissed && nextEp() && !nextShown && !video.paused) showNext();
    if (nextShown && d - t > 40) hideNext();
  }

  // ---------- controls ----------
  function togglePlay() { if (video.paused || video.ended) { wantPlaying = true; video.play().catch(() => {}); } else { wantPlaying = false; video.pause(); } }
  function skip(sec) { const d = dur(); video.currentTime = clamp(video.currentTime + sec, 0, d || video.currentTime + sec); pToast(`${sec > 0 ? '+' : '−'}${Math.abs(sec)} s`); }
  function toggleMute() { video.muted = !video.muted; }
  function toggleFs() {
    if (document.fullscreenElement) document.exitFullscreen?.();
    else if (root.requestFullscreen) root.requestFullscreen().catch(() => {});
    else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
  }
  on(document, 'fullscreenchange', () => { const f = !!document.fullscreenElement; fsBtn.replaceChildren(icon(f ? 'exitFull' : 'full')); fsBtn.setAttribute('aria-label', f ? 'Exit fullscreen' : 'Fullscreen'); });
  async function togglePip() { try { if (document.pictureInPictureElement) await document.exitPictureInPicture(); else await video.requestPictureInPicture(); } catch { pToast('Picture in picture is not available'); } }

  // auto-hide
  let hideT;
  function showUi(keep) { root.classList.add('ui'); root.classList.remove('nocursor'); if (!keep) scheduleHide(); else clearTimeout(hideT); }
  function scheduleHide() {
    clearTimeout(hideT);
    if (video.paused || panelOpen || errEl.hidden === false) return;
    hideT = setTimeout(() => { if (!root.matches(':focus-within .pl-btns:hover, .pl-bot:hover')) { root.classList.remove('ui'); root.classList.add('nocursor'); } else scheduleHide(); }, 3500);
  }
  on(root, 'pointermove', () => showUi());
  on(root, 'focusin', () => showUi());
  let lastTap = 0, tapT;
  on(surface, 'pointerup', (e) => {
    if (e.button > 0) return;
    const now = Date.now(); clearTimeout(tapT);
    const dbl = now - lastTap < 320; lastTap = now;
    if (panelOpen) { closePanel(); return; }
    if (e.pointerType === 'mouse') {
      if (dbl) { lastTap = 0; toggleFs(); } else tapT = setTimeout(togglePlay, 250);
    } else if (dbl) {
      lastTap = 0;
      const r = surface.getBoundingClientRect(); const x = (e.clientX - r.left) / r.width;
      if (x < 0.4) skip(-step()); else if (x > 0.6) skip(step()); else togglePlay();
    } else tapT = setTimeout(() => { root.classList.contains('ui') ? root.classList.remove('ui') : showUi(); }, 250);
  });

  // ---------- keyboard ----------
  on(document, 'keydown', (e) => {
    const t = e.target;
    if (t && (t.tagName === 'INPUT' && t.type !== 'range' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA')) return;
    if (document.getElementById('modal') && !document.getElementById('modal').hidden) return;
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const k = e.key;
    const rangeFocus = t && t.tagName === 'INPUT' && t.type === 'range';
    const btnFocus = t && t.tagName === 'BUTTON' && (k === ' ' || k === 'Enter');
    if (btnFocus) { showUi(); return; }
    switch (k) {
      case ' ': case 'k': e.preventDefault(); togglePlay(); break;
      case 'ArrowLeft': if (rangeFocus && t === seek) break; e.preventDefault(); skip(-step()); break;
      case 'ArrowRight': if (rangeFocus && t === seek) break; e.preventDefault(); skip(step()); break;
      case 'ArrowUp': if (rangeFocus) break; e.preventDefault(); video.volume = clamp(video.volume + 0.05, 0, 1); video.muted = false; pToast(`Volume ${Math.round(video.volume * 100)}%`); break;
      case 'ArrowDown': if (rangeFocus) break; e.preventDefault(); video.volume = clamp(video.volume - 0.05, 0, 1); pToast(`Volume ${Math.round(video.volume * 100)}%`); break;
      case 'f': case 'F': toggleFs(); break;
      case 'm': case 'M': toggleMute(); break;
      case 'c': case 'C': toggleSubs(); break;
      case 's': case 'S': skip(longStep()); break;
      case 'i': case 'I': setStats(!statsOn); break;
      case 'p': if (pipBtn) togglePip(); break;
      case 'P': goPrev(); break;
      case 'N': goNext(); break;
      case 'Escape': if (panelOpen) closePanel(); else if (!document.fullscreenElement) exit(); break;
      default: return;
    }
    showUi();
  });
  function toggleSubs() {
    if (subs.current) { lastSub = subs.current; subs.select(null); pToast('Subtitles off'); }
    else {
      const e = lastSub || pickAutoSubtitle(subs.entries, prefLangs(), 'on') || subs.entries[0];
      if (!e) { pToast('No subtitles available'); openPanel('subs'); return; }
      subs.select(e).then((r) => pToast(r.ok ? `Subtitles: ${e.langName}` : r.error));
    }
  }
  let lastSub = null;

  // ---------- next episode ----------
  const nextEp = () => (videoId && episodes.length ? followingEpisode(episodes, videoId) : null);
  let nextTimer;
  function showNext(manual) {
    const nx = nextEp(); if (!nx) return;
    nextShown = true;
    let n = S().countdown || 10;
    const bar = h('div', { class: 'bar' }, h('i'));
    const lbl = h('p', { text: '' });
    clear(nextEl).append(h('h4', { text: 'Up next' }), h('p', { class: 'serif', text: `S${nx.season} E${nx.episode}${nx.title ? ' · ' + nx.title : ''}` }), manual ? null : bar,
      h('div', { class: 'btns' }, h('button', { class: 'btn pri sm', type: 'button', text: 'Play now', onClick: goNext }), h('button', { class: 'btn sm', type: 'button', text: 'Cancel', onClick: () => { nextDismissed = true; hideNext(); } })), lbl);
    nextEl.hidden = false;
    if (manual || !S().autoNext) return;
    const total = n; lbl.textContent = `Starting in ${n} s`;
    clearInterval(nextTimer);
    nextTimer = setInterval(() => { n--; bar.firstChild.style.setProperty('--p', ((total - n) / total) * 100 + '%'); lbl.textContent = `Starting in ${n} s`; if (n <= 0) { clearInterval(nextTimer); goNext(); } }, 1000);
  }
  function hideNext() { nextShown = false; nextEl.hidden = true; clearInterval(nextTimer); }
  const prevEp = () => (videoId && episodes.length ? previousEpisode(episodes, videoId) : null);
  const epName = (e) => `S${e.season} E${e.episode}${e.title ? ' · ' + e.title : ''}`;
  function syncEpButtons() {
    const p = prevEp(), n = nextEp();
    prevBtn.hidden = !p; nextBtn.hidden = !n;
    if (p) prevBtn.setAttribute('aria-label', `Previous episode: ${epName(p)} (Shift+P)`);
    if (n) nextBtn.setAttribute('aria-label', `Next episode: ${epName(n)} (Shift+N)`);
  }
  /**
   * Go to another episode without the stream list: look for the same kind of stream (binge group, same source and quality),
   * else the best playable one, and start it. Falls back to the list when nothing playable turns up.
   */
  let switching = false;
  async function switchEpisode(ep) {
    if (switching) return;
    switching = true;
    clearInterval(nextTimer); hideNext(); hideSuggest();
    saveProgress(true);
    loadEl.lastChild.textContent = `Finding a stream for ${epName(ep)}…`;
    root.classList.add('loading');
    const sc = new AbortController();
    const abortAll = () => sc.abort();
    ctl.signal.addEventListener('abort', abortAll, { once: true });
    const all = []; const prev = store.prefs().lastStream?.[id];
    let pick = null;
    try {
      await loadStreams(type, ep.id, { signal: sc.signal, onResult: (r) => {
        if (!r.ok) return;
        all.push(...r.streams);
        if (!pick) { pick = pickSimilar(all, prev); if (pick) sc.abort(); }
      } });
    } catch { /* fall through to the list */ }
    ctl.signal.removeEventListener('abort', abortAll);
    if (ctl.signal.aborted || exited) return;
    pick = pick || pickBestPlayable(all);
    switching = false;
    if (pick) { rememberStream(type, id, ep.id, pick); navigate(`/play/${enc(type)}/${enc(id)}`, { v: ep.id }); return; }
    appToast('No playable stream found automatically. Choose one.');
    navigate(`/streams/${enc(type)}/${enc(id)}`, { v: ep.id });
  }
  function goNext() { const nx = nextEp(); if (nx) switchEpisode(nx); }
  function goPrev() { const pv = prevEp(); if (pv) switchEpisode(pv); }

  // ---------- progress ----------
  let saveInt = null;
  function startSaveTimer() { if (!saveInt) saveInt = setInterval(() => { if (!video.paused) saveProgress(); }, 10000); }
  function saveProgress(final) {
    if (!S().saveHistory) return;
    const d = dur() * 1000;
    if (!d || (!video.currentTime && !final)) return;
    const pos = final && video.ended ? d : video.currentTime * 1000;
    const prev = store.getProgress(id, videoId);
    const rec = applySample(prev, { mediaId: id, episodeId: videoId, positionMs: pos, durationMs: d, now: Date.now(), threshold: S().threshold, meta: { type, name: title, poster } });
    store.putProgress(rec);
  }
  on(window, 'pagehide', () => saveProgress());
  on(document, 'visibilitychange', () => { if (document.visibilityState === 'hidden') saveProgress(); });

  // ---------- side panel ----------
  let panelOpen = false, tab = 'subs';
  const untrap = trapFocus(panel);
  function openPanel(t) { tab = t; panelOpen = true; panel.classList.add('open'); renderPanel(); showUi(true); panel.querySelector('[aria-selected="true"]')?.focus(); }
  function closePanel() { panelOpen = false; panel.classList.remove('open'); scheduleHide(); root.focus(); }
  const tabsDef = [['subs', 'Subtitles'], ['style', 'Style'], ['timing', 'Timing'], ['audio', 'Audio'], ['streams', 'Streams'], ['play', 'Playback']];
  function opt(label, checked, onClick, extra, small) {
    return h('button', { class: 'opt', type: 'button', role: 'menuitemradio', 'aria-checked': String(!!checked), onClick },
      h('span', { class: 'ck' }, checked ? icon('check', 16) : null), h('span', { text: label }), extra || null, small ? h('small', { text: small }) : null);
  }
  function renderPanel() {
    if (!panelOpen) return;
    const keep = panel.querySelector('.body')?.scrollTop || 0;
    const body = h('div', { class: 'body' });
    if (tab === 'subs') {
      const pref = (S().subLangs || ['en']).map(langCode);
      const list = subs.sorted(pref);
      const shown = subsState.all ? list : list.filter((e) => pref.includes(e.lang));
      body.append(opt('Off', !subs.current, () => { manualSub = true; subs.select(null); lastSub = lastSub; store.setPrefMap('subLang', id, 'off'); }));
      if (!list.length) body.append(h('p', { class: 'mute', style: { padding: '8px' }, text: subsState.loading ? 'Looking for subtitles…' : 'No subtitles found for this video.' }));
      if (list.length) body.append(h('div', { class: 'chips', style: { margin: '8px 0' } },
        h('button', { class: 'chip', type: 'button', 'aria-pressed': String(!subsState.all), onClick: () => { subsState.all = false; renderPanel(); } }, 'Preferred'),
        h('button', { class: 'chip', type: 'button', 'aria-pressed': String(subsState.all), onClick: () => { subsState.all = true; renderPanel(); } }, 'All languages')));
      if (list.length && !shown.length) body.append(h('p', { class: 'mute', style: { padding: '8px' }, text: 'None in your preferred languages. Choose All languages.' }));
      let lastLang = '';
      for (const e of shown) {
        if (e.langName !== lastLang) { body.append(h('h4', { text: e.langName })); lastLang = e.langName; }
        const st = subs.status.get(e.key);
        body.append(opt(`${e.label}`, subs.current?.key === e.key, async () => {
          manualSub = true;
          const r = await subs.select(e);
          if (r.ok) { store.setPrefMap('subLang', id, e.lang); pToast(`Subtitles: ${e.langName}`); } else if (r.error !== 'superseded') pToast(r.error);
        }, h('span', {}, e.forced ? h('span', { class: 'tagf', text: 'Forced' }) : null, e.sdh ? h('span', { class: 'tagf', text: 'SDH' }) : null, st === 'loading' ? h('span', { class: 'spin', style: { 'margin-left': '8px' } }) : null, st === 'error' ? h('span', { class: 'tagf', text: 'Failed' }) : null), e.source));
      }
      const file = h('input', { type: 'file', accept: '.srt,.vtt,text/vtt,application/x-subrip', class: 'sr', 'aria-label': 'Choose subtitle file', onChange: async () => {
        const f = file.files[0]; if (!f) return;
        const buf = await f.arrayBuffer();
        const entry = { key: 'file:' + f.name, url: '', lang: S().subLangs?.[0] || 'und', langName: 'File', label: f.name, source: 'This device', origin: 'file', forced: false, sdh: false, n: 1 };
        manualSub = true;
        const r = await subs.select(entry, { fileBuf: buf });
        pToast(r.ok ? `Loaded ${f.name}` : r.error);
      } });
      if (subs.current && subs.raw) body.append(h('h4', { text: 'Wrong characters?' }), h('label', { class: 'encl', for: 'pl-enc', text: 'Text encoding (detected automatically)' }),
        h('select', { class: 'inp', id: 'pl-enc', onChange: async (e) => { const r = await subs.redecode(e.target.value); pToast(r.ok ? 'Re-read the subtitle file' : r.error); } },
          ENCODING_CHOICES.map(([v, l]) => h('option', { value: v, selected: v === (subs.encoding || ''), text: l }))));
      body.append(h('h4', { text: 'Add' }), h('button', { class: 'opt', type: 'button', onClick: () => file.click() }, h('span', { class: 'ck' }, icon('plus', 16)), 'Choose a file from this device (SRT or VTT)'), file);
    } else if (tab === 'style') {
      const s = S();
      const rng = (label, key, min, max, stp, fmt) => {
        const r = h('input', { class: 'rng', type: 'range', min, max, step: stp, value: s[key], 'aria-label': label, onInput: () => { store.setSettings({ [key]: Number(r.value) }); paintRange(r); o.textContent = fmt(Number(r.value)); applySubStyle(); } });
        const o = h('output', { text: fmt(s[key]) }); paintRange(r);
        return h('div', { class: 'ctl' }, h('span', { text: label }), r, o);
      };
      const cur = presetOf(s);
      body.append(h('h4', { text: 'Preset' }), h('div', { class: 'chips wrap presets', role: 'group', 'aria-label': 'Subtitle style presets' },
        SUB_PRESETS.map((p) => h('button', { class: 'chip', type: 'button', title: p.hint, 'aria-pressed': String(cur === p.id), onClick: () => { store.setSettings(p.values); applySubStyle(); renderPanel(); } }, p.label)),
        cur === 'custom' ? h('span', { class: 'chip static', text: 'Custom' }) : null));
      body.append(h('div', { class: 'pl-prev' }, h('div', { class: `cue ${s.subBg}`, style: { '--sub-size': '1' } }, 'The quick brown fox')));
      body.querySelector('.pl-prev .cue').style.setProperty('font-size', `${(s.subSize || 100) / 100 * 22}px`);
      body.querySelector('.pl-prev .cue').style.setProperty('color', s.subColor);
      body.querySelector('.pl-prev .cue').style.setProperty('font-weight', s.subBold ? '700' : '500');
      if (s.subBg === 'box') body.querySelector('.pl-prev .cue').style.setProperty('background', `rgba(0,0,0,${s.subBgOpacity ?? 0.6})`);
      body.append(rng('Size', 'subSize', 50, 200, 5, (v) => `${v}%`), rng('Height', 'subOffset', 0, 40, 1, (v) => `${v}%`));
      body.append(h('h4', { text: 'Colour' }), h('div', { class: 'swatches' }, SWATCHES.map(([c, cls, name]) => h('button', { class: `sw ${cls}`, type: 'button', 'aria-label': name, 'aria-pressed': String(s.subColor === c), onClick: () => { store.setSettings({ subColor: c }); applySubStyle(); renderPanel(); } }))));
      body.append(h('h4', { text: 'Background' }), ...[['shadow', 'Outline shadow'], ['box', 'Dark box'], ['none', 'None']].map(([v, l]) => opt(l, s.subBg === v, () => { store.setSettings({ subBg: v }); applySubStyle(); renderPanel(); })));
      body.append(h('h4', { text: 'Weight' }), opt('Bold', s.subBold, () => { store.setSettings({ subBold: !s.subBold }); applySubStyle(); renderPanel(); }),
        h('button', { class: 'btn sm', type: 'button', style: { 'margin-top': '14px' }, text: 'Reset style', onClick: () => { store.setSettings({ subSize: 100, subColor: '#ffffff', subBg: 'shadow', subBold: false, subOffset: 8, subBgOpacity: 0.6 }); applySubStyle(); renderPanel(); } }));
      if (s.subBg === 'box') body.append(rng('Opacity', 'subBgOpacity', 0, 1, 0.1, (v) => `${Math.round(v * 100)}%`));
    } else if (tab === 'timing') {
      const out = h('output', { class: 'serif', style: { 'font-size': '28px', 'min-width': '110px', 'text-align': 'center' }, text: fmtDelay(subs.delay) });
      const set = (v) => { subs.setDelay(clamp(v, -60, 60)); out.textContent = fmtDelay(subs.delay); };
      body.append(h('p', { class: 'mute', text: 'Shift subtitles earlier (negative) or later (positive) in steps of 0.1 s. Applies to this video.' }),
        h('div', { class: 'ctl', style: { 'justify-content': 'center', margin: '20px 0' } },
          h('button', { class: 'btn sm', type: 'button', 'aria-label': 'Earlier by 0.1 seconds', text: '−0.1', onClick: () => set(subs.delay - 0.1) }),
          h('button', { class: 'btn sm', type: 'button', text: '−1', onClick: () => set(subs.delay - 1) }), out,
          h('button', { class: 'btn sm', type: 'button', text: '+1', onClick: () => set(subs.delay + 1) }),
          h('button', { class: 'btn sm', type: 'button', 'aria-label': 'Later by 0.1 seconds', text: '+0.1', onClick: () => set(subs.delay + 0.1) })),
        h('div', { class: 'ctl', style: { 'justify-content': 'center' } }, h('button', { class: 'btn sm', type: 'button', text: 'Reset', onClick: () => set(0) })));
    } else if (tab === 'audio') {
      const tracks = audioTracks();
      if (!tracks.length) body.append(h('p', { class: 'mute', style: { padding: '8px' }, text: "This stream has a single audio track, or your browser doesn't expose audio tracks for it." }));
      else { body.append(h('h4', { text: 'Audio track' })); tracks.forEach((t) => body.append(opt(t.label, t.enabled, () => { t.select(); if (t.lang) store.setPrefMap('audioLang', id, t.lang); renderPanel(); }, null, t.lang ? 'Remembered for this title' : ''))); }
      if (hls && hls.levels && hls.levels.length > 1) {
        body.append(h('h4', { text: 'Quality' }), opt('Auto', hls.currentLevel === -1 || hls.autoLevelEnabled, () => { hls.currentLevel = -1; renderPanel(); }));
        hls.levels.forEach((lv, i) => body.append(opt(`${lv.height ? lv.height + 'p' : Math.round(lv.bitrate / 1000) + ' kbps'}`, !hls.autoLevelEnabled && hls.currentLevel === i, () => { hls.currentLevel = i; renderPanel(); })));
      }
    } else if (tab === 'streams') {
      renderStreamsTab(body);
    } else {
      body.append(h('h4', { text: 'Speed' }), ...SPEEDS.map((v) => opt(`${v}×`, video.playbackRate === v, () => { video.playbackRate = v; renderPanel(); })));
      body.append(h('h4', { text: 'Picture' }), opt('Fit', !video.classList.contains('fill'), () => { video.classList.remove('fill'); renderPanel(); }), opt('Fill (crop)', video.classList.contains('fill'), () => { video.classList.add('fill'); renderPanel(); }));
      body.append(h('h4', { text: 'Sleep timer' }), ...SLEEP_CHOICES.map(([v, l]) => opt(l, sleepChoice === v, () => setSleep(v), null, sleepChoice === v && sleepTimer?.mode === 'minutes' ? sleepLabel(sleepTimer) : '')));
      body.append(h('h4', { text: 'Diagnostics' }), opt('Playback stats', statsOn, () => setStats(!statsOn), null, 'I'));
      body.append(h('h4', { text: 'Shortcuts' }), h('p', { class: 'mute', style: { padding: '4px 8px', 'font-size': '13px', 'line-height': '1.7' }, text: `Space play/pause · ← → seek ${step()} s · S skip ${longStep()} s · Shift+N / Shift+P next / previous episode · ↑ ↓ volume · F fullscreen · M mute · C subtitles · I stats · P picture in picture · Esc close` }));
    }
    clear(panel).append(
      h('div', { class: 'tabs', role: 'tablist' }, tabsDef.map(([k, l]) => h('button', { type: 'button', role: 'tab', 'aria-selected': String(tab === k), text: l, onClick: () => { tab = k; renderPanel(); panel.querySelector('[aria-selected="true"]').focus(); } })),
        h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Close panel', onClick: closePanel }, icon('close', 18))), body);
    body.scrollTop = keep;
  }
  // ---------- switch stream without leaving the player (L045) ----------
  const alt = { started: false, groups: new Map(), order: [], done: false };
  function loadAlt() {
    alt.started = true; alt.done = false; alt.groups.clear(); alt.order = [];
    const redraw = () => { if (panelOpen && tab === 'streams') renderPanel(); };
    loadStreams(type, videoId || id, {
      signal: ctl.signal,
      onSource: (src) => { alt.groups.set(src.url, { src, status: 'loading', streams: [] }); alt.order.push(src.url); redraw(); },
      onResult: (r) => { const g = alt.groups.get(r.source.url); if (!g) return; if (r.ok) { g.status = 'ok'; g.streams = r.streams; } else g.status = 'err'; redraw(); },
    }).then(() => { alt.done = true; redraw(); }).catch(() => { alt.done = true; redraw(); });
  }
  function openStreams() { openPanel('streams'); if (!alt.started) loadAlt(); }
  function renderStreamsTab(body) {
    body.append(h('p', { class: 'mute', style: { padding: '2px 8px 8px', 'font-size': '13px' }, text: `Pick another stream. Playback continues from ${fmtTime(video.currentTime || lastPos)}.` }));
    let playable = 0, hidden = 0;
    for (const url of alt.order) {
      const g = alt.groups.get(url);
      body.append(h('h4', { text: g.src.manifest?.name || 'Source' }));
      if (g.status === 'loading') { body.append(h('div', { class: 'sk line' }), h('div', { class: 'sk line' })); continue; }
      if (g.status === 'err') { body.append(h('p', { class: 'mute', style: { padding: '4px 8px' }, text: 'This source did not answer.' })); continue; }
      const ok = sortStreams(g.streams.filter((x) => x.cls.playable), 'resolution-size');
      hidden += g.streams.length - ok.length; playable += ok.length;
      if (!ok.length) body.append(h('p', { class: 'mute', style: { padding: '4px 8px' }, text: 'Nothing playable here.' }));
      for (const x of ok) body.append(opt(x.title, x.raw.url === stream.url, () => switchStream(x), null, [x.res, fmtBytes(x.size)].filter(Boolean).join(' · ')));
    }
    if (!alt.order.length) body.append(h('p', { class: 'mute', style: { padding: '8px' }, text: alt.done ? 'None of your enabled sources offers streams for this title.' : 'Looking for streams…' }));
    if (alt.done && alt.order.length && !playable) body.append(h('p', { class: 'mute', style: { padding: '8px' }, text: 'No other playable streams were found.' }));
    if (hidden) body.append(h('p', { class: 'mute', style: { padding: '8px', 'font-size': '12.5px' }, text: `${hidden} more can't play in a browser (torrent, external or blocked links).` }));
    body.append(h('button', { class: 'btn sm', type: 'button', style: { 'margin-top': '12px' }, text: 'Refresh list', onClick: () => { loadAlt(); renderPanel(); } }));
  }
  function switchStream(x) {
    const t = video.currentTime || lastPos;
    saveProgress();
    rememberStream(type, id, videoId, x);
    stream = store.prefs().nowPlaying?.stream || stream;
    wantPlaying = true; retries = 0; mediaRecoveries = 0; audioApplied = false; startedOnce = true;
    if (stream.subtitles?.length) subs.addGroup({ source: stream.sourceName || 'Stream', origin: 'stream', items: stream.subtitles });
    hideSuggest(); closePanel(); errEl.hidden = true; root.classList.remove('err');
    attach(t);
    pToast(`Switched to ${x.title}`);
  }

  // ---------- sleep timer (L052) ----------
  let sleepTimer = null, sleepChoice = 'off', sleepInt = null;
  function setSleep(choice) {
    sleepChoice = choice; sleepTimer = startSleep(choice); clearInterval(sleepInt);
    sleepChip.hidden = !sleepTimer;
    if (sleepTimer) { tickSleep(); sleepInt = setInterval(tickSleep, 1000); pToast(sleepTimer.mode === 'end' ? 'Sleep timer: pauses at the end of this episode' : `Sleep timer: pauses in ${choice} minutes`); }
    else pToast('Sleep timer off');
    renderPanel();
  }
  function tickSleep() {
    if (!sleepTimer) return;
    sleepChip.querySelector('.sl-t').textContent = sleepLabel(sleepTimer);
    if (sleepStatus(sleepTimer).expired) fireSleep();
  }
  function fireSleep() {
    clearInterval(sleepInt); sleepTimer = null; sleepChoice = 'off'; sleepChip.hidden = true;
    wantPlaying = false; nextDismissed = true; hideNext(); clearInterval(nextTimer);
    try { video.pause(); } catch { /* ignore */ }
    pToast('Sleep timer ended. Playback paused.'); showUi(true); renderPanel();
  }

  // ---------- stats overlay (L049) ----------
  let statsOn = false, statsInt = null;
  function drawStats() {
    const q = video.getVideoPlaybackQuality?.();
    let levelBitrate = 0, bandwidth = 0;
    if (hls) { const lv = hls.levels?.[hls.currentLevel >= 0 ? hls.currentLevel : hls.loadLevel]; levelBitrate = lv?.bitrate || 0; bandwidth = Math.round(hls.bandwidthEstimate || 0); }
    let host = ''; try { host = new URL(stream.url).host; } catch { /* ignore */ }
    const rows = statsRows({ width: video.videoWidth, height: video.videoHeight, bandwidth, levelBitrate, dropped: q?.droppedVideoFrames, total: q?.totalVideoFrames, bufferAhead: bufferAhead(video.buffered, video.currentTime), speed: video.playbackRate, kind: stream.kind, host });
    clear(statsEl).append(h('dl', {}, rows.flatMap(([k, v]) => [h('dt', { text: k }), h('dd', { text: v })])));
  }
  function setStats(on) {
    statsOn = on; statsEl.hidden = !on; clearInterval(statsInt);
    if (on) { drawStats(); statsInt = setInterval(drawStats, 1000); }
    renderPanel();
  }

  function audioTracks() {
    if (hls && hls.audioTracks && hls.audioTracks.length > 1) return hls.audioTracks.map((t, i) => ({ label: `${t.name || languageName(t.lang)}${t.lang ? ' (' + t.lang + ')' : ''}`, lang: t.lang || '', enabled: hls.audioTrack === i, select: () => { hls.audioTrack = i; } }));
    const at = video.audioTracks;
    if (at && at.length > 1) return Array.from(at).map((t, i) => ({ label: `${t.label || languageName(t.language) || 'Track ' + (i + 1)}${t.language ? ' (' + t.language + ')' : ''}`, lang: t.language || '', enabled: t.enabled, select: () => { Array.from(at).forEach((x, j) => { x.enabled = j === i; }); } }));
    return [];
  }
  function refreshAudio() { audioBtn.hidden = !(audioTracks().length > 0 || (hls && hls.levels && hls.levels.length > 1)); applyAudioPref(); }
  /** Reuse the audio language chosen earlier for this series or film, when the stream has such a track (L047). */
  function applyAudioPref() {
    if (audioApplied) return;
    const tracks = audioTracks();
    if (tracks.length < 2) return;
    audioApplied = true;
    const pref = store.prefs().audioLang?.[id];
    if (!pref) return;
    const i = pickAudioIndex(tracks, pref);
    if (i >= 0 && !tracks[i].enabled) { tracks[i].select(); pToast(`Audio: ${tracks[i].label}`); }
  }

  // ---------- exit ----------
  let exited = false;
  function exit() {
    saveProgress();
    // replace (not push) so the browser Back button never lands on a finished player and restarts it
    navigate(`/title/${enc(type)}/${enc(id)}`, undefined, { replace: true });
  }
  function exitSoon() { setTimeout(() => { if (!exited) exit(); }, 400); }

  // ---------- media session / wake lock ----------
  if ('mediaSession' in navigator) {
    try {
      navigator.mediaSession.metadata = new MediaMetadata({ title: epLabel || title, artist: epLabel ? title : 'Liuying', artwork: [] });
      navigator.mediaSession.setActionHandler('play', () => video.play());
      navigator.mediaSession.setActionHandler('pause', () => video.pause());
      navigator.mediaSession.setActionHandler('seekbackward', () => skip(-step()));
      navigator.mediaSession.setActionHandler('seekforward', () => skip(step()));
      navigator.mediaSession.setActionHandler('previoustrack', prevEp() ? () => goPrev() : null);
      navigator.mediaSession.setActionHandler('nexttrack', nextEp() ? () => goNext() : null);
    } catch { /* ignore */ }
  }
  let wake = null;
  const getWake = async () => { try { wake = await navigator.wakeLock?.request('screen'); } catch { /* ignore */ } };
  getWake();
  on(document, 'visibilitychange', () => { if (document.visibilityState === 'visible') getWake(); });

  // ---------- late metadata (direct links, reloads) ----------
  if (!meta || (videoId && !episodes.length)) {
    loadMeta(type, id, { signal: ctl.signal }).then((r) => {
      if (!r.meta || ctl.signal.aborted) return;
      meta = r.meta; title = meta.name || title; poster = meta.poster || poster; episodes = normalizeVideos(meta.videos); epLabel = makeLabel();
      root.querySelector('#pl-title').textContent = title; root.querySelector('#pl-ep').textContent = epLabel;
      syncEpButtons();
      try { if ('mediaSession' in navigator) { navigator.mediaSession.setActionHandler('previoustrack', prevEp() ? () => goPrev() : null); navigator.mediaSession.setActionHandler('nexttrack', nextEp() ? () => goNext() : null); } } catch { /* ignore */ }
    }).catch(() => {});
  }
  document.getElementById('toast').hidden = true;

  // ---------- go ----------
  const vol = store.prefs().volume;
  if (vol) { video.volume = clamp(vol.v ?? 1, 0, 1); video.muted = !!vol.m; }
  volume.value = video.muted ? 0 : video.volume; paintRange(volume); paintRange(seek);
  syncPlayState();
  syncEpButtons();
  attach(0);
  root.focus();
  // test hook (read-only) for automated checks
  root.dataset.state = 'ready';

  // ---------- watch party ----------
  // A read-only hook for the desktop shell: it hands this URL to mpv/VLC when the container or codec is
  // one Chromium cannot decode. Nothing in the page depends on it.
  window.__liuyingPlayback = { url: stream?.url || '', title, mediaId: id, episodeId: videoId || null };
  cleanups.push(() => { delete window.__liuyingPlayback; });

  const party = attachParty({
    video,
    getMedia: () => ({ mediaId: id, episodeId: videoId || null, name: title }),
    toast: pToast,
  });
  cleanups.push(() => party.destroy());

  return {
    destroy() {
      exited = true;
      saveProgress();
      ctl.abort();
      clearInterval(saveInt); clearInterval(nextTimer); clearInterval(sleepInt); clearInterval(statsInt); clearTimeout(hideT); clearTimeout(tapT); clearTimeout(toastT); clearTimeout(suggestT); stall.end();
      cleanups.forEach((f) => f()); untrap();
      subs.destroy(); teardownHls();
      try { video.pause(); video.removeAttribute('src'); video.load(); } catch { /* ignore */ }
      try { if (document.fullscreenElement) document.exitFullscreen(); } catch { /* ignore */ }
      try { wake?.release(); } catch { /* ignore */ }
      document.body.classList.remove('playing');
    },
  };
}
