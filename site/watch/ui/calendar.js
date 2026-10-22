// Calendar: upcoming and recently aired episodes for series on the shelf, from meta "videos" release dates.
import { h, icon, clear } from './dom.js';
import * as store from '../store.js';
import { loadMeta } from '../addon/service.js';
import { normalizeVideos } from '../progress.js';
import { buildAgenda, airStatus, filterAgenda } from '../ux.js';
import { artImg, emptyState, titleHref } from './components.js';
import { streamsHref } from './continue.js';

const MAX_SERIES = 30;

export function calendar() {
  const view = document.getElementById('view');
  const ctl = new AbortController();
  const page = h('div', { class: 'page cal' });
  clear(view).append(page);
  const status = h('p', { class: 'mute cal-status', role: 'status' });
  const out = h('div', { class: 'cal-grid' });
  const hideBtn = h('button', { class: 'chip', type: 'button', 'aria-pressed': String(!!store.settings().calHideWatched), title: 'Leave out episodes you have already watched', onClick: () => { const on = !store.settings().calHideWatched; store.setSettings({ calHideWatched: on }); hideBtn.setAttribute('aria-pressed', String(on)); draw(false); } }, 'Hide watched');
  page.append(h('div', { class: 'page-h' }, h('h1', { text: 'Calendar' }), h('div', { class: 'chips', role: 'group', 'aria-label': 'Display' }, hideBtn)), h('p', { class: 'lede', text: 'New and recent episodes for the series on your shelf.' }), status, out);

  const shelfSeries = () => store.library().filter((i) => i.type === 'series').slice(0, MAX_SERIES);

  function itemCard(it) {
    const e = it.ep;
    const label = `S${e.season} E${Number.isFinite(e.episode) ? e.episode : '?'}`;
    const rec = store.getProgress(it.mediaId, e.id);
    const released = it.diff <= 0;
    const href = released ? streamsHref('series', it.mediaId, e.id) : titleHref({ type: 'series', id: it.mediaId });
    return h('a', { class: `cal-item${released ? '' : ' soon'}`, href, 'aria-label': `${it.name}, ${label}${e.title ? ', ' + e.title : ''}. ${airStatus(it.diff)}${rec?.watched ? ', watched' : ''}` },
      h('div', { class: 'cpos' }, h('div', { class: 'ph', 'aria-hidden': 'true', text: it.name }), artImg(it.poster, '')),
      h('div', { class: 'cinfo' },
        h('b', { class: 'cname', text: it.name }),
        h('span', { class: 'cep', text: `${label}${e.title ? ' · ' + e.title : ''}` })),
      rec?.watched ? h('span', { class: 'badge ok', title: 'Watched' }, 'Watched') : h('span', { class: `badge${it.diff === 0 ? ' res' : ''}`, text: airStatus(it.diff) }));
  }

  function dayBlock(d) {
    const dt = d.date;
    return h('section', { class: `cal-day${d.diff === 0 ? ' today' : ''}`, 'aria-label': d.label },
      h('div', { class: 'cdate' }, h('span', { class: 'cd', text: String(dt.getDate()) }), h('span', { class: 'cm', text: dt.toLocaleDateString('en-US', { month: 'short' }) })),
      h('div', { class: 'cbody' }, h('h3', { text: d.label + (d.diff > 1 || d.diff < -1 ? '' : ` · ${dt.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })}`) }), h('div', { class: 'clist' }, d.items.map(itemCard))));
  }

  function draw(loading = false) {
    if (ctl.signal.aborted) return;
    clear(out);
    const series = shelfSeries();
    if (!series.length) {
      status.textContent = '';
      out.append(emptyState('No series on your shelf', 'Save a series and its upcoming episodes will be listed here.', h('a', { class: 'btn pri', href: '#/', text: 'Browse Today' })));
      return;
    }
    const a = filterAgenda(buildAgenda(series, store.cachedMeta, normalizeVideos, new Date()), { hideWatched: !!store.settings().calHideWatched, isWatched: (id, ep) => !!store.getProgress(id, ep)?.watched });
    status.textContent = loading ? `Checking ${a.series} series for new episodes…` : `${a.tracked} of ${a.series} series reported release dates.`;
    const up = h('div', { class: 'cal-col' }, h('h2', { class: 'sec', text: 'Upcoming' }));
    if (a.upcoming.length) a.upcoming.forEach((d) => up.append(dayBlock(d)));
    else up.append(h('div', { class: 'state compact', text: loading ? 'Loading release dates…' : store.settings().calHideWatched ? 'Nothing new to show. Turn off Hide watched to see everything.' : 'Nothing scheduled. Your sources have no future release dates for these series.' }));
    const rc = h('div', { class: 'cal-col' }, h('h2', { class: 'sec', text: 'Recently aired' }));
    if (a.recent.length) a.recent.forEach((d) => rc.append(dayBlock(d)));
    else rc.append(h('div', { class: 'state compact', text: 'No episodes in the last 7 days.' }));
    out.append(up, rc);
  }

  async function refresh() {
    const series = shelfSeries();
    draw(true);
    let next = 0;
    const worker = async () => {
      while (next < series.length && !ctl.signal.aborted) {
        const s = series[next++];
        try { await loadMeta('series', s.id, { signal: ctl.signal }); } catch { /* ignore */ }
        draw(true);
      }
    };
    await Promise.all([worker(), worker(), worker(), worker()]);
    draw(false);
  }
  const unsub = store.subscribe((k) => { if (k === 'library' || k === 'progress') draw(false); });
  refresh();
  return { destroy() { ctl.abort(); unsub(); } };
}
