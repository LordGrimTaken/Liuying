import { h, icon, clear, toast as appToast, trapFocus } from '../ui/dom.js';
import * as store from '../store.js';
import { navigate, href, enc } from '../router.js';
import { applySample, resumePosition, normalizeVideos, followingEpisode, parseEpisodeId } from '../progress.js';
import { loadSubtitles } from '../addon/service.js';
import { pickAutoSubtitle, languageName, langCode, fmtDelay, COMMON_LANGS } from './subtitles.js';
import { SubtitleController } from './subs-controller.js';
import { fmtTime, clamp } from '../util.js';
import { streamsHref } from '../ui/continue.js';
import { loadMeta } from '../addon/service.js';

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
  const stream = np.stream;
  const view = document.getElementById('view');
  document.body.classList.add('playing');
  const S = () => store.settings();
  const meta = store.cachedMeta(id);
  const lib = store.library().find((i) => i.id === id);
  const prevRec = store.getProgress(id, videoId);
  const title = meta?.name || lib?.name || prevRec?.name || id;
  const poster = meta?.poster || lib?.poster || prevRec?.poster || '';
  const episodes = meta ? normalizeVideos(meta.videos) : [];
  const ep = videoId ? episodes.find((v) => v.id === videoId) : null;
  const pe = videoId ? parseEpisodeId(videoId) : null;
  const epLabel = videoId ? [`S${ep?.season ?? pe?.season ?? '?'} E${ep?.episode ?? pe?.episode ?? '?'}`, ep?.title].filter(Boolean).join(' · ') : '';
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
  const loadEl = h('div', { class: 'pl-load', role: 'status' }, h('span', { class: 'spin' }), h('span', { text: `Opening ${title}…` }));
  const errEl = h('div', { class: 'pl-err', role: 'alert', hidden: true });
  const toastEl = h('div', { class: 'pl-toast', role: 'status' });
  const nextEl = h('div', { class: 'pl-next', hidden: true });
  const panel = h('div', { class: 'pl-panel', role: 'dialog', 'aria-label': 'Player settings' });
  const resumeEl = h('div', { class: 'pl-center', hidden: true });

  const root = h('div', { class: 'player ui loading', tabindex: '-1' },
    video, cuesBox, surface,
    h('div', { class: 'pl-shade top' }), h('div', { class: 'pl-shade bot' }),
    h('div', { class: 'pl-top' },
      h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Back to details', onClick: exit }, icon('back')),
      h('div', { class: 'ttl' }, h('b', { id: 'pl-title', text: title }), h('span', { id: 'pl-ep', text: epLabel })),
      h('a', { class: 'btn sm', href: streamsHref(type, id, videoId), onClick: () => saveProgress() }, 'Streams')),
    h('div', { class: 'pl-center' }, loadEl, errEl),
    resumeEl,
    h('div', { class: 'pl-mid' },
      h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Back 10 seconds', onClick: () => skip(-step()) }, icon('back10', 30)), bigPlay,
      h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Forward 10 seconds', onClick: () => skip(step()) }, icon('fwd10', 30))),
    h('div', { class: 'pl-bot' },
      h('div', { class: 'pl-seek' }, curT, h('div', { class: 'track' }, h('div', { class: 'base' }), bufEl, seek), durT),
      h('div', { class: 'pl-btns' }, smallPlay,
        h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Back 10 seconds', onClick: () => skip(-step()) }, icon('back10')),
        h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Forward 10 seconds', onClick: () => skip(step()) }, icon('fwd10')),
        muteBtn, volume, h('span', { class: 'sp' }), spdBtn, ccBtn, audioBtn, pipBtn, fsBtn)),
    nextEl, panel, toastEl);
  clear(view).append(root);
  const step = () => S().seekStep || 10;
  // fix aria labels with current step
  root.querySelectorAll('[aria-label="Back 10 seconds"]').forEach((b) => b.setAttribute('aria-label', `Back ${step()} seconds`));
  root.querySelectorAll('[aria-label="Forward 10 seconds"]').forEach((b) => b.setAttribute('aria-label', `Forward ${step()} seconds`));

  // ---------- helpers ----------
  let toastT;
  function pToast(msg) { toastEl.textContent = msg; toastEl.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(() => toastEl.classList.remove('on'), 2200); }
  const dur = () => (Number.isFinite(video.duration) ? video.duration : 0);

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
  function attach(startAt) {
    teardownHls();
    clear(errEl); errEl.hidden = true;
    root.classList.add('loading');
    const url = stream.url;
    const isHls = stream.kind === 'hls';
    if (isHls && window.Hls && window.Hls.isSupported()) {
      hls = new window.Hls({ enableWorker: true, startPosition: startAt > 0 ? startAt : -1 });
      hls.on(window.Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal) return;
        if (data.type === window.Hls.ErrorTypes.NETWORK_ERROR) return netError(() => hls?.startLoad());
        if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR) { try { hls.recoverMediaError(); return; } catch { /* fall through */ } }
        showError('This stream could not be played. Try another stream.');
      });
      hls.on(window.Hls.Events.MANIFEST_PARSED, () => { refreshAudio(); });
      hls.on(window.Hls.Events.AUDIO_TRACKS_UPDATED, refreshAudio);
      hls.loadSource(url);
      hls.attachMedia(video);
    } else if (isHls && !video.canPlayType('application/vnd.apple.mpegurl')) {
      showError("Your browser can't play HLS streams.");
      return;
    } else {
      video.src = url;
      if (startAt > 0) video.addEventListener('loadedmetadata', () => { try { video.currentTime = startAt; } catch { /* ignore */ } }, { once: true });
    }
  }
  function teardownHls() { if (hls) { try { hls.destroy(); } catch { /* ignore */ } hls = null; } }

  function netError(retryFn) {
    if (retries >= 4) { showError('The connection to this stream was lost.'); return; }
    const wait = [1500, 3000, 6000, 12000][retries++];
    loadEl.lastChild.textContent = 'Reconnecting…';
    root.classList.add('loading');
    setTimeout(() => { if (ctl.signal.aborted) return; retryFn ? retryFn() : attach(lastPos); }, wait);
  }

  function showError(msg, { expired = false } = {}) {
    root.classList.remove('loading'); root.classList.add('ui');
    clear(errEl).append(h('h3', { text: "This stream didn't play" }), h('p', { text: msg }),
      h('div', { class: 'btns' },
        h('button', { class: 'btn pri', type: 'button', text: 'Retry', onClick: () => { retries = 0; attach(lastPos || 0); } }),
        h('a', { class: 'btn', href: streamsHref(type, id, videoId), text: 'Try another stream', onClick: () => saveProgress() }),
        h('button', { class: 'btn', type: 'button', text: 'Close', onClick: exit })));
    errEl.hidden = false;
    errEl.querySelector('button')?.focus();
  }
  on(video, 'error', () => {
    const c = video.error?.code;
    if (!video.src && !hls) return;
    if (c === 2) return netError();
    if (c === 4) return showError("Your browser can't play this stream's format or the link no longer works (it may have expired). Try another stream, or use the Android app for exotic formats.");
    if (c === 3) return showError('The video could not be decoded by your browser. Try another stream.');
    showError('Playback failed. Try another stream.');
  });

  on(video, 'loadedmetadata', () => {
    spdBtn.textContent = '1×';
    video.playbackRate = S().speed || 1; spdBtn.textContent = `${video.playbackRate}×`;
    durT.textContent = fmtTime(dur());
    refreshAudio();
    if (!startedOnce) {
      startedOnce = true;
      const pos = S().resume === 'never' ? 0 : resumePosition(prevRec, dur() * 1000, S().threshold) / 1000;
      if (pos > 1) {
        if (S().resume === 'ask') { askResume(pos); return; }
        try { video.currentTime = pos; } catch { /* ignore */ }
        resumedAt = pos; pToast(`Resumed at ${fmtTime(pos)}`);
      }
      start();
    } else if (wantPlaying) video.play().catch(() => {});
  });
  function askResume(pos) {
    resumeEl.hidden = false; resumeEl.style.setProperty('pointer-events', 'auto');
    const go = (p) => { resumeEl.hidden = true; try { video.currentTime = p; } catch { /* ignore */ } start(); };
    clear(resumeEl).append(h('div', { class: 'pl-resume' }, h('span', { text: `Resume at ${fmtTime(pos)}?` }), h('button', { class: 'btn pri sm', type: 'button', text: 'Resume', onClick: () => go(pos) }), h('button', { class: 'btn sm', type: 'button', text: 'Start over', onClick: () => go(0) })));
    resumeEl.querySelector('button').focus();
  }
  function start() {
    video.play().catch(() => { wantPlaying = false; syncPlayState(); showUi(true); });
  }
  on(video, 'canplay', () => { root.classList.remove('loading'); retries = 0; });
  on(video, 'playing', () => { root.classList.remove('loading'); errEl.hidden = true; syncPlayState(); startSaveTimer(); autoSubtitleOnce(); });
  on(video, 'waiting', () => { loadEl.lastChild.textContent = 'Buffering…'; root.classList.add('loading'); });
  on(video, 'seeking', () => { if (video.readyState < 3) root.classList.add('loading'); });
  on(video, 'seeked', () => { saveProgress(); });
  on(video, 'pause', () => { syncPlayState(); saveProgress(); });
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
  on(video, 'ended', () => { saveProgress(true); const nx = nextEp(); if (nx && S().autoNext) goNext(); else if (nx) showNext(true); else exitSoon(); });
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
      case 'p': case 'P': if (pipBtn) togglePip(); break;
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
  function goNext() {
    const nx = nextEp(); if (!nx) return;
    clearInterval(nextTimer);
    saveProgress(true);
    navigate(`/streams/${enc(type)}/${enc(id)}`, { v: nx.id, auto: '1' });
  }

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
  const tabsDef = [['subs', 'Subtitles'], ['style', 'Style'], ['timing', 'Timing'], ['audio', 'Audio'], ['play', 'Playback']];
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
        const { decodeSubtitleBytes } = await import('./subtitles.js');
        const entry = { key: 'file:' + f.name, url: '', lang: 'und', langName: 'File', label: f.name, source: 'This device', origin: 'file', forced: false, sdh: false, n: 1 };
        manualSub = true;
        const r = await subs.select(entry, { fileText: decodeSubtitleBytes(buf) });
        pToast(r.ok ? `Loaded ${f.name}` : r.error);
      } });
      body.append(h('h4', { text: 'Add' }), h('button', { class: 'opt', type: 'button', onClick: () => file.click() }, h('span', { class: 'ck' }, icon('plus', 16)), 'Choose a file from this device (SRT or VTT)'), file);
    } else if (tab === 'style') {
      const s = S();
      const rng = (label, key, min, max, stp, fmt) => {
        const r = h('input', { class: 'rng', type: 'range', min, max, step: stp, value: s[key], 'aria-label': label, onInput: () => { store.setSettings({ [key]: Number(r.value) }); paintRange(r); o.textContent = fmt(Number(r.value)); applySubStyle(); } });
        const o = h('output', { text: fmt(s[key]) }); paintRange(r);
        return h('div', { class: 'ctl' }, h('span', { text: label }), r, o);
      };
      body.append(h('div', { class: 'pl-prev' }, h('div', { class: `cue ${s.subBg}`, style: { '--sub-size': '1' } }, 'The quick brown fox')));
      body.querySelector('.pl-prev .cue').style.setProperty('font-size', `${(s.subSize || 100) / 100 * 22}px`);
      body.querySelector('.pl-prev .cue').style.setProperty('color', s.subColor);
      body.querySelector('.pl-prev .cue').style.setProperty('font-weight', s.subBold ? '700' : '500');
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
      else { body.append(h('h4', { text: 'Audio track' })); tracks.forEach((t) => body.append(opt(t.label, t.enabled, () => { t.select(); renderPanel(); }))); }
      if (hls && hls.levels && hls.levels.length > 1) {
        body.append(h('h4', { text: 'Quality' }), opt('Auto', hls.currentLevel === -1 || hls.autoLevelEnabled, () => { hls.currentLevel = -1; renderPanel(); }));
        hls.levels.forEach((lv, i) => body.append(opt(`${lv.height ? lv.height + 'p' : Math.round(lv.bitrate / 1000) + ' kbps'}`, !hls.autoLevelEnabled && hls.currentLevel === i, () => { hls.currentLevel = i; renderPanel(); })));
      }
    } else {
      body.append(h('h4', { text: 'Speed' }), ...SPEEDS.map((v) => opt(`${v}×`, video.playbackRate === v, () => { video.playbackRate = v; renderPanel(); })));
      body.append(h('h4', { text: 'Picture' }), opt('Fit', !video.classList.contains('fill'), () => { video.classList.remove('fill'); renderPanel(); }), opt('Fill (crop)', video.classList.contains('fill'), () => { video.classList.add('fill'); renderPanel(); }));
      body.append(h('h4', { text: 'Shortcuts' }), h('p', { class: 'mute', style: { padding: '4px 8px', 'font-size': '13px', 'line-height': '1.7' }, text: 'Space play/pause · ← → seek · ↑ ↓ volume · F fullscreen · M mute · C subtitles · P picture in picture · Esc close' }));
    }
    clear(panel).append(
      h('div', { class: 'tabs', role: 'tablist' }, tabsDef.map(([k, l]) => h('button', { type: 'button', role: 'tab', 'aria-selected': String(tab === k), text: l, onClick: () => { tab = k; renderPanel(); panel.querySelector('[aria-selected="true"]').focus(); } })),
        h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Close panel', onClick: closePanel }, icon('close', 18))), body);
    body.scrollTop = keep;
  }
  function audioTracks() {
    if (hls && hls.audioTracks && hls.audioTracks.length > 1) return hls.audioTracks.map((t, i) => ({ label: `${t.name || languageName(t.lang)}${t.lang ? ' (' + t.lang + ')' : ''}`, enabled: hls.audioTrack === i, select: () => { hls.audioTrack = i; } }));
    const at = video.audioTracks;
    if (at && at.length > 1) return Array.from(at).map((t, i) => ({ label: `${t.label || languageName(t.language) || 'Track ' + (i + 1)}${t.language ? ' (' + t.language + ')' : ''}`, enabled: t.enabled, select: () => { Array.from(at).forEach((x, j) => { x.enabled = j === i; }); } }));
    return [];
  }
  function refreshAudio() { audioBtn.hidden = !(audioTracks().length > 0 || (hls && hls.levels && hls.levels.length > 1)); }

  // ---------- exit ----------
  let exited = false;
  function exit() {
    saveProgress();
    location.hash = href(`/title/${enc(type)}/${enc(id)}`);
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
    }).catch(() => {});
  }
  document.getElementById('toast').hidden = true;

  // ---------- go ----------
  const vol = store.prefs().volume;
  if (vol) { video.volume = clamp(vol.v ?? 1, 0, 1); video.muted = !!vol.m; }
  volume.value = video.muted ? 0 : video.volume; paintRange(volume); paintRange(seek);
  syncPlayState();
  attach(0);
  root.focus();
  // test hook (read-only) for automated checks
  root.dataset.state = 'ready';

  return {
    destroy() {
      exited = true;
      saveProgress();
      ctl.abort();
      clearInterval(saveInt); clearInterval(nextTimer); clearTimeout(hideT); clearTimeout(tapT); clearTimeout(toastT);
      cleanups.forEach((f) => f()); untrap();
      subs.destroy(); teardownHls();
      try { video.pause(); video.removeAttribute('src'); video.load(); } catch { /* ignore */ }
      try { if (document.fullscreenElement) document.exitFullscreen(); } catch { /* ignore */ }
      try { wake?.release(); } catch { /* ignore */ }
      document.body.classList.remove('playing');
    },
  };
}
