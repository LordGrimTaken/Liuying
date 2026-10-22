import { h, icon, clear, toast, confirmDialog, modal } from './dom.js';
import * as store from '../store.js';
import * as sync from '../sync.js';
import { allHomeRows } from './today.js';
import { moveKey, parsePluginUrl, relTime, syncFace, toCsv, LIBRARY_COLUMNS, libraryCsvRows, HISTORY_COLUMNS, historyCsvRows, historyJson, buildHistory } from '../ux.js';
import { parseEpisodeId } from '../progress.js';
import { catalogTitle } from '../addon/protocol.js';
import { COMMON_LANGS, languageName, SUB_PRESETS, presetOf } from '../player/subtitles.js';
import { VERSION } from '../util.js';
import { applyTimeoutSetting } from '../addon/service.js';

const S = () => store.settings();

function row(label, desc, control) {
  return h('div', { class: 'setrow' }, h('div', { class: 'tx' }, h('b', { text: label }), desc ? h('span', { text: desc }) : null), control);
}
function toggle(label, desc, key, after) {
  const id = 'set-' + key;
  return h('div', { class: 'switch' }, h('label', { class: 'tx', for: id }, h('b', { text: label }), desc ? h('span', { text: desc }) : null),
    h('input', { class: 'tg', type: 'checkbox', id, checked: !!S()[key], onChange: (e) => { store.setSettings({ [key]: e.target.checked }); after?.(); } }));
}
function select(label, desc, key, options, { num = false, after } = {}) {
  const id = 'set-' + key;
  const sel = h('select', { class: 'inp', id, 'aria-label': label, onChange: (e) => { store.setSettings({ [key]: num ? Number(e.target.value) : e.target.value }); after?.(); } },
    options.map(([v, l]) => h('option', { value: String(v), selected: String(S()[key]) === String(v), text: l })));
  return row(label, desc, sel);
}
function range(label, desc, key, min, max, step, fmt) {
  const out = h('output', { class: 'mute', text: fmt(S()[key]) });
  const inp = h('input', { class: 'rng', type: 'range', min, max, step, value: S()[key], 'aria-label': label,
    onInput: (e) => { const v = Number(e.target.value); inp.style.setProperty('--v', ((v - min) / (max - min)) * 100 + '%'); out.textContent = fmt(v); store.setSettings({ [key]: v }); } });
  inp.style.setProperty('--v', ((S()[key] - min) / (max - min)) * 100 + '%');
  return row(label, desc, h('div', {}, inp, out));
}

// ---------- accordion ----------
const OPEN_KEY = 'liuying-settings-open';
const readOpen = () => { try { const v = JSON.parse(localStorage.getItem(OPEN_KEY)); if (Array.isArray(v)) return new Set(v); } catch { /* ignore */ } return new Set(['account']); };
const saveOpen = (set) => { try { localStorage.setItem(OPEN_KEY, JSON.stringify([...set])); } catch { /* ignore */ } };
export const SECTION_IDS = ['account', 'security', 'appearance', 'layout', 'playback', 'subtitles', 'shelf', 'sources', 'plugins', 'integrations', 'storage', 'about'];

export function settings({ query = {} } = {}) {
  const view = document.getElementById('view');
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  const open = readOpen();
  const deep = SECTION_IDS.includes(query.open) ? query.open : '';
  if (deep) { open.add(deep); saveOpen(open); }
  const sums = [];
  const stops = [];
  const refreshSums = () => sums.forEach((f) => f());
  const rerender = () => { const y = scrollY; build(); scrollTo(0, y); };

  /** Collapsible section: header button (aria-expanded/controls) with a one-line summary, animated panel. */
  function acc(id, title, summaryFn, ...body) {
    const isOpen = open.has(id);
    const bid = `acc-${id}-body`, hid = `acc-${id}-btn`;
    const sum = h('span', { class: 'sum', text: summaryFn() });
    const btn = h('button', { class: 'acc-h', type: 'button', id: hid, 'aria-expanded': String(isOpen), 'aria-controls': bid, onClick: () => set(!el.classList.contains('open')) }, h('span', { class: 't', text: title }), sum, icon('down', 20));
    const panel = h('div', { class: 'acc-body', id: bid, role: 'region', 'aria-labelledby': hid }, h('div', { class: 'acc-in' }, h('div', { class: 'acc-pad' }, body)));
    const el = h('section', { class: 'acc' + (isOpen ? ' open' : ''), 'data-acc': id }, h('h2', {}, btn), panel);
    panel.inert = !isOpen;
    function set(v) {
      el.classList.toggle('open', v); btn.setAttribute('aria-expanded', String(v)); panel.inert = !v;
      if (v) open.add(id); else open.delete(id);
      saveOpen(open);
    }
    sums.push(() => { sum.textContent = summaryFn(); });
    return el;
  }

  function build() {
    sums.length = 0;
    builtFor = !!sync.getStatus().user;
    stops.splice(0).forEach((f) => f());
    const rows = allHomeRows();
    const langOpts = COMMON_LANGS.map((c) => [c, languageName(c)]);
    const subLangs = S().subLangs || ['en'];
    const status = sync.getStatus();
    const storageNote = h('span', { text: 'Calculating…' });
    const opt = (key, list) => (list.find(([v]) => String(v) === String(S()[key])) || [0, String(S()[key])])[1];
    const THEMES = [['system', 'System'], ['dark', 'Ink'], ['black', 'Pure black'], ['paper', 'Paper']];
    const ACC = [['vermilion', 'Vermilion'], ['gold', 'Gold'], ['jade', 'Jade'], ['indigo', 'Indigo']];
    const SIZES = [['s', 'Small'], ['m', 'Medium'], ['l', 'Large']];
    const RESUME = [['auto', 'Resume automatically'], ['ask', 'Ask me'], ['never', 'Always start over']];
    const SUBBG = [['shadow', 'Outline shadow'], ['box', 'Dark box'], ['none', 'None']];
    const onOff = (v) => (v ? 'on' : 'off');
    const root = h('div', { class: 'acc-list' },
      acc('account', 'Account', () => { const st = sync.getStatus(); return st.user ? `${st.user.username} · ${syncLabel(st)}` : 'Signed out · saved in this browser only'; },
        status.user
          ? h('div', {}, h('p', { text: `Signed in as ${status.user.username}. Sources, shelf, progress and settings sync.` }), syncPanel(),
            h('div', { class: 'actions', style: { 'margin-top': '12px' } },
              h('a', { class: 'btn sm', href: '/account', text: 'Account' }), h('button', { class: 'btn sm', type: 'button', text: 'Delete synced data', onClick: delRemote })))
          : h('div', {}, h('p', { class: 'mute', text: 'You are using Liuying signed out. Everything is saved in this browser only.' }), h('a', { class: 'btn pri', href: '/login', text: 'Sign in to sync', style: { 'margin-top': '12px' } }))),
      acc('security', 'Security and privacy', () => (sync.getStatus().user ? 'Password and sessions on your account page' : 'No tracking · sign in for account security'),
        row('Password and sessions', 'Change your password or sign out other devices.', sync.getStatus().user ? h('a', { class: 'btn sm', href: '/account', text: 'Open account' }) : h('a', { class: 'btn sm', href: '/login', text: 'Sign in' })),
        h('p', { class: 'mute', text: 'There is no analytics or tracking in this player. Requests go only to your own sources and, if you sign in, to the Liuying account service. Everything else stays in this browser.' })),
      acc('appearance', 'Appearance', () => `${opt('theme', THEMES)} · ${opt('accent', ACC)} accent · ${opt('fontScale', SIZES).toLowerCase()} text`,
        select('Theme', 'System follows your device: Ink in dark mode, Paper in light mode.', 'theme', [['system', 'System'], ['dark', 'Ink'], ['black', 'Pure black'], ['paper', 'Paper (light)']], { after: refreshSums }),
        toggle('Spoiler protection', 'Blurs the picture and description of episodes you have not watched yet.', 'spoilers'),
        toggle('Reduce motion', 'Stops the rotating banner, shimmer and other movement.', 'reduceMotion'),
        select('Accent colour', 'Used for buttons, selected chips and the seal.', 'accent', ACC, { after: refreshSums }),
        select('Text size', 'Scales the page content.', 'fontScale', SIZES, { after: refreshSums }),
        select('Poster size', null, 'posterSize', SIZES),
        toggle('Titles under posters', null, 'titlesUnder'),
        toggle('Watched and shelf badges', null, 'showBadges')),
      acc('layout', 'Today layout', () => { const r = allHomeRows(); return `${r.filter((x) => !x.hidden).length} of ${r.length} catalogs shown · banner ${onOff(S().hero)}`; },
        toggle('Continue Watching', 'Show in-progress titles at the top.', 'showContinue', rerender),
        toggle('Up next cards', 'Suggest the next episode of series you are watching.', 'showUpNext'),
        toggle('Featured banner', null, 'hero', refreshSums),
        toggle('Hide watched titles', 'Leave titles you have finished out of catalog rows and Browse.', 'hideWatched'),
        select('Banner catalog', 'Which catalog feeds the banner.', 'heroCatalog', [['', 'First row'], ...rows.map((r) => [r.key, `${catalogTitle(r.cat)} (${r.src.manifest.name})`])]),
        toggle('Hide adult-flagged sources', null, 'hideAdult', () => { refreshSums(); rerender(); }),
        layoutList()),
      acc('playback', 'Playback', () => `${opt('resume', RESUME)} · auto-next ${onOff(S().autoNext)} · ${S().seekStep}s seek`,
        select('When a title has progress', null, 'resume', RESUME, { after: refreshSums }),
        range('Mark watched at', 'Share of the runtime after which a title counts as watched.', 'threshold', 0.8, 0.98, 0.01, (v) => `${Math.round(v * 100)}%`),
        toggle('Auto-play next episode', null, 'autoNext', refreshSums),
        select('Next episode countdown', null, 'countdown', [[5, '5 seconds'], [10, '10 seconds'], [15, '15 seconds']], { num: true }),
        select('Rewind on resume', 'Back up a little when resuming so you catch what you missed.', 'resumeRewind', [[0, 'Off'], [3, '3 seconds'], [5, '5 seconds'], [10, '10 seconds'], [15, '15 seconds']], { num: true }),
        select('Long skip', 'The forward button next to play and the S key.', 'longSkip', [[60, '60 seconds'], [85, '85 seconds'], [90, '90 seconds'], [120, '2 minutes']], { num: true }),
        select('Seek step', 'For the skip buttons and arrow keys.', 'seekStep', [[5, '5 seconds'], [10, '10 seconds'], [15, '15 seconds'], [30, '30 seconds']], { num: true, after: refreshSums }),
        select('Default speed', null, 'speed', [[0.75, '0.75×'], [1, '1×'], [1.25, '1.25×'], [1.5, '1.5×'], [2, '2×']], { num: true }),
        toggle('Auto-select a stream', 'Start the best playable stream after a short wait, with a cancel button.', 'autoSelect'),
        select('Stream list', null, 'streamGroup', [['source', 'Grouped by source'], ['flat', 'One list, best quality first']]),
        select('Sort inside groups', null, 'streamSort', [['default', 'As the source ordered them'], ['resolution', 'Resolution'], ['resolution-size', 'Resolution, then size'], ['size', 'Size'], ['name', 'Name']]),
        toggle('Hide streams that cannot play here', 'Torrent and blocked streams are shown disabled by default.', 'hideUnplayable')),
      acc('subtitles', 'Audio and subtitles', () => `${languageName((S().subLangs || ['en'])[0] || 'en')} · ${S().subMode === 'off' ? 'off until chosen' : 'on'} · ${opt('subBg', SUBBG).toLowerCase()}`,
        row('Preferred subtitle language', 'Used to pick subtitles automatically.', h('select', { class: 'inp', id: 'set-_l1', 'aria-label': 'Preferred subtitle language', onChange: (e) => { store.setSettings({ subLangs: [e.target.value, ...(S().subLangs || []).filter((x) => x !== e.target.value)].slice(0, 3) }); refreshSums(); } }, langOpts.map(([v, l]) => h('option', { value: v, selected: v === (subLangs[0] || 'en'), text: l })))),
        select('Subtitles', null, 'subMode', [['on', 'Show preferred language when available'], ['off', 'Off until I choose']], { after: refreshSums }),
        row('Style preset', 'High contrast and large print are easier to read. Change any detail below and it becomes Custom.', h('select', { class: 'inp', id: 'set-_preset', 'aria-label': 'Subtitle style preset', onChange: (e) => { const p = SUB_PRESETS.find((x) => x.id === e.target.value); if (p) { store.setSettings(p.values); rerender(); } } }, [...SUB_PRESETS.map((p) => h('option', { value: p.id, selected: presetOf(S()) === p.id, text: p.label })), presetOf(S()) === 'custom' ? h('option', { value: 'custom', selected: true, text: 'Custom' }) : null])),
        range('Text size', null, 'subSize', 50, 200, 5, (v) => `${v}%`),
        select('Text colour', null, 'subColor', [['#ffffff', 'White'], ['#ffe27a', 'Yellow'], ['#8de0ff', 'Cyan'], ['#9be58f', 'Green'], ['#ffb3a8', 'Rose']]),
        select('Background', null, 'subBg', SUBBG, { after: refreshSums }),
        toggle('Bold', null, 'subBold'),
        range('Height from bottom', null, 'subOffset', 0, 40, 1, (v) => `${v}%`)),
      acc('shelf', 'Shelf and history', () => `${store.library().length} on shelf · history ${onOff(S().saveHistory)}`,
        toggle('Save watch history', 'Turn off to stop recording new progress.', 'saveHistory', refreshSums),
        toggle('Remember recent searches', null, 'recentSearches'),
        row('Watch history', 'Everything you watched or started, by day. Remove entries you do not want.', h('a', { class: 'btn sm', href: '#/history', text: 'Open history' })),
        row('Export', 'Spreadsheet (CSV) or data (JSON) copies of your shelf and history.', h('div', { class: 'actions wrap', role: 'group', 'aria-label': 'Export' },
          h('button', { class: 'btn sm', type: 'button', text: 'Shelf CSV', onClick: () => exportData('shelf', 'csv') }), h('button', { class: 'btn sm', type: 'button', text: 'Shelf JSON', onClick: () => exportData('shelf', 'json') }),
          h('button', { class: 'btn sm', type: 'button', text: 'History CSV', onClick: () => exportData('history', 'csv') }), h('button', { class: 'btn sm', type: 'button', text: 'History JSON', onClick: () => exportData('history', 'json') }))),
        row('Clear all progress', 'Resume points, watched marks and Continue Watching.', h('button', { class: 'btn sm', type: 'button', text: 'Clear progress', onClick: clearProg })),
        row('Backup', 'Sources, shelf, progress and settings in one file.', h('div', { class: 'actions' }, h('button', { class: 'btn sm', type: 'button', text: 'Export', onClick: exportBackup }), h('button', { class: 'btn sm', type: 'button', text: 'Import', onClick: importBackup })))),
      acc('sources', 'Sources', () => `${store.sources().length} added · ${store.enabledSources().length} enabled`,
        row('Manage sources', store.sources().length ? store.sources().map((x) => x.manifest?.name || new URL(x.url).host).join(', ') : 'Add, enable, reorder or remove your sources.', h('a', { class: 'btn sm', href: '#/sources', text: 'Open sources' })),
        toggle('Patient mode', 'Waits longer for slow sources before giving up.', 'patient', applyTimeoutSetting),
        row('Insecure HTTP sources', 'Not available on the web player. Browsers block them on a secure page.', h('span', { class: 'badge', text: 'Blocked' }))),
      acc('plugins', 'Repositories and plugins', () => `${plugins().length} saved link${plugins().length === 1 ? '' : 's'} · not runnable yet`,
        h('p', { class: 'mute', text: 'Plugin repositories add new kinds of sources, like on the Android app. Plugins cannot run in the web player yet: you can save a manifest link here, but nothing is installed or executed.' }),
        pluginForm(),
        pluginList()),
      acc('integrations', 'Integrations', () => 'Trakt, Simkl, TMDb · coming soon',
        h('div', { class: 'intgrid' }, [['Trakt', 'Sync watch history and ratings.'], ['Simkl', 'Track what you watch across apps.'], ['TMDb', 'Richer artwork and cast details.']].map(([n, d]) =>
          h('div', { class: 'intcard', 'aria-disabled': 'true' }, h('div', { class: 'intn', 'aria-hidden': 'true', text: n[0] }), h('div', { class: 'grow' }, h('b', { text: n }), h('span', { class: 'mute', text: d })), h('span', { class: 'badge', text: 'Coming soon' }))))),
      acc('storage', 'Storage', () => `${(store.metaCacheSize() / 1024).toFixed(0)} KB cached details`,
        row('Cached details', 'Titles remembered so cards and Continue Watching load fast.', h('div', { class: 'actions' }, storageNote, h('button', { class: 'btn sm', type: 'button', text: 'Clear cache', onClick: () => { store.clearMetaCache(); toast('Cache cleared'); rerender(); } }))),
        row('Recent searches', null, h('button', { class: 'btn sm', type: 'button', text: 'Clear', onClick: () => { store.clearRecent(); toast('Recent searches cleared'); } })),
        row('Reset the web player', 'Removes sources, shelf, progress and settings from this browser.', h('button', { class: 'btn sm danger', type: 'button', text: 'Reset…', onClick: resetAll }))),
      acc('about', 'About', () => `Liuying Web Player ${VERSION}`,
        h('p', { text: `Liuying Web Player ${VERSION}` }),
        h('p', { class: 'mute', text: 'Liuying does not host, provide or endorse any content. Sources are added by the user, who is responsible for them.' }),
        h('div', { class: 'actions', style: { 'margin-top': '12px' } }, h('button', { class: 'btn sm', type: 'button', text: 'Copy debug info', onClick: copyDebug }), h('a', { class: 'btn sm', href: '/', text: 'Liuying home' }))));
    clear(page).append(h('div', { class: 'page-h' }, h('h1', { text: 'Settings' })), root);
    storageNote.textContent = `${(store.metaCacheSize() / 1024).toFixed(0)} KB`;
    if (deep) { const t = root.querySelector(`[data-acc="${deep}"]`); requestAnimationFrame(() => t?.scrollIntoView({ block: 'start' })); }
  }

  /** Today layout: catalogs grouped per source in sub-dropdowns, with a filter box and Show all / Hide all. */
  function layoutList() {
    if (!allHomeRows().length) return h('p', { class: 'mute', text: 'Add a source to arrange its catalogs here.' });
    const openSrc = new Set();
    let q = '';
    const box = h('div', { class: 'lgroups' });
    const filter = h('input', { class: 'inp', type: 'search', placeholder: 'Filter catalogs', 'aria-label': 'Filter catalogs', onInput: () => { q = filter.value.trim().toLowerCase(); draw(); } });
    const setHidden = (keys, hidden) => { const cur = { ...S().hiddenCatalogs }; keys.forEach((k) => { cur[k] = hidden; }); store.setSettings({ hiddenCatalogs: cur }); refreshSums(); };
    function draw(focusSel) {
      const all = allHomeRows();
      const order = all.map((r) => r.key);
      const groups = new Map();
      for (const r of all) { const id = store.sourceId(r.src); if (!groups.has(id)) groups.set(id, { id, name: r.src.manifest.name, rows: [] }); groups.get(id).rows.push(r); }
      clear(box);
      let shown = 0;
      for (const g of groups.values()) {
        const match = (r) => !q || `${catalogTitle(r.cat)} ${r.cat.name || ''} ${r.cat.id} ${g.name}`.toLowerCase().includes(q);
        const vis = g.rows.filter(match);
        if (!vis.length) continue;
        shown += vis.length;
        const on = g.rows.filter((r) => !r.hidden).length;
        const isOpen = !!q || openSrc.has(g.id);
        const sid = `lg-${g.id}`;
        const btn = h('button', { class: 'sacc-h', type: 'button', 'aria-expanded': String(isOpen), 'aria-controls': sid, onClick: () => { if (openSrc.has(g.id)) openSrc.delete(g.id); else openSrc.add(g.id); draw(`[data-sg="${g.id}"]`); } }, h('span', { class: 't', text: g.name }), h('span', { class: 'sum', text: `${on} of ${g.rows.length} shown` }), icon('down', 18));
        btn.dataset.sg = g.id;
        const body = h('div', { class: 'sacc-body', id: sid, role: 'region', 'aria-label': `${g.name} catalogs` },
          h('div', { class: 'bulk' },
            h('button', { class: 'btn sm', type: 'button', text: 'Show all', onClick: () => { setHidden(g.rows.map((r) => r.key), false); draw(); } }),
            h('button', { class: 'btn sm', type: 'button', text: 'Hide all', onClick: () => { setHidden(g.rows.map((r) => r.key), true); draw(); } })),
          h('ol', { class: 'lrows' }, vis.map((r) => {
            const i = g.rows.indexOf(r);
            const move = (d) => { const nb = g.rows[i + d]; if (!nb) return; const a = [...order]; const x = a.indexOf(r.key), y = a.indexOf(nb.key); [a[x], a[y]] = [a[y], a[x]]; store.setSettings({ homeOrder: a }); draw(`[data-mv="${d < 0 ? 'u' : 'd'}:${r.key}"]`); };
            const mv = (d, name, ic, dis) => { const b = h('button', { class: 'ibtn', type: 'button', 'aria-label': `Move ${catalogTitle(r.cat)} ${name}`, disabled: dis, onClick: () => move(d) }, icon(ic, 18)); b.dataset.mv = `${d < 0 ? 'u' : 'd'}:${r.key}`; return b; };
            const sw = h('input', { class: 'tg', type: 'checkbox', checked: !r.hidden, 'aria-label': `Show ${catalogTitle(r.cat)} on Today`, onChange: (e) => { setHidden([r.key], !e.target.checked); draw(`[data-sw="${r.key}"]`); } });
            sw.dataset.sw = r.key;
            return h('li', { class: r.hidden ? 'off' : '' }, h('div', { class: 'grow' }, h('b', { text: catalogTitle(r.cat) })), mv(-1, 'up', 'up', i === 0), mv(1, 'down', 'down', i === g.rows.length - 1), sw);
          })));
        body.hidden = !isOpen;
        box.append(h('div', { class: 'sacc' + (isOpen ? ' open' : '') }, btn, body));
      }
      if (!shown) box.append(h('p', { class: 'mute', text: 'No catalogs match.' }));
      if (focusSel) { const el = [...box.querySelectorAll('[data-sg],[data-mv],[data-sw]')].find((n) => focusSel === `[data-sg="${n.dataset.sg}"]` || focusSel === `[data-mv="${n.dataset.mv}"]` || focusSel === `[data-sw="${n.dataset.sw}"]`); el?.focus({ preventScroll: true }); }
    }
    const wrap = h('div', { class: 'layout' },
      h('h3', { class: 'subh', text: 'Catalogs' }),
      h('p', { class: 'mute', text: 'Choose which catalogs appear on Today and in what order. Catalogs are grouped by source.' }),
      h('div', { class: 'ltools' }, filter,
        h('button', { class: 'btn sm', type: 'button', text: 'Show all', onClick: () => { setHidden(allHomeRows().map((r) => r.key), false); draw(); } }),
        h('button', { class: 'btn sm', type: 'button', text: 'Hide all', onClick: () => { setHidden(allHomeRows().map((r) => r.key), true); draw(); } })),
      box,
      row('Reset layout', 'Show every catalog again, in the sources’ own order.', h('button', { class: 'btn sm', type: 'button', text: 'Reset layout', onClick: () => { store.setSettings({ hiddenCatalogs: {}, homeOrder: [], heroCatalog: '' }); toast('Today layout reset'); rerender(); } })));
    draw();
    return wrap;
  }

  const plugins = () => (Array.isArray(store.prefs().plugins) ? store.prefs().plugins : []);
  function pluginForm() {
    const inp = h('input', { class: 'inp', type: 'url', id: 'plug-url', placeholder: 'https://example.com/plugin/manifest.json', 'aria-label': 'Plugin manifest link', autocomplete: 'off' });
    const add = async () => {
      const url = parsePluginUrl(inp.value);
      if (!url) { toast('Enter a full https:// link to a plugin manifest.'); inp.focus(); return; }
      if (plugins().some((p) => p.url === url)) { toast('That link is already saved.'); return; }
      const ok = await confirmDialog('Save this plugin link?', `Plugins are code written by third parties and Liuying does not review them. Saving only records the link ${new URL(url).host}; nothing is downloaded or run, and the web player cannot run plugins yet.`, 'Save link');
      if (!ok) return;
      store.setPref('plugins', [...plugins(), { url, addedAt: Date.now() }]);
      toast('Link saved. Not yet runnable.'); rerender();
    };
    return h('form', { class: 'plug-form', onSubmit: (e) => { e.preventDefault(); add(); } }, h('label', { class: 'lbl', for: 'plug-url', text: 'Add by manifest link' }), h('div', { class: 'plug-in' }, inp, h('button', { class: 'btn pri', type: 'submit', text: 'Add' })));
  }
  function pluginList() {
    const list = plugins();
    if (!list.length) return h('p', { class: 'mute', text: 'No plugin links saved.' });
    const host = (u) => { try { return new URL(u).host; } catch { return u; } };
    const ul = h('ul', { class: 'plist compact' });
    const count = h('p', { class: 'mute pcount' });
    const draw = (q = '') => {
      const vis = list.filter((p) => !q || p.url.toLowerCase().includes(q));
      clear(ul).append(...vis.map((p) => h('li', {},
        h('div', { class: 'grow' }, h('b', { text: host(p.url) }), h('span', { class: 'mute purl', text: p.url })),
        h('button', { class: 'ibtn', type: 'button', 'aria-label': `Remove ${p.url}`, onClick: () => { store.setPref('plugins', plugins().filter((x) => x.url !== p.url)); rerender(); } }, icon('trash', 18)))));
      count.textContent = vis.length ? `${vis.length} of ${list.length} saved · not yet runnable` : 'No saved links match.';
    };
    draw();
    return h('div', {}, h('input', { class: 'inp', type: 'search', placeholder: 'Search saved links', 'aria-label': 'Search saved plugin links', onInput: (e) => draw(e.target.value.trim().toLowerCase()) }), count, h('div', { class: 'plist-box' }, ul));
  }

  /** Last synced time, pending count and a Sync now button, with a glyph so the state is not colour alone (L032, L104). */
  function syncPanel() {
    const glyph = h('span', { class: 'sp-glyph', 'aria-hidden': 'true' });
    const word = h('b', {});
    const meta = h('span', { class: 'mute', role: 'status' });
    const btn = h('button', { class: 'btn sm pri', type: 'button', text: 'Sync now', onClick: async () => { btn.disabled = true; try { await sync.pull(); await sync.push(); } finally { btn.disabled = false; } } });
    const box = h('div', { class: 'syncpanel', role: 'group', 'aria-label': 'Sync status' }, glyph, h('div', { class: 'grow' }, word, meta), btn);
    const upd = (st = sync.getStatus()) => {
      const f = syncFace(st.state, st.waking);
      box.dataset.state = st.waking ? 'waking' : st.state;
      glyph.textContent = f.glyph || '·'; word.textContent = f.word;
      const parts = [`Last synced ${relTime(st.lastSync)}`, st.pending ? `${st.pending} change${st.pending === 1 ? '' : 's'} waiting to upload` : 'nothing waiting to upload'];
      if (st.waking) parts.unshift('Waking the server, this can take up to a minute');
      else if (st.state === 'error' && st.error) parts.push(`problem: ${st.error}`);
      else if (st.state === 'offline') parts.push('offline, will retry');
      meta.textContent = parts.join(' · ');
      btn.textContent = st.state === 'syncing' ? 'Syncing…' : 'Sync now';
    };
    stops.push(sync.subscribeStatus((st) => upd(st)));
    const t = setInterval(() => upd(), 30000);
    stops.push(() => clearInterval(t));
    upd();
    return box;
  }

  function download(name, text, type) {
    const a = h('a', { href: URL.createObjectURL(new Blob([text], { type })), download: name });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
  }
  function exportData(what, fmt) {
    const day = new Date().toISOString().slice(0, 10);
    const hint = (id) => { const m = store.cachedMeta(id); const l = store.library().find((i) => i.id === id); return { name: m?.name || l?.name, type: m?.type || l?.type }; };
    if (what === 'shelf') {
      const lib = store.library();
      if (fmt === 'csv') download(`liuying-shelf-${day}.csv`, toCsv(LIBRARY_COLUMNS, libraryCsvRows(lib)), 'text/csv');
      else download(`liuying-shelf-${day}.json`, JSON.stringify(lib.map((i) => ({ id: i.id, type: i.type, title: i.name, year: i.year || null, added: i.addedAt ? new Date(i.addedAt).toISOString() : null })), null, 1), 'application/json');
      toast(`Exported ${lib.length} shelf title${lib.length === 1 ? '' : 's'}`);
    } else {
      const days = buildHistory(store.allProgress(), hint, new Date(), parseEpisodeId);
      const n = days.reduce((a, d) => a + d.items.length, 0);
      if (fmt === 'csv') download(`liuying-history-${day}.csv`, toCsv(HISTORY_COLUMNS, historyCsvRows(days)), 'text/csv');
      else download(`liuying-history-${day}.json`, JSON.stringify(historyJson(days), null, 1), 'application/json');
      toast(`Exported ${n} history entr${n === 1 ? 'y' : 'ies'}`);
    }
  }

  const syncLabel = (st) => ({ idle: 'up to date', syncing: 'syncing', offline: 'offline, will retry', error: `problem (${st.error || 'will retry'})`, checking: 'checking', signedout: 'signed out' }[st.state] || st.state);

  async function clearProg() {
    if (!(await confirmDialog('Clear all progress?', 'Resume points and watched marks are removed from this browser. Your shelf and sources stay.', 'Clear', true))) return;
    store.clearProgress(); toast('Progress cleared');
  }
  async function delRemote() {
    if (!(await confirmDialog('Delete synced data?', 'Removes your synced sources, shelf, progress and settings from your account. This browser keeps its local copy.', 'Delete', true))) return;
    try { await sync.deleteRemoteAll(); toast('Synced data deleted'); } catch { toast('Could not reach the server'); }
  }
  async function resetAll() {
    const inp = h('input', { class: 'inp', 'aria-label': 'Type RESET to confirm', placeholder: 'RESET' });
    const r = await modal({ title: 'Reset the web player?', body: h('div', {}, h('p', { text: 'This removes everything stored in this browser. Type RESET to confirm.' }), h('div', { style: { height: '10px' } }), inp),
      actions: [{ label: 'Cancel', value: false }, { label: 'Reset', value: true, primary: true, danger: true }] });
    if (r && inp.value.trim() === 'RESET') { await store.resetAll(); toast('Reset complete'); location.hash = '#/'; location.reload(); }
    else if (r) toast('Not reset. Type RESET exactly.');
  }
  function exportBackup() {
    const data = { app: 'liuying-web', version: 1, exportedAt: new Date().toISOString(), sources: store.sources().map((s) => ({ url: s.url, enabled: s.enabled })), library: store.library(), progress: store.allProgress(), settings: store.settings() };
    const a = h('a', { href: URL.createObjectURL(new Blob([JSON.stringify(data, null, 1)], { type: 'application/json' })), download: 'liuying-backup.json' });
    document.body.append(a); a.click(); a.remove();
  }
  function importBackup() {
    const f = h('input', { type: 'file', accept: 'application/json,.json' });
    f.addEventListener('change', async () => {
      try {
        const d = JSON.parse(await f.files[0].text());
        if (d.app !== 'liuying-web') throw new Error('not a Liuying backup');
        let n = 0;
        for (const s of d.sources || []) if (typeof s.url === 'string' && !store.sources().some((x) => x.url === s.url)) { store.setSources([...store.sources(), { url: s.url, enabled: s.enabled !== false, manifest: null, addedAt: Date.now() }]); n++; }
        for (const i of d.library || []) if (i?.id && !store.inLibrary(i.id)) store.addToLibrary(i);
        for (const p of d.progress || []) { const cur = store.getProgress(p.mediaId, p.episodeId); if (!cur || cur.updatedAt < p.updatedAt) store.putProgress(p); }
        if (d.settings) store.setSettings(d.settings);
        toast(`Imported (${n} new sources). Manifests load on next start.`); import('../addon/service.js').then((m) => m.refreshManifests());
      } catch (e) { toast('That file could not be imported: ' + e.message); }
    });
    f.click();
  }
  async function copyDebug() {
    const t = `Liuying Web ${VERSION}\nUA: ${navigator.userAgent}\nSources: ${store.sources().length} (${store.enabledSources().length} enabled)\nLibrary: ${store.library().length}\nSync: ${sync.getStatus().state}\nStorage: ${store.persistent() ? 'IndexedDB' : 'localStorage'}`;
    try { await navigator.clipboard.writeText(t); toast('Debug info copied'); } catch { toast('Copy failed'); }
  }

  let builtFor = null; // whether the page was drawn signed in: the sign-in check can finish after the page first drew
  const unsub = sync.subscribeStatus((st) => { refreshSums(); if (builtFor !== null && builtFor !== !!st.user) rerender(); });
  build();
  return { destroy() { unsub(); stops.forEach((f) => f()); } };
}
