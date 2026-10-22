(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  function mb(n) { return (n / 1048576).toFixed(1) + ' MB'; }
  fetch('/downloads/latest.json', { cache: 'no-cache', headers: { Accept: 'application/json' } })
    .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
    .then(function (m) {
      $('dl-version').textContent = m.versionName + ' (build ' + m.versionCode + ')';
      $('dl-size').textContent = m.sizeBytes ? mb(m.sizeBytes) : '';
      $('dl-sha').textContent = m.sha256 || '';
      var d = m.releasedAt ? new Date(m.releasedAt) : null;
      $('dl-date').textContent = d && !isNaN(d) ? d.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' }) : '';
      if (m.notes) $('dl-notes').textContent = m.notes;
      try {
        var u = new URL(m.apkUrl, location.href);
        if (u.origin === location.origin) $('dl-btn').setAttribute('href', u.pathname);
      } catch (e) { /* keep the static link */ }
    })
    .catch(function () { $('dl-error').hidden = false; });
})();
