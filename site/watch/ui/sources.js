import { h, icon, clear, toast, confirmDialog } from './dom.js';
import * as store from '../store.js';
import { fetchManifest, describeError } from '../addon/client.js';
import { normalizeManifestUrl, isAdult, isConfigurable, configurationRequired, configureUrl, catalogTitle, normalizeResources, catalogCapabilities, catalogKey } from '../addon/protocol.js';
import { refreshManifests } from '../addon/service.js';
import { imgUrl } from '../util.js';
import { artImg, emptyState } from './components.js';

const KINDS = [['catalog', 'Catalogs'], ['meta', 'Metadata'], ['stream', 'Streams'], ['subtitles', 'Subtitles']];
const hostOf = (u) => { try { return new URL(u).host; } catch { return u; } };

export function resourceBadges(m) {
  const names = new Set(normalizeResources(m).map((r) => r.name));
  if ((m.catalogs || []).length) names.add('catalog');
  return h('div', { class: 'badges' }, KINDS.filter(([k]) => names.has(k)).map(([, l]) => h('span', { class: 'badge', text: l })));
}

export function sources({ query }) {
  const view = document.getElementById('view');
  const page = h('div', { class: 'page' });
  clear(view).append(page);
  const list = h('div', { class: 'srclist' });
  const addBox = h('div', {});
  const expanded = new Set();
  const addBtn = h('button', { class: 'btn pri', type: 'button', onClick: () => openAdd() }, icon('plus', 18), 'Add source');
  page.append(h('div', { class: 'page-h' }, h('h1', { text: 'Sources' }), addBtn),
    h('p', { class: 'lede', text: 'Sources are addons you choose, identified by a manifest link. Liuying does not host or provide any content. You are responsible for the sources you add.' }),
    h('div', { style: { height: '14px' } }), addBox, list);
  const unsub = store.subscribe((k) => { if (k === 'sources' || k === 'settings') draw(); });

  // ---------- add flow ----------
  function openAdd(prefill = '') {
    clear(addBox);
    const input = h('input', { class: 'inp', id: 'src-url', type: 'url', inputmode: 'url', autocomplete: 'off', spellcheck: 'false', placeholder: 'https://example.com/manifest.json', value: prefill, 'aria-describedby': 'src-hint' });
    const msg = h('div', { role: 'alert', 'aria-live': 'assertive' });
    const prev = h('div', {});
    let abort = null;
    const go = async (e) => {
      e?.preventDefault();
      clear(msg); clear(prev);
      let url;
      try { url = normalizeManifestUrl(input.value); } catch (err) { msg.append(h('div', { class: 'state err compact' }, icon('warn', 20), h('div', { class: 'grow', text: err.message }))); return; }
      if (url.startsWith('http:')) {
        msg.append(h('div', { class: 'state err compact' }, icon('warn', 20), h('div', { class: 'grow' }, h('strong', { text: 'Plain HTTP is blocked here' }), h('span', { class: 'mute', text: 'This page can only reach secure (https) sources, and sending your viewing to an unencrypted host is not recommended. Ask the source for an https link.' }))));
        return;
      }
      abort?.abort(); abort = new AbortController();
      const btn = $btn;
      btn.disabled = true;
      msg.append(h('p', { class: 'progress-line', role: 'status' }, h('span', { class: 'spin' }), 'Reading manifest… ', h('button', { class: 'linkbtn', type: 'button', text: 'Cancel', onClick: () => { abort.abort(); clear(msg); btn.disabled = false; } })));
      try {
        const { manifest } = await fetchManifest(url, { signal: abort.signal });
        clear(msg); btn.disabled = false;
        showPreview(url, manifest);
      } catch (err) {
        btn.disabled = false;
        if (err.kind === 'abort') return;
        clear(msg).append(h('div', { class: 'state err compact' }, icon('warn', 20), h('div', { class: 'grow' }, h('strong', { text: "Couldn't add this source" }), h('span', { class: 'mute', text: describeError(err) }),
          err.kind === 'network' ? h('span', { class: 'mute', text: ' If you can open this link in your browser and see JSON, the source simply does not allow web access; use the Android app instead.' }) : null)));
      }
    };
    const $btn = h('button', { class: 'btn pri', type: 'submit' }, 'Continue');
    const paste = navigator.clipboard?.readText ? h('button', { class: 'btn', type: 'button', onClick: async () => { try { input.value = (await navigator.clipboard.readText()).trim(); input.focus(); } catch { toast('Clipboard is not available. Paste with Ctrl+V.'); } } }, 'Paste') : null;
    addBox.append(h('form', { class: 'preview', onSubmit: go, 'aria-label': 'Add a source' },
      h('div', { class: 'field' }, h('label', { for: 'src-url', text: 'Manifest link' }), input,
        h('p', { class: 'hint mute', id: 'src-hint', text: 'Paste the link that ends in /manifest.json. stremio:// links work too.' })),
      h('div', { class: 'actions' }, $btn, paste, h('button', { class: 'btn', type: 'button', text: 'Close', onClick: () => clear(addBox) })), msg, prev));
    function showPreview(url, m) {
      const dupUrl = store.sources().find((s) => s.url === url);
      const dupId = store.sources().find((s) => s.manifest?.id === m.id && s.url !== url);
      const dup = dupUrl || dupId;
      const adult = isAdult(m);
      const ok = h('input', { type: 'checkbox', id: 'adult-ok', class: 'tg' });
      const warns = [];
      if (dup) warns.push(h('li', {}, icon('warn', 18), `Already installed${dupId ? ' (same source id)' : ''}. Installing again replaces its stored manifest and keeps its order and on/off setting.`));
      if (adult) warns.push(h('li', {}, icon('warn', 18), 'This source is flagged as adult content. Confirm below to install.'));
      if (configurationRequired(m)) warns.push(h('li', { class: 'info' }, icon('warn', 18), 'This source needs configuration before it will return results. Open its configure page after installing.'));
      const cats = m.catalogs.slice(0, 6).map((c) => catalogTitle(c));
      const install = () => {
        if (adult && !ok.checked) { toast('Tick the adult content box first.'); ok.focus(); return; }
        if (dupId && !dupUrl) store.removeSource(dupId.url);
        store.addSource(url, m);
        clear(addBox);
        toast(`Installed ${m.name}`, { action: 'Open Today', onAction: () => { location.hash = '#/'; } });
        history.replaceState(null, '', '#/sources');
      };
      clear(prev).append(h('div', { class: 'preview' },
        h('div', { class: 'src', style: { border: '0', padding: '0', background: 'none', margin: '0' } },
          logoEl(m), h('div', {}, h('h3', {}, m.name, h('small', { text: m.version ? `v${m.version}` : '' })), h('p', { class: 'desc', text: m.description || 'No description.' }), h('p', { class: 'host', text: `Host: ${hostOf(url)}` }), resourceBadges(m))),
        h('p', { class: 'mute', style: { margin: '10px 0 0', 'font-size': '13px' }, text: `Types: ${m.types.join(', ') || 'any'}${cats.length ? ' · Catalogs: ' + cats.join(', ') + (m.catalogs.length > 6 ? '…' : '') : ''}` }),
        warns.length ? h('ul', { class: 'warnlist' }, warns) : null,
        adult ? h('div', { class: 'switch' }, h('div', { class: 'tx' }, h('b', { text: 'I understand this is adult content' })), ok) : null,
        h('div', { class: 'actions' }, h('button', { class: 'btn pri', type: 'button', onClick: install, text: dup ? 'Replace' : 'Install' }), h('button', { class: 'btn', type: 'button', text: 'Cancel', onClick: () => clear(prev) }))));
      prev.scrollIntoView?.({ block: 'nearest' });
    }
    input.focus();
  }

  function logoEl(m) {
    const u = imgUrl(m.logo);
    const el = h('div', { class: 'logo', 'aria-hidden': 'true', text: (m.name || '?')[0].toUpperCase() });
    if (u) { const im = h('img', { class: 'logo', src: u, alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' }); im.addEventListener('error', () => im.replaceWith(el)); return im; }
    return el;
  }

  // ---------- list ----------
  function draw() {
    const all = store.sources();
    clear(list);
    if (!all.length) { list.append(emptyState('No sources yet', 'A source is an addon that supplies catalogs, streams or subtitles. Paste its manifest link to add it.', h('button', { class: 'btn pri', type: 'button', text: 'Add source', onClick: () => openAdd() }))); return; }
    list.append(h('p', { class: 'mute', text: `${all.length} source${all.length > 1 ? 's' : ''}. Order sets the priority for streams.` }));
    all.forEach((s, idx) => list.append(srcRow(s, idx, all.length)));
  }

  function srcRow(s, idx, n) {
    const m = s.manifest;
    const bad = s.error && !m ? true : !!s.error;
    const open = expanded.has(s.url);
    const name = m?.name || hostOf(s.url);
    const row = h('article', { class: 'src' + (s.enabled ? '' : ' off'), 'aria-label': name },
      m ? logoEl(m) : h('div', { class: 'logo', 'aria-hidden': 'true', text: '?' }),
      h('div', { style: { 'min-width': '0' } },
        h('h3', {}, name, m?.version ? h('small', { text: `v${m.version}` }) : null),
        h('p', { class: 'desc', text: m?.description || (m ? '' : 'Manifest not loaded yet.') }),
        m ? resourceBadges(m) : null,
        h('div', { style: { 'margin-top': '8px' } }, h('span', { class: `status${!s.enabled ? ' off' : bad ? ' bad' : ''}` }, h('i'), !s.enabled ? 'Disabled' : bad ? `Problem: ${describeError(s.error)}` : 'Ready'),
          m && configurationRequired(m) ? h('span', { class: 'badge warn', style: { 'margin-left': '8px' }, text: 'Needs configuration' }) : null,
          m && isAdult(m) ? h('span', { class: 'badge warn', style: { 'margin-left': '8px' }, text: 'Adult' }) : null)),
      h('div', { class: 'ctl' },
        h('input', { class: 'tg', type: 'checkbox', checked: s.enabled, 'aria-label': `${s.enabled ? 'Disable' : 'Enable'} ${name}`, onChange: (e) => store.updateSource(s.url, { enabled: e.target.checked }, { sync: true }) })),
      h('div', { class: 'acts' },
        h('button', { class: 'btn sm', type: 'button', disabled: idx === 0, 'aria-label': `Move ${name} up`, onClick: () => store.moveSource(s.url, -1) }, icon('up', 16), 'Up'),
        h('button', { class: 'btn sm', type: 'button', disabled: idx === n - 1, 'aria-label': `Move ${name} down`, onClick: () => store.moveSource(s.url, 1) }, icon('down', 16), 'Down'),
        m && isConfigurable(m) ? h('a', { class: 'btn sm', href: configureUrl(s.url), target: '_blank', rel: 'noopener noreferrer' }, icon('external', 16), 'Configure') : null,
        h('button', { class: 'btn sm', type: 'button', onClick: () => refresh(s) }, icon('refresh', 16), 'Refresh'),
        h('button', { class: 'btn sm', type: 'button', 'aria-expanded': String(open), onClick: () => { open ? expanded.delete(s.url) : expanded.add(s.url); draw(); } }, 'Details'),
        h('button', { class: 'btn sm', type: 'button', onClick: () => remove(s, name) }, icon('trash', 16), 'Remove')));
    if (open && m) row.append(detail(s, m));
    return row;
  }

  function detail(s, m) {
    const hidden = store.settings().hiddenCatalogs || {};
    return h('div', { class: 'detail' },
      h('dl', { class: 'facts' },
        h('dt', { text: 'Link' }), h('dd', { text: s.url, style: { 'overflow-wrap': 'anywhere', 'user-select': 'all' } }),
        h('dt', { text: 'Id' }), h('dd', { text: m.id }),
        h('dt', { text: 'Types' }), h('dd', { text: m.types.join(', ') || 'any' }),
        h('dt', { text: 'Last fetch' }), h('dd', { text: s.fetchedAt ? new Date(s.fetchedAt).toLocaleString() : 'never' }),
        s.error ? h('dt', { text: 'Last error' }) : null, s.error ? h('dd', { text: `${describeError(s.error)} (${new Date(s.error.at).toLocaleString()})` }) : null),
      m.catalogs.length ? h('h4', { class: 'lbl', style: { 'margin-top': '14px' }, text: 'Catalogs' }) : null,
      ...m.catalogs.map((c) => {
        const key = catalogKey(s.url, c); const caps = catalogCapabilities(c);
        return h('label', { class: 'switch' }, h('div', { class: 'tx' }, h('b', { text: catalogTitle(c) }), h('span', { text: [caps.search ? 'Searchable' : '', caps.searchRequired || caps.otherRequired.length ? 'Needs input (not shown on Today)' : ''].filter(Boolean).join(' · ') || 'Show on Today' })),
          h('input', { class: 'tg', type: 'checkbox', checked: !hidden[key], 'aria-label': `Show ${catalogTitle(c)} on Today`, onChange: (e) => { const hc = { ...(store.settings().hiddenCatalogs || {}) }; if (e.target.checked) delete hc[key]; else hc[key] = true; store.setSettings({ hiddenCatalogs: hc }); } }));
      }));
  }

  async function refresh(s) {
    toast('Refreshing…', { ms: 1500 });
    try {
      const { manifest } = await fetchManifest(s.url);
      store.updateSource(s.url, { manifest, fetchedAt: Date.now(), error: null });
      toast('Manifest refreshed');
    } catch (e) {
      store.updateSource(s.url, { error: { kind: e.kind, message: e.message, at: Date.now() } });
      toast(describeError(e));
    }
  }
  async function remove(s, name) {
    const ok = await confirmDialog(`Remove ${name}?`, 'Its rows, streams and subtitles disappear. Your shelf, watch progress and history are kept.', 'Remove', true);
    if (!ok) return;
    store.removeSource(s.url);
    toast(`Removed ${name}`);
  }

  draw();
  if (query.add) openAdd(query.url || '');
  return { destroy() { unsub(); } };
}
