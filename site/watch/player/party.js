// Watch party for the web player.
//
// One host drives playback; everyone else polls the room every two seconds and follows. The room state
// lives in the Worker/D1 (see docs/watch-party.md); no media bytes are relayed, only position and play state.
import { h } from '../ui/dom.js';

const POLL_MS = 2000;
const DRIFT_MS = 2000;
const PEOPLE = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c.6-3 2.8-4.6 5.5-4.6s4.9 1.6 5.5 4.6"/><circle cx="17.5" cy="9.5" r="2.4"/><path d="M15.6 14.6c2.4.1 4.2 1.5 4.8 4.1"/></svg>';

export function attachParty({ video, getMedia, toast }) {
  let room = null;          // { code, host, rev, state, members }
  let pollTimer = null;
  let pushTimer = null;
  let applying = false;
  let lastPush = 0;
  let destroyed = false;
  let dialog = null;

  const shareUrl = (code) => {
    const u = new URL(location.href);
    u.searchParams.set('party', code);
    return u.toString();
  };

  const api = async (method, path, body) => {
    const res = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: body === undefined ? {} : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
    });
    if (res.status === 401) { const e = new Error('unauthenticated'); e.code = 401; throw e; }
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) { const e = new Error(data?.error || `http_${res.status}`); e.code = res.status; throw e; }
    return data;
  };

  // ---------- UI ----------
  const fab = h('button', {
    class: 'party-fab', type: 'button', 'aria-label': 'Watch party', title: 'Watch party — start or join',
    onClick: () => toggleDialog(),
  });
  fab.innerHTML = `${PEOPLE}<span class="party-fab-label">Watch party</span>`;

  const badge = h('div', { class: 'party-badge', hidden: true });

  function closeDialog() {
    if (!dialog) return;
    dialog.remove();
    dialog = null;
  }

  function toggleDialog() {
    if (dialog) { closeDialog(); return; }
    renderDialog();
  }

  function renderDialog() {
    if (dialog) dialog.remove();
    const body = h('div', { class: 'party-dialog-body' });

    if (room) {
      const url = shareUrl(room.code);
      body.append(
        h('p', { class: 'party-code', text: room.code }),
        h('p', { class: 'party-hint', text: room.host ? 'You are hosting. Others follow your play, pause and seek.' : 'Following the host. Your player keeps time with theirs.' }),
        h('div', { class: 'party-row' }, [
          h('button', {
            class: 'btn sm', type: 'button', text: 'Copy link',
            onClick: async () => {
              try { await navigator.clipboard.writeText(url); toast('Link copied'); }
              catch { window.prompt('Copy this link', url); }
            },
          }),
          h('button', {
            class: 'btn sm danger', type: 'button', text: room.host ? 'End party' : 'Leave',
            onClick: async () => {
              try { await api('POST', `/api/party/${room.code}/leave`, {}); } catch { /* already gone */ }
              stop('You left the watch party.');
            },
          }),
        ]),
      );
    } else {
      body.append(
        h('p', { class: 'party-hint', text: 'Start a party and share the link, or join with a code.' }),
        h('div', { class: 'party-row' }, [
          h('button', {
            class: 'btn sm pri', type: 'button', text: 'Start',
            onClick: async () => {
              const m = getMedia();
              try {
                const r = await api('POST', '/api/party', {
                  mediaId: m.mediaId, episodeId: m.episodeId ?? null,
                  positionMs: Math.round(video.currentTime * 1000), playing: !video.paused, name: m.name,
                });
                adopt(r, true);
                toast('Watch party started');
              } catch (e) { authMessage(e); }
            },
          }),
        ]),
        h('form', {
          class: 'party-join',
          onSubmit: async (ev) => {
            ev.preventDefault();
            const input = ev.target.querySelector('input');
            const code = (input.value || '').trim().toUpperCase();
            if (!/^[A-Z]{6}$/.test(code)) { toast('A code is 6 letters'); return; }
            try { adopt(await api('POST', `/api/party/${code}/join`, {}), false); toast('Joined'); }
            catch (e) { authMessage(e); }
          },
        }, [
          h('input', { type: 'text', maxlength: '6', placeholder: 'CODE', 'aria-label': 'Party code', autocomplete: 'off', spellcheck: 'false' }),
          h('button', { class: 'btn sm', type: 'submit', text: 'Join' }),
        ]),
      );
    }

    dialog = h('div', { class: 'party-dialog', role: 'dialog', 'aria-label': 'Watch party' }, [
      h('div', { class: 'party-dialog-head' }, [
        h('strong', { text: 'Watch party' }),
        h('button', { class: 'ibtn', type: 'button', 'aria-label': 'Close', text: '✕', onClick: closeDialog }),
      ]),
      body,
    ]);
    document.body.append(dialog);
  }

  function authMessage(e) {
    if (e?.code === 401) toast('Sign in to use watch party');
    else if (e?.code === 404) toast('No such party — check the code');
    else toast('Watch party is unavailable right now');
  }

  // ---------- state ----------
  function adopt(r, host) {
    room = { code: r.code, host: host ?? !!r.host, rev: r.rev, state: r.state, members: r.members ?? [] };
    history.replaceState(null, '', shareUrl(r.code));
    startPolling();
    paintBadge();
    if (!room.host && r.state) applyRemote(r.state);
    renderDialog();
  }

  function stop(message) {
    room = null;
    clearInterval(pollTimer); pollTimer = null;
    clearInterval(pushTimer); pushTimer = null;
    badge.hidden = true;
    const u = new URL(location.href);
    u.searchParams.delete('party');
    history.replaceState(null, '', u.toString());
    closeDialog();
    if (message) toast(message);
  }

  function paintBadge() {
    if (!room) { badge.hidden = true; return; }
    badge.hidden = false;
    badge.textContent = `${room.host ? 'Hosting' : 'Watching'} · ${room.code}`;
  }

  let warnedOtherTitle = false;

  function applyRemote(state) {
    // The room carries one title. If the guest wandered to another one, never seek their player.
    if (state.mediaId && state.mediaId !== getMedia().mediaId) {
      if (!warnedOtherTitle) { warnedOtherTitle = true; toast('The party is watching a different title'); }
      return;
    }
    applying = true;
    try {
      const target = Math.max(0, Number(state.positionMs) || 0);
      if (Math.abs(video.currentTime * 1000 - target) > DRIFT_MS) video.currentTime = target / 1000;
      if (state.playing && video.paused) video.play().catch(() => {});
      if (!state.playing && !video.paused) video.pause();
    } finally {
      setTimeout(() => { applying = false; }, 0);
    }
  }

  function startPolling() {
    clearInterval(pollTimer);
    pollTimer = setInterval(poll, POLL_MS);
    clearInterval(pushTimer);
    pushTimer = setInterval(() => { if (room?.host && !video.paused) push(false); }, POLL_MS);
  }

  async function poll() {
    if (!room || destroyed) return;
    try {
      const r = await api('GET', `/api/party/${room.code}`);
      room.rev = r.rev; room.host = r.host; room.members = r.members;
      if (!r.host) applyRemote(r.state);
      paintBadge();
    } catch (e) {
      if (e?.code === 404) stop('The watch party ended.');
      else if (e?.code === 401) stop('Signed out — watch party stopped.');
    }
  }

  async function push(force) {
    if (!room?.host || applying) return;
    const t = Date.now();
    if (!force && t - lastPush < POLL_MS - 250) return;
    lastPush = t;
    try {
      await api('PUT', `/api/party/${room.code}`, { positionMs: Math.round(video.currentTime * 1000), playing: !video.paused });
    } catch (e) {
      if (e?.code === 404) stop('The watch party ended.');
    }
  }

  // Host publishes intent immediately; guests never write.
  const onPlay = () => { if (room?.host) push(true); };
  const onPause = () => { if (room?.host) push(true); };
  const onSeek = () => { if (room?.host) push(true); };
  video.addEventListener('play', onPlay);
  video.addEventListener('pause', onPause);
  video.addEventListener('seeked', onSeek);

  document.body.append(fab, badge);

  // A ?party=CODE link joins automatically.
  const invited = new URL(location.href).searchParams.get('party');
  if (invited && /^[A-Za-z]{6}$/.test(invited)) {
    const code = invited.toUpperCase();
    api('POST', `/api/party/${code}/join`, {})
      .then((r) => { adopt(r, false); toast(`Joined party ${code}`); })
      .catch((e) => { authMessage(e); });
  }

  return {
    destroy() {
      destroyed = true;
      clearInterval(pollTimer); clearInterval(pushTimer);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('seeked', onSeek);
      closeDialog();
      fab.remove(); badge.remove();
    },
  };
}
