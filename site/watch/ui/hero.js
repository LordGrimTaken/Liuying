// Full-bleed featured hero shared by Today, Movies and Series.
import { h, icon, clear } from './dom.js';
import * as store from '../store.js';
import { artImg, titleHref, toggleLibrary } from './components.js';
import { streamsHref } from './continue.js';
import { href, enc } from '../router.js';
import { yearOf, typeLabel } from '../util.js';

/**
 * Render a rotating hero into `box`. items: featured catalog items; srcId: source id for deep links.
 * Returns { destroy } which stops the rotation timer.
 */
export function renderHero(box, items, { srcId } = {}) {
  clear(box);
  let i = 0;
  let paused = false;
  let timer = null;
  const bgs = items.map((it, k) => {
    const useBg = !!it.background;
    const im = artImg(it.background || it.poster);
    if (im) { im.className = 'bg' + (useBg ? '' : ' fromposter') + (k === 0 ? ' on' : ''); im.alt = ''; im.loading = 'eager'; }
    return im;
  });
  const copy = h('div', { class: 'copy', 'aria-live': 'off' });
  const pauseBtn = h('button', { class: 'ibtn hbtn', type: 'button', 'aria-label': 'Pause rotation', onClick: () => { paused = !paused; syncPause(); } });
  const dots = h('div', { class: 'dots' }, items.map((_, k) => h('button', { type: 'button', 'aria-label': `Show featured title ${k + 1} of ${items.length}`, onClick: () => show(k) })));
  const rate = h('span', { class: 'hrate' });
  const foot = h('div', { class: 'hfoot' }, h('div', { class: 'hctl' }, items.length > 1 ? pauseBtn : null, items.length > 1 ? dots : null), rate);
  const hero = h('section', { class: 'hero', 'aria-roledescription': 'carousel', 'aria-label': 'Featured titles' }, bgs, copy, foot);
  box.append(hero);

  function syncPause() {
    pauseBtn.replaceChildren(icon(paused ? 'play' : 'pause', 16));
    pauseBtn.setAttribute('aria-label', paused ? 'Resume rotation' : 'Pause rotation');
  }

  function listBtn(it) {
    const inLib = store.inLibrary(it.id);
    const b = h('button', { class: 'hb', type: 'button', 'aria-pressed': String(inLib), 'aria-label': inLib ? `Remove ${it.name} from shelf` : `Add ${it.name} to shelf`, title: inLib ? 'On your shelf' : 'Add to shelf' }, icon(inLib ? 'check' : 'plus', 20), h('span', { text: inLib ? 'Listed' : 'List' }));
    b.addEventListener('click', () => {
      toggleLibrary(it);
      const on = store.inLibrary(it.id);
      b.setAttribute('aria-pressed', String(on));
      b.setAttribute('aria-label', on ? `Remove ${it.name} from shelf` : `Add ${it.name} to shelf`);
      b.title = on ? 'On your shelf' : 'Add to shelf';
      b.replaceChildren(icon(on ? 'check' : 'plus', 20), h('span', { text: on ? 'Listed' : 'List' }));
    });
    return b;
  }

  function show(k, animate = true) {
    const changed = k !== i || !copy.firstChild;
    i = k;
    const it = items[k];
    bgs.forEach((b, j) => b && b.classList.toggle('on', j === k));
    [...dots.children].forEach((d, j) => d.setAttribute('aria-current', j === k ? 'true' : 'false'));
    const type = it.type || 'movie';
    const yr = yearOf(it);
    const genres = (Array.isArray(it.genres) ? it.genres : Array.isArray(it.genre) ? it.genre : []).slice(0, 2);
    copy.classList.toggle('swap', animate && changed);
    clear(copy).append(
      h('p', { class: 'eyebrow', text: items.length > 1 ? `Featured · ${k + 1} of ${items.length}` : 'Featured' }),
      h('h2', { text: it.name || it.id }),
      h('div', { class: 'chipline' },
        yr ? h('span', { class: 'hyear', text: yr }) : null,
        h('span', { class: 'hchip', text: typeLabel(type) }),
        ...genres.map((g) => h('span', { class: 'hgen', text: g }))),
      it.description ? h('p', { class: 'desc', text: it.description }) : null,
      h('div', { class: 'acts' },
        h('a', { class: 'hb pri', href: type === 'series' ? href(`/title/${enc(type)}/${enc(it.id)}`, { src: srcId, play: 1 }) : streamsHref(type, it.id) }, icon('play', 20), h('span', { text: 'Play' })),
        listBtn(it),
        h('a', { class: 'hb', href: titleHref(it, srcId) }, icon('info', 20), h('span', { text: 'Details' }))));
    clear(rate);
    rate.hidden = !it.imdbRating;
    if (it.imdbRating) rate.append(icon('star', 13), String(it.imdbRating));
  }

  show(0, false);
  syncPause();
  const reduce = store.settings().reduceMotion || matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (items.length > 1 && !reduce) {
    let hover = false;
    timer = setInterval(() => { if (!paused && !hover) show((i + 1) % items.length); }, 8000);
    hero.addEventListener('mouseenter', () => { hover = true; });
    hero.addEventListener('mouseleave', () => { hover = false; });
    hero.addEventListener('focusin', () => { paused = true; syncPause(); });
  } else { paused = true; syncPause(); }
  if (items.length > 1) {
    // swipe between featured titles on touch screens
    let x0 = 0, y0 = 0;
    hero.addEventListener('touchstart', (e) => { x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; }, { passive: true });
    hero.addEventListener('touchend', (e) => {
      const t = e.changedTouches[0];
      const dx = t.clientX - x0, dy = t.clientY - y0;
      if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5) show((i + (dx < 0 ? 1 : items.length - 1)) % items.length);
    }, { passive: true });
  }
  return { destroy() { clearInterval(timer); } };
}
