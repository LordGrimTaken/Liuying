// Settings > Repositories and plugins: repositories by manifest URL, per-plugin switches and Test, consent per repository,
// the master "Run plugins" switch (default off) and the optional TMDB key. Mirrors the Android screen.
import { h, icon, clear, append, keepFocus, toast, modal } from './dom.js';
import * as sync from '../sync.js';
import * as svc from '../plugins/service.js';
import { RISK_NOTICE, ALWAYS, describePermission } from '../plugins/contract.js';
import { parsePluginUrl } from '../ux.js';

const tests = new Map(); // "repoUrl|pluginId" -> null (running) | {ok, streamCount, error, elapsedMs}

export function pluginsSummary() {
  const list = svc.repos();
  const loaded = list.filter((r) => (r.plugins || []).length || r.manifestLoaded);
  const enabled = loaded.reduce((n, r) => n + (r.plugins || []).filter((p) => p.enabled).length, 0);
  return `${loaded.length} ${loaded.length === 1 ? 'repository' : 'repositories'} · ${enabled} ${enabled === 1 ? 'plugin' : 'plugins'} enabled · Run plugins ${svc.masterOn() ? 'on' : 'off'}`;
}

/** The whole section body. `onChange` lets the settings page refresh its one-line summary. */
export function pluginsPanel({ onChange = () => {} } = {}) {
  const box = h('div', { class: 'plug' });
  const open = new Set(); // expanded repositories
  const openPlugins = new Set(); // expanded plugin rows
  const queries = new Map();
  let adding = false, addError = '';
  const urlInput = h('input', { class: 'inp', type: 'url', id: 'plug-url', placeholder: 'https://example.com/plugins/manifest.json', 'aria-label': 'Repository manifest URL', autocomplete: 'off' });
  let keyDraft = svc.tmdbKey();

  const redraw = () => { keepFocus(box, draw); onChange(); };

  async function askConsent(repo) {
    const perms = [...new Set((repo.plugins || []).flatMap((p) => p.permissions || []))];
    return modal({
      title: `Allow plugins from ${repo.name || hostOfUrl(repo.url)}?`,
      body: h('div', { class: 'consent' },
        h('p', { text: `Plugins are small scripts written by ${repo.author || 'the repository owner'}. A plugin you enable runs in a sandbox in this browser when you open a title, and could:` }),
        h('ul', {}, perms.length ? perms.map((p) => h('li', { class: 'perm', text: describePermission(p) })) : h('li', { text: 'Ask for nothing beyond the limits below' })),
        h('p', { text: 'Always:' }),
        h('ul', {}, ALWAYS.map((t) => h('li', { text: t }))),
        h('p', { text: 'Everything a plugin requests goes through the Liuying relay server (you must be signed in), so the sites it contacts see that server, not your device.' }),
        h('p', { text: 'Plugins only run while Run plugins is on. You can turn them off at any time, here or per plugin.' })),
      actions: [{ label: 'Not now', value: false }, { label: 'Allow', value: true, primary: true }],
    });
  }

  async function enable(repo, ids, on) {
    if (on && !repo.consented) {
      if (!(await askConsent(repo))) return;
      svc.consentRepo(repo.url);
    }
    svc.setPluginsEnabled(repo.url, ids, on);
    redraw();
  }

  async function addRepo() {
    const url = parsePluginUrl(urlInput.value);
    if (!url) { addError = 'Enter a full https:// link to a repository manifest.'; draw(); urlInput.focus(); return; }
    if (!sync.getStatus().user) { addError = 'Sign in to Liuying to add a repository: its files are fetched through your account.'; draw(); return; }
    adding = true; addError = ''; draw();
    try {
      const repo = await svc.addRepo(url);
      urlInput.value = '';
      open.add(repo.url);
      toast('Repository added. Plugins stay off until you allow them and turn on Run plugins.');
    } catch (e) { addError = String(e.message || e); }
    adding = false; redraw();
  }

  async function runTest(repo, plugin) {
    const k = `${repo.url}|${plugin.id}`;
    tests.set(k, null); draw();
    const r = await svc.testPlugin(repo, plugin);
    tests.set(k, r); draw();
  }

  function repoView(repo) {
    const plugins = repo.plugins || [];
    const isOpen = open.has(repo.url);
    const enabledCount = plugins.filter((p) => p.enabled).length;
    const master = svc.masterOn();
    const q = (queries.get(repo.url) || '').toLowerCase();
    const shown = plugins.filter((p) => !q || `${p.name} ${p.id} ${p.description}`.toLowerCase().includes(q));
    const sid = `repo-${btoa(unescape(encodeURIComponent(repo.url))).replace(/[^A-Za-z0-9]/g, '').slice(0, 24)}`;
    const head = h('button', { class: 'sacc-h', type: 'button', 'aria-expanded': String(isOpen), 'aria-controls': sid, onClick: () => { if (open.has(repo.url)) open.delete(repo.url); else open.add(repo.url); draw(); } },
      h('span', { class: 't', text: (repo.name || hostOfUrl(repo.url)) + (repo.version ? `  v${repo.version}` : '') }),
      h('span', { class: 'sum', text: repo.manifestLoaded ? `${plugins.length} plugins · ${enabledCount} on` : 'not loaded' }), icon('down', 18));
    const body = h('div', { class: 'sacc-body', id: sid, role: 'region', 'aria-label': `${repo.name || 'Repository'} plugins` });
    body.hidden = !isOpen;
    if (isOpen) {
      if (repo.description) body.append(h('p', { class: 'mute', text: repo.description }));
      body.append(h('p', { class: 'mute pmeta', text: [repo.author ? `by ${repo.author}` : '', hostOfUrl(repo.url), repo.consented ? 'Permissions accepted' : 'Permissions not yet accepted'].filter(Boolean).join(' · ') }));
      if (repo.lastError) body.append(h('p', { class: 'perr', role: 'status', text: `Last update failed: ${repo.lastError}` }));
      if (!repo.manifestLoaded) body.append(h('p', { class: 'mute', text: 'This link was saved without loading it. Press Update (signed in) to read the plugin list.' }));
      if (plugins.length > 8) body.append(h('input', { class: 'inp', type: 'search', placeholder: `Search ${plugins.length} plugins`, 'aria-label': `Search ${plugins.length} plugins`, value: queries.get(repo.url) || '', onInput: (e) => { queries.set(repo.url, e.target.value); redraw(); } }));
      const bulkLabel = q ? ` (${shown.length})` : '';
      body.append(h('div', { class: 'bulk wrapbulk' },
        h('button', { class: 'btn sm', type: 'button', text: `Enable all${bulkLabel}`, disabled: !master || !shown.some((p) => !p.enabled), onClick: () => enable(repo, shown.filter((p) => !p.enabled).map((p) => p.id), true) }),
        h('button', { class: 'btn sm', type: 'button', text: `Disable all${bulkLabel}`, disabled: !shown.some((p) => p.enabled), onClick: () => enable(repo, shown.filter((p) => p.enabled).map((p) => p.id), false) }),
        h('button', { class: 'btn sm', type: 'button', text: 'Update', onClick: async () => { const r = await svc.refreshRepo(repo.url); toast(r.ok ? 'Repository updated.' : `Update failed: ${r.error}`); redraw(); } }),
        h('button', { class: 'btn sm danger', type: 'button', text: 'Remove', onClick: async () => { if (await modal({ title: `Remove ${repo.name || 'this repository'}?`, body: h('p', { text: 'Its plugins are removed too.' }), actions: [{ label: 'Cancel', value: false }, { label: 'Remove', value: true, primary: true, danger: true }] })) { svc.removeRepo(repo.url); redraw(); } } })));
      if (q && !shown.length) body.append(h('p', { class: 'mute', text: `No plugin matches “${q}”.` }));
      const ul = h('ul', { class: 'plist prows' });
      for (const p of shown) ul.append(pluginRow(repo, p));
      body.append(h('div', { class: 'plist-box' }, ul));
    }
    return h('div', { class: 'sacc' + (isOpen ? ' open' : ''), 'data-repo': repo.id || repo.url }, head, body);
  }

  function pluginRow(repo, p) {
    const k = `${repo.url}|${p.id}`;
    const isOpen = openPlugins.has(k);
    const master = svc.masterOn();
    const sw = h('input', { class: 'tg', type: 'checkbox', checked: !!(p.enabled && master), disabled: !master, 'aria-label': `Enable ${p.name}`, onChange: (e) => enable(repo, [p.id], e.target.checked).then(() => { if (!svc.repos().find((r) => r.url === repo.url)?.plugins.find((x) => x.id === p.id)?.enabled) e.target.checked = false; }) });
    sw.dataset.plugin = p.id;
    const li = h('li', { class: 'prow' + (isOpen ? ' open' : '') },
      h('div', { class: 'prow-h' },
        h('button', { class: 'pname', type: 'button', 'aria-expanded': String(isOpen), onClick: () => { if (openPlugins.has(k)) openPlugins.delete(k); else openPlugins.add(k); redraw(); } },
          h('b', { text: p.name + (p.version ? `  v${p.version}` : '') }), icon('down', 16)),
        sw));
    if (isOpen) {
      const t = tests.get(k);
      const canTest = master && repo.consented && !!sync.getStatus().user;
      const running = tests.has(k) && t === null;
      li.append(h('div', { class: 'pdetail' },
        p.description ? h('p', { class: 'mute', text: p.description }) : null,
        h('p', { class: 'perms', text: p.permissions?.length ? p.permissions.map(describePermission).join('; ') : 'No permissions' }),
        h('p', { class: 'mute', text: p.types?.length ? `For: ${p.types.join(', ')}` : 'For: movies and series' }),
        h('div', { class: 'actions' }, h('button', { class: 'btn sm', type: 'button', 'data-test': p.id, disabled: !canTest || running, text: running ? 'Testing…' : 'Test plugin', onClick: () => runTest(repo, p) })),
        !canTest ? h('p', { class: 'mute', text: !master ? 'Turn on Run plugins and allow this repository to test.' : !sync.getStatus().user ? 'Sign in to Liuying to test.' : 'Allow this repository to test (enable a plugin to be asked).' }) : null,
        t ? h('p', { class: 'ptest ' + (t.ok ? 'pass' : 'fail'), role: 'status', text: t.ok ? `Pass: ${t.streamCount} stream${t.streamCount === 1 ? '' : 's'} in ${(t.elapsedMs / 1000).toFixed(1)}s` : `Failed: ${t.error}` }) : null));
    }
    return li;
  }

  function draw() {
    const master = svc.masterOn();
    const signedIn = !!sync.getStatus().user;
    const list = svc.repos();
    const keyInput = h('input', { class: 'inp', type: 'password', id: 'plug-tmdb', autocomplete: 'off', 'aria-label': 'Metadata API key', placeholder: 'TMDB v3 key or v4 token', value: keyDraft, onInput: (e) => { keyDraft = e.target.value; } });
    append(clear(box), [
      h('p', { class: 'risk', text: RISK_NOTICE }),
      h('p', { class: 'mute', text: 'Add a plugin repository to see which plugins it lists. With Run plugins on, each enabled plugin is downloaded and run in a sandbox when you open a title, and the streams it finds are listed with a plugin badge. A plugin can only reach public web addresses, through the Liuying relay: no settings, accounts, cookies, files or other plugins. It has 20 seconds and 30 web requests per title.' }),
      !signedIn ? h('div', { class: 'state compact', role: 'status' }, h('p', { text: 'Plugins need a Liuying account: their downloads and web requests go through the Liuying relay, which only signed-in users can use.' }), h('a', { class: 'btn sm', href: '/login', text: 'Sign in' })) : null,
      h('div', { class: 'switch' }, h('label', { class: 'tx', for: 'plug-master' }, h('b', { text: 'Run plugins' }), h('span', { text: 'Master switch. Off by default; nothing is downloaded or run while it is off.' })),
        h('input', { class: 'tg', type: 'checkbox', id: 'plug-master', checked: master, onChange: (e) => { svc.setMaster(e.target.checked); redraw(); } })),
      h('form', { class: 'plug-form', onSubmit: (e) => { e.preventDefault(); addRepo(); } }, h('label', { class: 'lbl', for: 'plug-url', text: 'Repository manifest URL' }),
        h('div', { class: 'plug-in' }, urlInput, h('button', { class: 'btn pri', type: 'submit', disabled: adding, text: adding ? 'Fetching…' : 'Add repository' })),
        addError ? h('p', { class: 'perr', role: 'alert', text: addError }) : null),
      list.length ? h('div', { class: 'repos' }, list.map(repoView)) : h('p', { class: 'mute', text: 'No repositories added.' }),
      h('div', { class: 'setrow keyrow' }, h('div', { class: 'tx' }, h('b', { text: 'Metadata API key (TMDB)' }), h('span', { text: 'Optional. Lets plugins that need a TMDB id get one; without it they receive the IMDb id. Stays in this browser: never synced, never given to a plugin, sent only to api.themoviedb.org.' })),
        h('div', { class: 'keyin' }, keyInput, h('div', { class: 'actions' },
          h('button', { class: 'btn sm', type: 'button', text: 'Save', onClick: () => { svc.setTmdbKey(keyInput.value); toast('Key saved in this browser.'); } }),
          h('button', { class: 'btn sm', type: 'button', text: 'Clear', onClick: () => { svc.setTmdbKey(''); keyInput.value = ''; toast('Key removed.'); } })))),
    ]);
  }
  draw();
  box.dataset.ready = '1';
  return box;
}

function hostOfUrl(u) { try { return new URL(u).host; } catch { return u; } }
