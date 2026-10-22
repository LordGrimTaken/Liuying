// Subtitle controller: fetches, converts to WebVTT blob tracks, renders active cues in our own overlay
// so size / colour / background / position / delay are fully user controlled.
import { srtToVtt, decodeSubtitleBytes, normalizeSubtitles, sortSubtitles } from './subtitles.js';

export class SubtitleController {
  constructor(video, overlay, { onChange } = {}) {
    this.video = video; this.overlay = overlay; this.onChange = onChange || (() => {});
    this.entries = []; this.groups = []; this.current = null; this.status = new Map(); // key -> 'loading' | 'error'
    this.delay = 0; this.trackEl = null; this.blobUrl = null; this.base = []; this.style = { bg: 'shadow' };
    this._onCue = () => this.render();
    this._seq = 0;
  }

  addGroup(group) {
    this.groups = this.groups.filter((g) => !(g.origin === group.origin && g.source === group.source));
    this.groups.push(group);
    this.entries = normalizeSubtitles(this.groups);
    this.onChange();
  }

  sorted(preferred) { return sortSubtitles(this.entries, preferred); }

  clearTrack() {
    if (this.trackEl) {
      try { this.trackEl.track.removeEventListener('cuechange', this._onCue); this.trackEl.track.mode = 'disabled'; } catch { /* ignore */ }
      this.trackEl.remove(); this.trackEl = null;
    }
    if (this.blobUrl) { URL.revokeObjectURL(this.blobUrl); this.blobUrl = null; }
    this.base = [];
    this.render();
  }

  /** Select an entry (or null for Off). Resolves {ok, error}. Keeps previous selection on failure. */
  async select(entry, { fileText } = {}) {
    if (!entry) { this._seq++; this.clearTrack(); this.current = null; this.onChange(); return { ok: true }; }
    const seq = ++this._seq;
    this.status.set(entry.key, 'loading'); this.onChange();
    try {
      let text = fileText;
      if (text === undefined) {
        let res;
        try { res = await fetch(entry.url, { credentials: 'omit' }); } catch { throw new Error('This subtitle source does not allow web access.'); }
        if (!res.ok) throw new Error(`The subtitle server answered HTTP ${res.status}.`);
        const buf = await res.arrayBuffer();
        if (buf.byteLength > 5 * 1024 * 1024) throw new Error('This subtitle file is too large.');
        text = decodeSubtitleBytes(buf);
      }
      const { vtt, cues } = srtToVtt(text);
      if (!cues) throw new Error("This subtitle couldn't be read.");
      if (seq !== this._seq) return { ok: false, error: 'superseded' };
      const url = URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }));
      const el = document.createElement('track');
      el.kind = 'subtitles'; el.label = entry.label; el.srclang = (entry.lang || 'und').slice(0, 8); el.src = url;
      this.video.append(el);
      el.track.mode = 'hidden';
      await new Promise((resolve) => { el.addEventListener('load', resolve, { once: true }); el.addEventListener('error', resolve, { once: true }); setTimeout(resolve, 5000); });
      if (seq !== this._seq) { el.remove(); URL.revokeObjectURL(url); return { ok: false, error: 'superseded' }; }
      if (!el.track.cues || !el.track.cues.length) { el.remove(); URL.revokeObjectURL(url); throw new Error("This subtitle couldn't be read."); }
      this.clearTrack();
      this.trackEl = el; this.blobUrl = url;
      el.track.mode = 'hidden';
      el.track.addEventListener('cuechange', this._onCue);
      this.base = Array.from(el.track.cues).map((c) => ({ c, s: c.startTime, e: c.endTime }));
      this.current = entry;
      this.status.delete(entry.key);
      this.applyDelay();
      this.onChange();
      return { ok: true };
    } catch (e) {
      if (seq === this._seq) { this.status.set(entry.key, 'error'); this.onChange(); }
      return { ok: false, error: e.message };
    }
  }

  setDelay(sec) {
    this.delay = Math.round(sec * 10) / 10;
    this.applyDelay();
    this.onChange();
  }
  applyDelay() {
    for (const b of this.base) { b.c.startTime = Math.max(0, b.s + this.delay); b.c.endTime = Math.max(0.01, b.e + this.delay); }
    // activeCues is recomputed on the next time update; render now as well
    setTimeout(() => this.render(), 60);
  }

  setStyle(style) { this.style = { ...this.style, ...style }; this.render(); }

  render() {
    const box = this.overlay;
    while (box.firstChild) box.removeChild(box.firstChild);
    const tr = this.trackEl?.track;
    if (!tr || !tr.activeCues) return;
    const cues = Array.from(tr.activeCues);
    if (!cues.length) return;
    const wrap = document.createElement('div');
    wrap.className = `cue ${this.style.bg || 'shadow'}`;
    wrap.dataset.testid = 'cue';
    cues.forEach((c, i) => {
      if (i) wrap.append(document.createElement('br'));
      wrap.append(c.getCueAsHTML());
    });
    box.append(wrap);
  }

  destroy() { this._seq++; this.clearTrack(); }
}
