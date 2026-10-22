// DOM helpers. Always use textContent for remote data; never innerHTML except static SVG icons.
export function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'data') for (const [dk, dv] of Object.entries(v)) el.dataset[dk] = dv;
      else if (k === 'style') for (const [sk, sv] of Object.entries(v)) el.style.setProperty(sk, sv);
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, String(v));
    }
  }
  append(el, kids);
  return el;
}
export function append(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k === null || k === undefined || k === false) continue;
    el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
  return el;
}
export const clear = (el) => { while (el.firstChild) el.removeChild(el.firstChild); return el; };
export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const ICONS = {
  play: '<path d="M7 4.5v15l13-7.5z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" fill="currentColor" stroke="none"/>',
  today: '<path d="M4 11.5 12 4l8 7.5V20h-5.5v-5.5h-5V20H4z"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>',
  shelf: '<path d="M5 4h4v16H5zM10.5 4h4v16h-4zM16 6l3.7-1 3 15-3.7 1z" transform="translate(-1 0)"/>',
  sources: '<path d="M4 7c3-5 6 5 9 0M4 13c3-5 6 5 9 0M4 19c3-5 6 5 9 0M16 6h5M16 12h5M16 18h5"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"/>',
  back: '<path d="M15 5 8 12l7 7"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  bookmark: '<path d="M7 4h10v16l-5-3.5L7 20z"/>',
  bookmarkOn: '<path d="M7 4h10v16l-5-3.5L7 20z" fill="currentColor"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>',
  more: '<circle cx="5.5" cy="12" r="1.4" fill="currentColor"/><circle cx="12" cy="12" r="1.4" fill="currentColor"/><circle cx="18.5" cy="12" r="1.4" fill="currentColor"/>',
  back10: '<polyline points="1 4 1 10 7 10"/><path d="M3.5 15a9 9 0 1 0 2.1-9.4L1 10"/><text x="12.2" y="15.2" font-size="7.5" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none" font-family="system-ui">10</text>',
  fwd10: '<polyline points="23 4 23 10 17 10"/><path d="M20.5 15a9 9 0 1 1-2.1-9.4L23 10"/><text x="11.8" y="15.2" font-size="7.5" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none" font-family="system-ui">10</text>',
  volume: '<path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4z" fill="currentColor"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>',
  mute: '<path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4z" fill="currentColor"/><path d="m16 9.5 5 5M21 9.5l-5 5"/>',
  full: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  exitFull: '<path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/>',
  pip: '<rect x="3" y="5" width="18" height="14" rx="1.5"/><rect x="12" y="11" width="7" height="5.5" fill="currentColor"/>',
  cc: '<rect x="3" y="5.5" width="18" height="13" rx="2"/><path d="M10 10.2a2.4 2.4 0 1 0 0 3.6M17 10.2a2.4 2.4 0 1 0 0 3.6"/>',
  audio: '<path d="M4 14v-2a8 8 0 0 1 16 0v2"/><rect x="3" y="14" width="4" height="6" rx="1.5" fill="currentColor"/><rect x="17" y="14" width="4" height="6" rx="1.5" fill="currentColor"/>',
  speed: '<path d="M5 18a8 8 0 1 1 14 0M12 13l4-4"/>',
  external: '<path d="M14 4h6v6M20 4l-9 9M18 14v5H5V6h5"/>',
  warn: '<path d="M12 4 2.8 19.5h18.4zM12 10v4.5M12 17v.4"/>',
  up: '<path d="m6 15 6-6 6 6"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  refresh: '<path d="M19 8a7.5 7.5 0 1 0 1.5 5M19.5 3v5h-5"/>',
  trash: '<path d="M5 7h14M9 7V4.5h6V7M7 7l1 13h8l1-13"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="1.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  cloud: '<path d="M7 18a4 4 0 0 1-.6-7.95A5.5 5.5 0 0 1 17 9a4.5 4.5 0 0 1 .5 9z"/>',
  star: '<path d="m12 4 2.4 5 5.4.7-4 3.7 1 5.4-4.8-2.7-4.8 2.7 1-5.4-4-3.7 5.4-.7z" fill="currentColor" stroke="none"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"/>',
};
export function icon(name, size = 22) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('width', size); s.setAttribute('height', size);
  s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor');
  s.setAttribute('stroke-width', '1.7'); s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round');
  s.setAttribute('aria-hidden', 'true'); s.setAttribute('focusable', 'false');
  s.classList.add('ico');
  s.innerHTML = ICONS[name] || '';
  return s;
}

/** Button with icon + visually hidden label. */
export function iconBtn(name, label, onClick, cls = '') {
  return h('button', { class: `ibtn ${cls}`, type: 'button', 'aria-label': label, title: label, onClick }, icon(name));
}

// ---------- toast ----------
let toastTimer;
export function toast(msg, { action, onAction, ms = 4000 } = {}) {
  const box = document.getElementById('toast');
  if (!box) return;
  clear(box);
  box.append(h('span', { text: msg }));
  if (action) box.append(h('button', { class: 'linkbtn', type: 'button', text: action, onClick: () => { clearTimeout(toastTimer); box.hidden = true; onAction?.(); } }));
  box.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.hidden = true; }, ms);
}

// ---------- modal dialog (confirm / custom) ----------
export function modal({ title, body, actions }) {
  return new Promise((resolve) => {
    const prev = document.activeElement;
    const root = document.getElementById('modal');
    clear(root);
    const close = (v) => { root.hidden = true; clear(root); document.removeEventListener('keydown', onKey, true); prev?.focus?.(); resolve(v); };
    const onKey = (e) => { if (e.key === 'Escape') { e.stopPropagation(); close(null); } };
    document.addEventListener('keydown', onKey, true);
    const btns = actions.map((a) => h('button', { class: `btn ${a.primary ? 'pri' : 'sec'}${a.danger ? ' danger' : ''}`, type: 'button', text: a.label, onClick: () => close(a.value) }));
    const dlg = h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'dlg-t' },
      h('h2', { id: 'dlg-t', text: title }), h('div', { class: 'dlg-body' }, typeof body === 'function' ? body(close) : body), h('div', { class: 'dlg-actions' }, btns));
    root.append(h('div', { class: 'scrim', onClick: () => close(null) }), dlg);
    root.hidden = false;
    (btns.find((_, i) => actions[i].primary) || btns[0])?.focus();
  });
}
export const confirmDialog = (title, text, okLabel = 'Confirm', danger = false) =>
  modal({ title, body: h('p', { text }), actions: [{ label: 'Cancel', value: false }, { label: okLabel, value: true, primary: true, danger }] });

export function trapFocus(container) {
  const handler = (e) => {
    if (e.key !== 'Tab') return;
    const f = $$('button:not([disabled]),a[href],input:not([disabled]),select,[tabindex]:not([tabindex="-1"])', container).filter((x) => x.offsetParent !== null);
    if (!f.length) return;
    const first = f[0], last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  };
  container.addEventListener('keydown', handler);
  return () => container.removeEventListener('keydown', handler);
}
