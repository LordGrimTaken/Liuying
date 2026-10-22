import { h, icon, clear, modal } from './dom.js';
import * as store from '../store.js';
import { href, enc } from '../router.js';
import { imgUrl, yearOf, typeLabel } from '../util.js';
import { getProgress } from '../store.js';
import { ratioOf, setWatched } from '../progress.js';
import { toast } from './dom.js';

export function titleHref(item, srcId) {
  return href(`/title/${enc(item.type || 'movie')}/${enc(item.id)}`, srcId ? { src: srcId } : undefined);
}

export function artImg(url, alt = '') {
  const u = imgUrl(url);
  if (!u) return null;
  const img = h('img', { src: u, alt, loading: 'lazy', decoding: 'async', referrerpolicy: 'no-referrer' });
  img.addEventListener('error', () => img.remove(), { once: true });
  return img;
}

/** Portrait poster card. */
export function posterCard(item, { srcId, showTitle = true, onMenu } = {}) {
  const s = store.settings();
  const rec = getProgress(item.id, null);
  const r = ratioOf(rec);
  const inLib = store.inLibrary(item.id);
  const name = item.name || item.id;
  const marks = [];
  if (s.showBadges) {
    if (rec?.watched) marks.push(h('span', { class: 'w', title: 'Watched' }, icon('check', 14)));
    if (inLib) marks.push(h('span', { title: 'In your shelf' }, icon('bookmarkOn', 13)));
  }
  const card = h('a', { class: 'pcard', href: titleHref(item, srcId), 'aria-label': `${name}${yearOf(item) ? ', ' + yearOf(item) : ''}` },
    h('div', { class: 'art' },
      h('div', { class: 'ph', 'aria-hidden': 'true', text: name }),
      artImg(item.poster),
      marks.length ? h('div', { class: 'mk' }, marks) : null,
      r > 0.03 && !rec?.watched ? h('div', { class: 'pbar', style: { '--p': `${Math.round(r * 100)}%` } }, h('i')) : null),
    s.titlesUnder ? h('span', { class: 'ttl', text: name }) : null,
    s.titlesUnder && yearOf(item) ? h('span', { class: 'sub', text: yearOf(item) }) : null);
  const open = (e) => { e.preventDefault(); e.stopPropagation(); itemMenu(item); };
  card.append(h('button', { class: 'more', type: 'button', 'aria-label': `Actions for ${name}`, onClick: open }, icon('more', 18)));
  if (item.id) {
    const q = h('button', { class: 'quick', type: 'button', 'aria-pressed': String(inLib), 'aria-label': `${inLib ? 'Remove' : 'Add'} ${name} ${inLib ? 'from' : 'to'} shelf`, title: inLib ? 'On your shelf' : 'Add to shelf' }, icon(inLib ? 'check' : 'plus', 18));
    q.addEventListener('click', (e) => {
      e.preventDefault(); e.stopPropagation();
      toggleLibrary(item);
      const on = store.inLibrary(item.id);
      q.setAttribute('aria-pressed', String(on)); q.title = on ? 'On your shelf' : 'Add to shelf';
      q.setAttribute('aria-label', `${on ? 'Remove' : 'Add'} ${name} ${on ? 'from' : 'to'} shelf`);
      q.replaceChildren(icon(on ? 'check' : 'plus', 18));
    });
    card.querySelector('.art').append(q);
  }
  card.addEventListener('contextmenu', open);
  let t;
  card.addEventListener('touchstart', () => { t = setTimeout(() => itemMenu(item), 550); }, { passive: true });
  for (const ev of ['touchend', 'touchmove', 'touchcancel']) card.addEventListener(ev, () => clearTimeout(t), { passive: true });
  return card;
}

/** Action sheet for a title: open, library, watched. */
export function itemMenu(item) {
  const inLib = () => store.inLibrary(item.id);
  const rec = getProgress(item.id, null);
  const entries = [
    { label: 'Open details', icon: 'eye', run: () => { location.hash = titleHref(item); } },
    { label: inLib() ? 'Remove from shelf' : 'Add to shelf', icon: inLib() ? 'bookmark' : 'bookmarkOn', run: () => toggleLibrary(item) },
  ];
  if (item.type !== 'series') {
    entries.push({ label: rec?.watched ? 'Mark unwatched' : 'Mark watched', icon: 'check', run: () => {
      store.putProgress(setWatched(rec, { mediaId: item.id, episodeId: null, watched: !rec?.watched, now: Date.now(), meta: { type: item.type, name: item.name, poster: item.poster } }));
    } });
  }
  return sheet(item.name || item.id, entries);
}

export function toggleLibrary(item) {
  if (store.inLibrary(item.id)) {
    const snap = store.library().find((i) => i.id === item.id);
    store.removeFromLibrary(item.id);
    toast('Removed from your shelf', { action: 'Undo', onAction: () => store.addToLibrary(snap || item) });
  } else {
    store.addToLibrary(item);
    toast('Added to your shelf', { action: 'Undo', onAction: () => store.removeFromLibrary(item.id) });
  }
}

export function sheet(title, entries) {
  return modal({
    title,
    body: (close) => h('div', { class: 'sheet' }, entries.map((e) => h('button', { type: 'button', class: e.danger ? 'danger' : '', onClick: () => { close(null); setTimeout(() => e.run(), 0); } }, icon(e.icon || 'more', 20), e.label))),
    actions: [{ label: 'Close', value: null }],
  });
}

export function posterSkeleton() { return h('div', { class: 'sk poster', 'aria-hidden': 'true' }); }

/** Horizontal row: heading, short subtitle, optional "See all", paging arrows (desktop) and a snap scroller. */
export function rowSection(title, { allHref, sub } = {}) {
  const scroller = h('div', { class: 'scroller', role: 'list' });
  const page = (dir) => scroller.scrollBy({ left: dir * Math.max(200, scroller.clientWidth * 0.85), behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  const head = h('div', { class: 'row-h' },
    h('div', { class: 'ht' }, h('h2', { text: title }), sub ? h('p', { class: 'rsub', text: sub }) : null),
    allHref ? h('a', { class: 'all', href: allHref, 'aria-label': `See all: ${title}`, text: 'See all' }) : null,
    h('div', { class: 'pager' },
      h('button', { class: 'ibtn ring', type: 'button', 'aria-label': `Scroll ${title} left`, onClick: () => page(-1) }, icon('back', 18)),
      h('button', { class: 'ibtn ring', type: 'button', 'aria-label': `Scroll ${title} right`, onClick: () => page(1) }, icon('right', 18))));
  const el = h('section', { class: 'row', 'aria-label': title }, head, scroller);
  return { el, scroller, head };
}

export function showSkeletons(scroller, n = 7) { clear(scroller); for (let i = 0; i < n; i++) scroller.append(posterSkeleton()); }

export function errorCard(title, message, onRetry, extra) {
  return h('div', { class: 'state err', role: 'alert' },
    icon('warn', 24),
    h('div', { class: 'grow' }, h('strong', { text: title }), h('span', { class: 'mute', text: message })),
    onRetry ? h('button', { class: 'btn sm', type: 'button', text: 'Retry', onClick: onRetry }) : null, extra || null);
}

export function emptyState(title, text, ...actions) {
  return h('div', { class: 'state' }, h('h3', { text: title }), h('p', { text }), h('div', {}, actions));
}

export function pageHeader(title, ...actions) {
  return h('div', { class: 'page-h' }, h('h1', { text: title }), actions);
}

export function badges(list) {
  return h('div', { class: 'badges' }, list.map((b) => h('span', { class: `badge ${b.kind}`, text: b.label })));
}

export { typeLabel };
