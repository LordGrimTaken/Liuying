(function () {
  'use strict';
  var page = document.body.getAttribute('data-page');
  var USER_RE = /^[\p{L}\p{N}_][\p{L}\p{N}\p{M}_]*$/u;
  var USER_MSG = 'Use 2 to 24 characters: letters in any language, numbers or underscores (no spaces).';
  function userOk(v) {
    var n = String(v).normalize('NFKC'), len = Array.from(n).length;
    return len >= 2 && len <= 24 && USER_RE.test(n);
  }
  var form = document.getElementById('auth-form');
  var errBox = document.getElementById('form-error');

  function api(method, path, body) {
    var opts = { method: method, credentials: 'same-origin', headers: { Accept: 'application/json' } };
    if (body) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    return fetch(path, opts).then(function (res) {
      if (res.status === 204) return { res: res, data: null };
      return res.json().catch(function () { return null; }).then(function (data) { return { res: res, data: data }; });
    });
  }

  function showError(msg) {
    if (!errBox) return;
    errBox.hidden = false;
    errBox.textContent = '';
    // re-insert text on next frame so assistive tech announces repeated errors
    window.requestAnimationFrame(function () { errBox.textContent = msg; });
  }
  function clearError() { if (errBox) { errBox.hidden = true; errBox.textContent = ''; } }
  function fieldError(id, msg) {
    var input = document.getElementById(id), p = document.getElementById(id + '-err');
    if (!input || !p) return;
    if (msg) { p.textContent = msg; p.hidden = false; input.setAttribute('aria-invalid', 'true'); }
    else { p.textContent = ''; p.hidden = true; input.removeAttribute('aria-invalid'); }
  }
  function busy(btn, on) {
    btn.disabled = on;
    if (on) btn.setAttribute('aria-busy', 'true'); else btn.removeAttribute('aria-busy');
  }
  function friendly(res, data) {
    if (data && data.message) return data.message;
    if (res.status === 429) {
      var s = parseInt(res.headers.get('Retry-After'), 10);
      return 'Too many attempts. Please wait' + (s ? ' about ' + s + ' seconds' : ' a moment') + ' and try again.';
    }
    return 'Something went wrong on our side. Please try again.';
  }
  var NET = 'We could not reach Liuying. Check your connection and try again.';

  function me() {
    return api('GET', '/api/auth/me').then(function (r) {
      return r.res.status === 200 && r.data && r.data.user ? r.data.user : null;
    });
  }


  // Free-tier hosting sleeps when idle; the first request can take up to a minute. Say so after 3 s.
  function wakeMessage() {
    var el = document.getElementById('wake');
    if (!el) {
      el = document.createElement('p');
      el.id = 'wake'; el.className = 'hint wake'; el.setAttribute('role', 'status'); el.hidden = true;
      var anchor = document.getElementById('form-error');
      if (anchor && anchor.parentNode) anchor.parentNode.insertBefore(el, anchor.nextSibling);
    }
    return el;
  }
  function withWake(promise) {
    var el = wakeMessage();
    var t = setTimeout(function () {
      el.textContent = 'Waking the server. This can take up to a minute after it has been idle. Your sources and shelf in the web player keep working meanwhile.';
      el.hidden = false;
    }, 3000);
    var done = function () { clearTimeout(t); el.hidden = true; el.textContent = ''; };
    promise.then(done, done);
    return promise;
  }

  // Recovery code panel: shown once, with copy/download and a "saved it" gate before continuing.
  function showRecovery(code, opts) {
    opts = opts || {};
    var panel = document.getElementById(opts.panelId || 'rc-panel');
    if (!panel) { location.assign('/account'); return; }
    document.body.classList.add('has-code');
    var codeEl = panel.querySelector('#rc-code'), msg = panel.querySelector('#rc-msg');
    codeEl.textContent = code;
    panel.hidden = false;
    var h2 = panel.querySelector('h2'); if (h2) h2.focus();
    var flash = function (t) { if (msg) { msg.hidden = false; msg.textContent = t; msg.className = 'note ok'; } };
    panel.querySelector('#rc-copy').onclick = function () {
      var done = function () { flash('Copied.'); };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(code).then(done, function () { selectText(codeEl); flash('Press Ctrl+C to copy the selected code.'); });
      else { selectText(codeEl); flash('Press Ctrl+C to copy the selected code.'); }
    };
    panel.querySelector('#rc-dl').onclick = function () {
      var text = 'Liuying recovery code\r\n' + code + '\r\n\r\nUse it with your username at https://www.liuyingapp.com/recover to reset a lost password. Each code works once.\r\n';
      var a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' })); a.download = 'liuying-recovery-code.txt';
      document.body.appendChild(a); a.click(); a.remove();
      flash('Saved to your downloads.');
    };
    var ok = panel.querySelector('#rc-ok'), go = panel.querySelector('#rc-go');
    if (ok && go) ok.onchange = function () { go.setAttribute('aria-disabled', ok.checked ? 'false' : 'true'); if (ok.checked) go.removeAttribute('tabindex'); else go.setAttribute('tabindex', '-1'); };
    if (go) go.setAttribute('tabindex', '-1');
  }
  function selectText(el) {
    var r = document.createRange(); r.selectNodeContents(el);
    var sel = window.getSelection(); sel.removeAllRanges(); sel.addRange(r);
  }
  window.__liuyingAuth = { showRecovery: showRecovery };

  // show/hide password toggles
  Array.prototype.forEach.call(document.querySelectorAll('.reveal-pw'), function (b) {
    b.addEventListener('click', function () {
      var input = document.getElementById(b.getAttribute('aria-controls'));
      var show = input.type === 'password';
      input.type = show ? 'text' : 'password';
      b.textContent = show ? 'Hide' : 'Show';
      b.setAttribute('aria-pressed', show ? 'true' : 'false');
      b.setAttribute('aria-label', (show ? 'Hide ' : 'Show ') + (input.id === 'confirm' ? 'confirm password' : 'password'));
    });
  });

  if (form) {
    me().then(function (u) { if (u) location.replace('/account'); }).catch(function () {});
    var username = document.getElementById('username');
    var pw = document.getElementById('password');
    var confirmEl = document.getElementById('confirm');
    var submit = form.querySelector('.submit');

    [username, pw, confirmEl].forEach(function (el) {
      if (el) el.addEventListener('input', function () { fieldError(el.id, ''); });
    });
    username.addEventListener('blur', function () {
      if (username.value && !userOk(username.value.trim())) fieldError('username', USER_MSG);
    });

    var codeEl = document.getElementById('code');
    var recoveryEl = document.getElementById('recovery');
    if (codeEl) codeEl.addEventListener('input', function () { fieldError('code', ''); });

    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      clearError();
      var u = username.value.trim(), p = pw.value, first = null;
      ['username', 'password', 'confirm', 'code'].forEach(function (id) { fieldError(id, ''); });
      if (!userOk(u)) { fieldError('username', USER_MSG); first = first || username; }
      if (page === 'signup') {
        if (p.length < 8 || p.length > 128) { fieldError('password', 'Use between 8 and 128 characters.'); first = first || pw; }
        if (confirmEl.value !== p) { fieldError('confirm', 'The passwords do not match.'); first = first || confirmEl; }
      } else if (page === 'recover') {
        if (!codeEl.value.replace(/[\s-]/g, '')) { fieldError('code', 'Enter your recovery code.'); first = first || codeEl; }
        if (p.length < 8 || p.length > 128) { fieldError('password', 'Use between 8 and 128 characters.'); first = first || pw; }
      } else if (!p) { fieldError('password', 'Enter your password.'); first = first || pw; }
      if (first) { showError('Please fix the highlighted fields.'); first.focus(); return; }

      busy(submit, true);
      var body = { username: u, password: p };
      var path = '/api/auth/' + page;
      if (page === 'signup') body.recovery = !!(recoveryEl && recoveryEl.checked);
      if (page === 'recover') body = { username: u, recoveryCode: codeEl.value, newPassword: p };
      withWake(api('POST', path, body)).then(function (r) {
        if (r.res.status === 200 || r.res.status === 201) {
          if (r.data && r.data.recoveryCode) { busy(submit, false); showRecovery(r.data.recoveryCode); return; }
          location.assign('/account'); return;
        }
        busy(submit, false);
        var code = r.data && r.data.error, msg = friendly(r.res, r.data);
        if (code === 'invalid_username' || code === 'username_taken') { fieldError('username', msg); username.focus(); }
        else if (code === 'invalid_password') { fieldError('password', msg); pw.focus(); }
        else if (code === 'invalid_recovery') { fieldError('code', msg); codeEl.focus(); }
        showError(msg);
      }).catch(function () { busy(submit, false); showError(NET); });
    });
  }

  if (page === 'account') {
    var card = document.getElementById('acct'), loading = document.getElementById('loading');
    var out = document.getElementById('logout');
    me().then(function (u) {
      if (!u) { location.replace('/login'); return; }
      document.getElementById('who').textContent = u.username;
      document.getElementById('initial').textContent = (Array.from(u.username)[0] || '').toUpperCase();
      loading.hidden = true; card.hidden = false;
      document.title = u.username + ' | Liuying';
      document.getElementById('h').focus && document.getElementById('h').setAttribute('tabindex', '-1');
      initSecurity();
    }).catch(function () { loading.textContent = NET; });
    out.addEventListener('click', function () {
      clearError(); busy(out, true);
      api('POST', '/api/auth/logout', {}).then(function (r) {
        if (r.res.status === 204 || r.res.status === 200 || r.res.status === 401) location.assign('/login');
        else { busy(out, false); showError(friendly(r.res, r.data)); }
      }).catch(function () { busy(out, false); showError(NET); });
    });
  }

  function note(el, msg, ok) {
    el.hidden = !msg; el.textContent = msg || '';
    el.className = 'note ' + (ok ? 'ok' : 'err');
  }
  function when(iso) { try { return new Date(iso).toLocaleString(); } catch (e) { return iso; } }

  function initSecurity() {
    var pwForm = document.getElementById('pw-form');
    if (!pwForm || pwForm.getAttribute('data-ready')) return;
    pwForm.setAttribute('data-ready', '1');
    var pwMsg = document.getElementById('pw-msg'), save = document.getElementById('pw-save');
    pwForm.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var cur = document.getElementById('cur-pw'), nw = document.getElementById('new-pw');
      if (!cur.value) { note(pwMsg, 'Enter your current password.'); cur.focus(); return; }
      if (nw.value.length < 8 || nw.value.length > 128) { note(pwMsg, 'Use between 8 and 128 characters.'); nw.focus(); return; }
      busy(save, true); note(pwMsg, '');
      api('POST', '/api/auth/password', { currentPassword: cur.value, newPassword: nw.value }).then(function (r) {
        busy(save, false);
        if (r.res.status === 200) { cur.value = ''; nw.value = ''; note(pwMsg, 'Password changed. Other devices were signed out.', true); loadSessions(); }
        else note(pwMsg, friendly(r.res, r.data));
      }).catch(function () { busy(save, false); note(pwMsg, NET); });
    });

    var list = document.getElementById('sess-list'), sMsg = document.getElementById('sess-msg');
    function loadSessions() {
      api('GET', '/api/auth/sessions').then(function (r) {
        if (r.res.status !== 200 || !r.data) return;
        list.textContent = '';
        r.data.sessions.forEach(function (s) {
          var li = document.createElement('li'), t = document.createElement('span');
          t.textContent = (s.current ? 'This device' : 'Signed in') + ' - ' + when(s.createdAt);
          li.appendChild(t);
          if (!s.current) {
            var b = document.createElement('button');
            b.type = 'button'; b.textContent = 'Sign out';
            b.setAttribute('aria-label', 'Sign out the session from ' + when(s.createdAt));
            b.addEventListener('click', function () {
              api('DELETE', '/api/auth/sessions/' + s.id).then(loadSessions).catch(function () { note(sMsg, NET); });
            });
            li.appendChild(b);
          }
          list.appendChild(li);
        });
      }).catch(function () { note(sMsg, NET); });
    }
    document.getElementById('sess-det').addEventListener('toggle', function (e) { if (e.target.open) loadSessions(); });
    var others = document.getElementById('sess-others');
    others.addEventListener('click', function () {
      busy(others, true);
      api('POST', '/api/auth/sessions/revoke-others', {}).then(function (r) {
        busy(others, false);
        if (r.res.status === 204) { note(sMsg, 'Other devices were signed out.', true); loadSessions(); }
        else note(sMsg, friendly(r.res, r.data));
      }).catch(function () { busy(others, false); note(sMsg, NET); });
    });

    var recDet = document.getElementById('rec-det');
    if (recDet) {
      var recStatus = document.getElementById('rec-status'), recMsg = document.getElementById('rec-msg'), recGo = document.getElementById('rec-go');
      var loadRec = function () {
        api('GET', '/api/auth/recovery').then(function (r) {
          if (r.res.status !== 200 || !r.data) { recStatus.textContent = 'Could not check your recovery code.'; return; }
          recStatus.textContent = r.data.enabled ? 'A recovery code is set (created ' + when(r.data.createdAt) + '). It is only shown when created, so we cannot show it again.' : 'No recovery code yet. Without one, a lost password cannot be reset.';
        }).catch(function () { recStatus.textContent = 'Could not check your recovery code.'; });
      };
      recDet.addEventListener('toggle', function (e) { if (e.target.open) loadRec(); });
      document.getElementById('rec-form').addEventListener('submit', function (ev) {
        ev.preventDefault();
        var pwEl = document.getElementById('rec-pw');
        if (!pwEl.value) { note(recMsg, 'Enter your current password.'); pwEl.focus(); return; }
        busy(recGo, true); note(recMsg, '');
        api('POST', '/api/auth/recovery', { password: pwEl.value }).then(function (r) {
          busy(recGo, false);
          if (r.res.status === 200 && r.data && r.data.recoveryCode) {
            pwEl.value = '';
            document.getElementById('rec-form').hidden = true;
            document.getElementById('rec-panel').hidden = false;
            var codeNode = document.getElementById('rc-code'); codeNode.textContent = r.data.recoveryCode;
            window.__liuyingAuth.showRecovery(r.data.recoveryCode, { panelId: 'rec-panel' });
            recStatus.textContent = 'New recovery code created. The old one no longer works.';
          } else note(recMsg, friendly(r.res, r.data));
        }).catch(function () { busy(recGo, false); note(recMsg, NET); });
      });
    }

    var delForm = document.getElementById('del-form'), dMsg = document.getElementById('del-msg'), go = document.getElementById('del-go');
    delForm.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var p = document.getElementById('del-pw');
      if (!p.value) { note(dMsg, 'Enter your password to confirm.'); p.focus(); return; }
      if (!window.confirm('Delete your account and all synced data permanently?')) return;
      busy(go, true); note(dMsg, '');
      api('POST', '/api/auth/delete', { password: p.value }).then(function (r) {
        if (r.res.status === 204) { location.assign('/'); return; }
        busy(go, false); note(dMsg, friendly(r.res, r.data));
      }).catch(function () { busy(go, false); note(dMsg, NET); });
    });
  }
})();
