(function () {
  'use strict';
  var page = document.body.getAttribute('data-page');
  var USER_RE = /^[A-Za-z0-9_]{3,24}$/;
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
      if (username.value && !USER_RE.test(username.value)) fieldError('username', 'Use 3 to 24 letters, numbers or underscores.');
    });

    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      clearError();
      var u = username.value.trim(), p = pw.value, first = null;
      ['username', 'password', 'confirm'].forEach(function (id) { fieldError(id, ''); });
      if (!USER_RE.test(u)) { fieldError('username', 'Use 3 to 24 letters, numbers or underscores.'); first = first || username; }
      if (page === 'signup') {
        if (p.length < 8 || p.length > 128) { fieldError('password', 'Use between 8 and 128 characters.'); first = first || pw; }
        if (confirmEl.value !== p) { fieldError('confirm', 'The passwords do not match.'); first = first || confirmEl; }
      } else if (!p) { fieldError('password', 'Enter your password.'); first = first || pw; }
      if (first) { showError('Please fix the highlighted fields.'); first.focus(); return; }

      busy(submit, true);
      api('POST', '/api/auth/' + page, { username: u, password: p }).then(function (r) {
        if (r.res.status === 200 || r.res.status === 201) { location.assign('/account'); return; }
        busy(submit, false);
        var code = r.data && r.data.error, msg = friendly(r.res, r.data);
        if (code === 'invalid_username' || code === 'username_taken') { fieldError('username', msg); username.focus(); }
        else if (code === 'invalid_password') { fieldError('password', msg); pw.focus(); }
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
      loading.hidden = true; card.hidden = false;
      document.title = u.username + ' | Liuying';
      document.getElementById('h').focus && document.getElementById('h').setAttribute('tabindex', '-1');
    }).catch(function () { loading.textContent = NET; });
    out.addEventListener('click', function () {
      clearError(); busy(out, true);
      api('POST', '/api/auth/logout').then(function (r) {
        if (r.res.status === 204 || r.res.status === 200 || r.res.status === 401) location.assign('/login');
        else { busy(out, false); showError(friendly(r.res, r.data)); }
      }).catch(function () { busy(out, false); showError(NET); });
    });
  }
})();
