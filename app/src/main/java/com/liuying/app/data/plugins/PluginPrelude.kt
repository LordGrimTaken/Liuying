package com.liuying.app.data.plugins

/**
 * JavaScript evaluated before a plugin script. It builds the whole host API (fetch, console, atob/btoa, URL,
 * URLSearchParams, setTimeout, module/exports) on top of five raw host functions and then removes those from the
 * global scope. The plugin runs in a fresh engine that has nothing else: no Android, no files, no app data.
 */
object PluginPrelude {
    const val SOURCE: String = """
(function (g) {
  'use strict';
  var hostFetch = g.__hostFetch, hostLog = g.__hostLog, hostSleep = g.__hostSleep, hostResolve = g.__hostResolve, hostReject = g.__hostReject;
  ['__hostFetch', '__hostLog', '__hostSleep', '__hostResolve', '__hostReject'].forEach(function (n) { try { delete g[n]; } catch (e) { g[n] = undefined; } });

  function errText(e) {
    if (e === null || e === undefined) return 'Unknown error';
    if (typeof e === 'string') return e;
    var m = e.message !== undefined ? String(e.message) : String(e);
    return (e.name && e.name !== 'Error' ? e.name + ': ' : '') + m;
  }

  // ---- console
  function fmt(a) {
    if (typeof a === 'string') return a;
    try { var s = JSON.stringify(a); return s === undefined ? String(a) : s; } catch (e) { return String(a); }
  }
  var con = {};
  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (lvl) {
    con[lvl] = function () { try { hostLog(lvl, Array.prototype.map.call(arguments, fmt).join(' ')); } catch (e) {} };
  });
  g.console = con;

  // ---- base64
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  g.btoa = function (input) {
    var s = String(input), out = '';
    for (var i = 0; i < s.length; i += 3) {
      var a = s.charCodeAt(i), b = i + 1 < s.length ? s.charCodeAt(i + 1) : NaN, c = i + 2 < s.length ? s.charCodeAt(i + 2) : NaN;
      if (a > 255 || b > 255 || c > 255) throw new Error('InvalidCharacterError: btoa only accepts Latin-1 text');
      var n = (a << 16) | ((b || 0) << 8) | (c || 0);
      out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + (isNaN(b) ? '=' : B64[(n >> 6) & 63]) + (isNaN(c) ? '=' : B64[n & 63]);
    }
    return out;
  };
  g.atob = function (input) {
    var s = String(input).replace(/[\t\n\f\r ]+/g, '');
    if (s.length % 4 === 0) s = s.replace(/={1,2}${'$'}/, '');
    if (s.length % 4 === 1 || /[^A-Za-z0-9+\/]/.test(s)) throw new Error('InvalidCharacterError: atob input is not valid base64');
    var out = '', buf = 0, bits = 0;
    for (var i = 0; i < s.length; i++) {
      buf = (buf << 6) | B64.indexOf(s[i]); bits += 6;
      if (bits >= 8) { bits -= 8; out += String.fromCharCode((buf >> bits) & 255); buf &= (1 << bits) - 1; }
    }
    return out;
  };

  // ---- URLSearchParams
  function enc(s) {
    return encodeURIComponent(s).replace(/%20/g, '+').replace(/[!'()~]/g, function (c) { return '%' + c.charCodeAt(0).toString(16).toUpperCase(); });
  }
  function dec(s) { s = s.replace(/\+/g, ' '); try { return decodeURIComponent(s); } catch (e) { return s; } }
  class URLSearchParams {
    constructor(init) {
      this._l = [];
      if (init === undefined || init === null) return;
      if (typeof init === 'string') {
        if (init.charAt(0) === '?') init = init.slice(1);
        init.split('&').forEach(function (p) {
          if (!p) return;
          var i = p.indexOf('=');
          this._l.push(i < 0 ? [dec(p), ''] : [dec(p.slice(0, i)), dec(p.slice(i + 1))]);
        }, this);
      } else if (init instanceof URLSearchParams) {
        this._l = init._l.map(function (x) { return [x[0], x[1]]; });
      } else if (Array.isArray(init)) {
        init.forEach(function (x) { this._l.push([String(x[0]), String(x[1])]); }, this);
      } else if (typeof init === 'object') {
        Object.keys(init).forEach(function (k) { this._l.push([k, String(init[k])]); }, this);
      }
    }
    append(k, v) { this._l.push([String(k), String(v)]); }
    delete(k) { k = String(k); this._l = this._l.filter(function (x) { return x[0] !== k; }); }
    get(k) { k = String(k); for (var i = 0; i < this._l.length; i++) if (this._l[i][0] === k) return this._l[i][1]; return null; }
    getAll(k) { k = String(k); return this._l.filter(function (x) { return x[0] === k; }).map(function (x) { return x[1]; }); }
    has(k) { return this.get(k) !== null; }
    set(k, v) {
      k = String(k); v = String(v);
      var found = false, out = [];
      this._l.forEach(function (x) { if (x[0] === k) { if (!found) { found = true; out.push([k, v]); } } else out.push(x); });
      if (!found) out.push([k, v]);
      this._l = out;
    }
    sort() { this._l.sort(function (a, b) { return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0; }); }
    forEach(fn, thisArg) { this._l.forEach(function (x) { fn.call(thisArg, x[1], x[0], this); }, this); }
    keys() { return this._l.map(function (x) { return x[0]; })[Symbol.iterator](); }
    values() { return this._l.map(function (x) { return x[1]; })[Symbol.iterator](); }
    entries() { return this._l.map(function (x) { return [x[0], x[1]]; })[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
    get size() { return this._l.length; }
    toString() { return this._l.map(function (x) { return enc(x[0]) + '=' + enc(x[1]); }).join('&'); }
  }
  g.URLSearchParams = URLSearchParams;

  // ---- URL
  var DEFAULT_PORTS = { 'http:': '80', 'https:': '443', 'ftp:': '21', 'ws:': '80', 'wss:': '443' };
  var RE = /^([A-Za-z][A-Za-z0-9+.\-]*:)\/\/(?:([^:@\/?#]*)(?::([^@\/?#]*))?@)?(\[[^\]]*\]|[^:\/?#]*)(?::(\d*))?([^?#]*)(\?[^#]*)?(#.*)?${'$'}/;
  function normPath(p) {
    var out = [];
    p.split('/').forEach(function (seg, i, a) {
      if (seg === '..') { if (out.length > 1) out.pop(); if (i === a.length - 1) out.push(''); }
      else if (seg === '.') { if (i === a.length - 1) out.push(''); }
      else out.push(seg);
    });
    var r = out.join('/');
    return r.charAt(0) === '/' ? r : '/' + r;
  }
  function resolveRef(ref, base) {
    var b = base;
    if (/^\/\//.test(ref)) return b.protocol + ref;
    if (ref.charAt(0) === '/') return b.origin + ref;
    if (ref.charAt(0) === '?') return b.origin + b.pathname + ref;
    if (ref.charAt(0) === '#') return b.origin + b.pathname + b.search + ref;
    if (ref === '') return b.href;
    var dir = b.pathname.slice(0, b.pathname.lastIndexOf('/') + 1);
    return b.origin + dir + ref;
  }
  class URL {
    constructor(url, base) {
      url = String(url).trim();
      var m = RE.exec(url);
      if (!m) {
        if (base === undefined) throw new TypeError('Invalid URL: ' + url);
        var bu = base instanceof URL ? base : new URL(base);
        m = RE.exec(resolveRef(url, bu));
        if (!m) throw new TypeError('Invalid URL: ' + url);
      }
      this.protocol = m[1].toLowerCase();
      this.username = m[2] || '';
      this.password = m[3] || '';
      this.hostname = m[4].toLowerCase();
      var special = DEFAULT_PORTS[this.protocol] !== undefined;
      if (special && !this.hostname) throw new TypeError('Invalid URL: ' + url);
      this.port = m[5] && m[5] !== DEFAULT_PORTS[this.protocol] ? m[5] : '';
      this.pathname = special ? normPath(m[6] || '/') : (m[6] || '');
      this.hash = m[8] && m[8] !== '#' ? m[8] : '';
      this._raw = m[7] && m[7] !== '?' ? m[7] : '';
      this.searchParams = new URLSearchParams(this._raw);
      this._sp0 = this.searchParams.toString();
    }
    get search() { var s = this.searchParams.toString(); return s === this._sp0 ? this._raw : (s ? '?' + s : ''); }
    set search(v) { this.searchParams = new URLSearchParams(String(v)); this._sp0 = null; }
    get host() { return this.hostname + (this.port ? ':' + this.port : ''); }
    get origin() { return DEFAULT_PORTS[this.protocol] !== undefined ? this.protocol + '//' + this.host : 'null'; }
    get href() {
      var auth = this.username ? this.username + (this.password ? ':' + this.password : '') + '@' : '';
      return this.protocol + '//' + auth + this.host + this.pathname + this.search + this.hash;
    }
    toString() { return this.href; }
    toJSON() { return this.href; }
  }
  g.URL = URL;

  // ---- fetch
  function makeResponse(r) {
    var h = r.headers || {};
    var hdr = {
      get: function (n) { var v = h[String(n).toLowerCase()]; return v === undefined ? null : v; },
      has: function (n) { return h[String(n).toLowerCase()] !== undefined; },
      forEach: function (fn) { Object.keys(h).forEach(function (k) { fn(h[k], k); }); },
      entries: function () { return Object.keys(h).map(function (k) { return [k, h[k]]; })[Symbol.iterator](); },
      keys: function () { return Object.keys(h)[Symbol.iterator](); },
      values: function () { return Object.keys(h).map(function (k) { return h[k]; })[Symbol.iterator](); }
    };
    var res = {
      ok: r.status >= 200 && r.status < 300, status: r.status, statusText: r.statusText || '', url: r.url, headers: hdr,
      text: function () { return Promise.resolve(r.body); },
      json: function () { try { return Promise.resolve(JSON.parse(r.body)); } catch (e) { return Promise.reject(e); } },
      clone: function () { return makeResponse(r); }
    };
    return res;
  }
  g.fetch = function (url, opts) {
    opts = opts || {};
    var headers = {}, src = opts.headers;
    if (src) {
      if (Array.isArray(src)) src.forEach(function (p) { headers[String(p[0])] = String(p[1]); });
      else if (typeof src.forEach === 'function' && typeof src.get === 'function') src.forEach(function (v, k) { headers[String(k)] = String(v); });
      else Object.keys(src).forEach(function (k) { headers[k] = String(src[k]); });
    }
    var body = opts.body;
    if (body !== undefined && body !== null) {
      if (body instanceof URLSearchParams) {
        body = body.toString();
        var has = Object.keys(headers).some(function (k) { return k.toLowerCase() === 'content-type'; });
        if (!has) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      } else if (typeof body !== 'string') body = String(body);
    } else body = null;
    var spec = JSON.stringify({ url: String(url instanceof URL ? url.href : url), method: opts.method ? String(opts.method) : 'GET', headers: headers, body: body });
    return hostFetch(spec).then(function (raw) {
      var r = JSON.parse(raw);
      if (r.error) throw new TypeError('fetch failed: ' + r.error);
      return makeResponse(r);
    });
  };

  // ---- timers
  var timers = {}, nextTimer = 1;
  g.setTimeout = function (fn, ms) {
    var id = nextTimer++, args = Array.prototype.slice.call(arguments, 2);
    timers[id] = true;
    hostSleep(Math.max(0, +ms || 0)).then(function () { if (timers[id]) { delete timers[id]; fn.apply(undefined, args); } });
    return id;
  };
  g.clearTimeout = function (id) { delete timers[id]; };

  // ---- CommonJS-ish surface
  g.module = { exports: {} };
  g.exports = g.module.exports;
  g.require = function (name) { throw new Error("require('" + name + "') is not available in the Liuying plugin sandbox"); };
  g.global = g; g.self = g; g.window = g;

  // ---- contract: the host starts the plugin through this one function
  Object.defineProperty(g, '__liuyingInvoke', {
    configurable: true, enumerable: false, writable: true,
    value: function (tmdbId, mediaType, season, episode) {
      var ex = g.module && g.module.exports;
      var fn = (ex && ex.getStreams) || (g.exports && g.exports.getStreams);
      if (typeof fn !== 'function') { hostReject('The plugin does not export getStreams().'); return; }
      var p;
      try { p = fn(tmdbId, mediaType, season, episode); } catch (e) { hostReject(errText(e)); return; }
      Promise.resolve(p).then(function (v) {
        var s;
        try { s = JSON.stringify(v === undefined ? null : v); } catch (e) { hostReject('The result could not be read: ' + errText(e)); return; }
        hostResolve(s === undefined ? 'null' : s);
      }, function (e) { hostReject(errText(e)); });
    }
  });
})(globalThis);
"""
}
