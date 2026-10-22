import { h, icon, clear, toast, confirmDialog, modal } from './dom.js';
import * as store from '../store.js';
import * as sync from '../sync.js';
import { homeRows } from './today.js';
import { catalogTitle } from '../addon/protocol.js';
import { COMMON_LANGS, languageName } from '../player/subtitles.js';
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
const group = (title, ...kids) => h('section', { class: 'setgroup', 'aria-label': title }, h('h2', { text: title }), kids);

export function settings() {
  const view = document.getElementById('view');
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  const rerender = () => { const y = scrollY; build(); scrollTo(0, y); };

  function build() {
    const rows = homeRows();
    const langOpts = COMMON_LANGS.map((c) => [c, languageName(c)]);
    const subLangs = S().subLangs || ['en'];
    const status = sync.getStatus();
    const storageNote = h('span', { text: 'Calculating…' });
    const root = h('div', {},
      h('div', { class: 'page-h' }, h('h1', { text: 'Settings' })),
      group('Appearance',
        select('Theme', 'Ink is the default.', 'theme', [['dark', 'Ink'], ['black', 'Pure black'], ['paper', 'Paper (light)']]),
        toggle('Reduce motion', 'Stops the rotating banner, shimmer and other movement.', 'reduceMotion'),
        select('Poster size', null, 'posterSize', [['s', 'Small'], ['m', 'Medium'], ['l', 'Large']]),
        toggle('Titles under posters', null, 'titlesUnder'),
        toggle('Watched and shelf badges', null, 'showBadges')),
      group('Today layout',
        toggle('Continue Watching', 'Show in-progress titles at the top.', 'showContinue', rerender),
        toggle('Up next cards', 'Suggest the next episode of series you are watching.', 'showUpNext'),
        toggle('Featured banner', null, 'hero'),
        select('Banner catalog', 'Which catalog feeds the banner.', 'heroCatalog', [['', 'First row'], ...rows.map((r) => [r.key, `${catalogTitle(r.cat)} (${r.src.manifest.name})`])]),
        toggle('Hide adult-flagged sources', null, 'hideAdult'),
        row('Rows', 'Choose which catalogs appear in Sources › Details.', h('button', { class: 'btn sm', type: 'button', text: 'Show all rows again', onClick: () => { store.setSettings({ hiddenCatalogs: {}, heroCatalog: '' }); toast('Today layout reset'); rerender(); } }))),
      group('Playback',
        select('When a title has progress', null, 'resume', [['auto', 'Resume automatically'], ['ask', 'Ask me'], ['never', 'Always start over']]),
        range('Mark watched at', 'Share of the runtime after which a title counts as watched.', 'threshold', 0.8, 0.98, 0.01, (v) => `${Math.round(v * 100)}%`),
        toggle('Auto-play next episode', null, 'autoNext'),
        select('Next episode countdown', null, 'countdown', [[5, '5 seconds'], [10, '10 seconds'], [15, '15 seconds']], { num: true }),
        select('Seek step', 'For the skip buttons and arrow keys.', 'seekStep', [[5, '5 seconds'], [10, '10 seconds'], [15, '15 seconds'], [30, '30 seconds']], { num: true }),
        select('Default speed', null, 'speed', [[0.75, '0.75×'], [1, '1×'], [1.25, '1.25×'], [1.5, '1.5×'], [2, '2×']], { num: true }),
        toggle('Auto-select a stream', 'Start the best playable stream after a short wait, with a cancel button.', 'autoSelect'),
        select('Stream list', null, 'streamGroup', [['source', 'Grouped by source'], ['flat', 'One list, best quality first']]),
        select('Sort inside groups', null, 'streamSort', [['default', 'As the source ordered them'], ['resolution', 'Resolution'], ['size', 'Size'], ['name', 'Name']]),
        toggle('Hide streams that cannot play here', 'Torrent and blocked streams are shown disabled by default.', 'hideUnplayable')),
      group('Audio and subtitles',
        row('Preferred subtitle language', 'Used to pick subtitles automatically.', h('select', { class: 'inp', id: 'set-_l1', 'aria-label': 'Preferred subtitle language', onChange: (e) => store.setSettings({ subLangs: [e.target.value, ...(S().subLangs || []).filter((x) => x !== e.target.value)].slice(0, 3) }) }, langOpts.map(([v, l]) => h('option', { value: v, selected: v === (subLangs[0] || 'en'), text: l })))),
        select('Subtitles', null, 'subMode', [['on', 'Show preferred language when available'], ['off', 'Off until I choose']]),
        range('Text size', null, 'subSize', 50, 200, 5, (v) => `${v}%`),
        select('Text colour', null, 'subColor', [['#ffffff', 'White'], ['#ffe27a', 'Yellow'], ['#8de0ff', 'Cyan'], ['#9be58f', 'Green'], ['#ffb3a8', 'Rose']]),
        select('Background', null, 'subBg', [['shadow', 'Outline shadow'], ['box', 'Dark box'], ['none', 'None']]),
        toggle('Bold', null, 'subBold'),
        range('Height from bottom', null, 'subOffset', 0, 40, 1, (v) => `${v}%`)),
      group('Sources and content',
        toggle('Patient mode', 'Waits longer for slow sources before giving up.', 'patient', applyTimeoutSetting),
        row('Insecure HTTP sources', 'Not available on the web player. Browsers block them on a secure page.', h('span', { class: 'badge', text: 'Blocked' })),
        row('Manage sources', null, h('a', { class: 'btn sm', href: '#/sources', text: 'Open sources' }))),
      group('Shelf and history',
        toggle('Save watch history', 'Turn off to stop recording new progress.', 'saveHistory'),
        toggle('Remember recent searches', null, 'recentSearches'),
        row('Clear all progress', 'Resume points, watched marks and Continue Watching.', h('button', { class: 'btn sm', type: 'button', text: 'Clear progress', onClick: clearProg })),
        row('Backup', 'Sources, shelf, progress and settings in one file.', h('div', { class: 'actions' }, h('button', { class: 'btn sm', type: 'button', text: 'Export', onClick: exportBackup }), h('button', { class: 'btn sm', type: 'button', text: 'Import', onClick: importBackup })))),
      group('Account and sync',
        status.user
          ? h('div', {}, h('p', { text: `Signed in as ${status.user.username}. Sources, shelf, progress and settings sync.` }), h('p', { class: 'mute', text: `Status: ${syncLabel(status)}` }),
            h('div', { class: 'actions', style: { 'margin-top': '12px' } }, h('button', { class: 'btn sm', type: 'button', text: 'Sync now', onClick: async () => { await sync.pull(); sync.schedulePush.flush?.(); sync.push(); toast('Syncing…'); } }),
              h('a', { class: 'btn sm', href: '/account', text: 'Account' }), h('button', { class: 'btn sm', type: 'button', text: 'Delete synced data', onClick: delRemote })))
          : h('div', {}, h('p', { class: 'mute', text: 'You are using Liuying signed out. Everything is saved in this browser only.' }), h('a', { class: 'btn pri', href: '/login', text: 'Sign in to sync', style: { 'margin-top': '12px' } }))),
      group('Storage',
        row('Cached details', 'Titles remembered so cards and Continue Watching load fast.', h('div', { class: 'actions' }, storageNote, h('button', { class: 'btn sm', type: 'button', text: 'Clear cache', onClick: () => { store.clearMetaCache(); toast('Cache cleared'); rerender(); } }))),
        row('Recent searches', null, h('button', { class: 'btn sm', type: 'button', text: 'Clear', onClick: () => { store.clearRecent(); toast('Recent searches cleared'); } })),
        row('Reset the web player', 'Removes sources, shelf, progress and settings from this browser.', h('button', { class: 'btn sm danger', type: 'button', text: 'Reset…', onClick: resetAll }))),
      group('Privacy',
        h('p', { class: 'mute', text: 'There is no analytics or tracking in this player. Requests go only to your own sources and, if you sign in, to the Liuying account service. Everything else stays in this browser.' })),
      group('About',
        h('p', { text: `Liuying Web Player ${VERSION}` }),
        h('p', { class: 'mute', text: 'Liuying does not host, provide or endorse any content. Sources are added by the user, who is responsible for them.' }),
        h('div', { class: 'actions', style: { 'margin-top': '12px' } }, h('button', { class: 'btn sm', type: 'button', text: 'Copy debug info', onClick: copyDebug }), h('a', { class: 'btn sm', href: '/', text: 'Liuying home' }))));
    clear(page).append(root);
    storageNote.textContent = `${(store.metaCacheSize() / 1024).toFixed(0)} KB`;
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

  const unsub = sync.subscribeStatus(() => { /* keep simple: status shown on build */ });
  build();
  return { destroy() { unsub(); } };
}
