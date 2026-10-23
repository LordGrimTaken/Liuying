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
  var hostFetch = g.__hostFetch, hostLog = g.__hostLog, hostSleep = g.__hostSleep, hostResolve = g.__hostResolve, hostReject = g.__hostReject, hostModule = g.__hostModule;
  ['__hostFetch', '__hostLog', '__hostSleep', '__hostResolve', '__hostReject', '__hostModule'].forEach(function (n) { try { delete g[n]; } catch (e) { g[n] = undefined; } });

  function errText(e) {
    if (e === null || e === undefined) return 'Unknown error';
    if (typeof e === 'string') return e;
    var m = e.message !== undefined ? String(e.message) : String(e);
    return (e.name && e.name !== 'Error' ? e.name + ': ' : '') + m;
  }

  // Only plain ASCII crosses the host boundary: text from the plugin world is \\uXXXX-escaped JSON, messages lose
  // non-ASCII. (Characters outside the BMP are not safe through the engine's UTF-8 <-> Java string bridge.)
  function ascii(s) { return s.replace(/[\u0080-\uffff]/g, function (c) { return '\\u' + ('0000' + c.charCodeAt(0).toString(16)).slice(-4); }); }
  function plain(s) { return String(s).replace(/[^\u0009\u000a\u0020-\u007e]/g, '?'); }

  // ---- small standard-library gaps
  if (!String.prototype.replaceAll) Object.defineProperty(String.prototype, 'replaceAll', { configurable: true, writable: true, value: function (a, b) {
    if (a instanceof RegExp) return this.replace(a, b);
    return this.split(String(a)).join(typeof b === 'function' ? b(String(a)) : String(b));
  } });
  if (!Array.prototype.at) Object.defineProperty(Array.prototype, 'at', { configurable: true, writable: true, value: function (i) { i = Math.trunc(i) || 0; if (i < 0) i += this.length; return this[i]; } });
  if (!Object.fromEntries) Object.fromEntries = function (it) { var o = {}; for (var e of it) o[e[0]] = e[1]; return o; };
  if (!Object.hasOwn) Object.hasOwn = function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); };
  if (!Promise.allSettled) Promise.allSettled = function (a) { return Promise.all(Array.from(a, function (p) { return Promise.resolve(p).then(function (value) { return { status: 'fulfilled', value: value }; }, function (reason) { return { status: 'rejected', reason: reason }; }); })); };
  if (!Promise.any) Promise.any = function (a) { return new Promise(function (res, rej) { var n = 0, errs = [], list = Array.from(a); if (!list.length) rej(new Error('All promises were rejected')); list.forEach(function (p, i) { Promise.resolve(p).then(res, function (e) { errs[i] = e; if (++n === list.length) { var x = new Error('All promises were rejected'); x.name = 'AggregateError'; x.errors = errs; rej(x); } }); }); }); };
  g.queueMicrotask = function (fn) { Promise.resolve().then(fn); };

  // what the plugin was last doing, for the message when it ends without an answer
  var diag = { inflight: 0, last: '' };

  // ---- console
  function fmt(a) {
    if (typeof a === 'string') return a;
    try { var s = JSON.stringify(a); return s === undefined ? String(a) : s; } catch (e) { return String(a); }
  }
  var con = {};
  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (lvl) {
    con[lvl] = function () { try { hostLog(lvl, plain(Array.prototype.map.call(arguments, fmt).join(' '))); } catch (e) {} };
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

  // ---- bytes: TextEncoder / TextDecoder / Buffer (a subset: utf8, hex, base64, base64url, latin1)
  function utf8Encode(str) {
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        var d = str.charCodeAt(i + 1);
        if (d >= 0xdc00 && d <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i++; }
      }
      if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return out;
  }
  function utf8Decode(b, start, end) {
    var s = '', i = start === undefined ? 0 : start, n = end === undefined ? b.length : end;
    while (i < n) {
      var c = b[i++], cp = 0xfffd, extra = 0;
      if (c < 0x80) cp = c;
      else if (c >= 0xc2 && c < 0xe0) { cp = c & 31; extra = 1; }
      else if (c >= 0xe0 && c < 0xf0) { cp = c & 15; extra = 2; }
      else if (c >= 0xf0 && c < 0xf5) { cp = c & 7; extra = 3; }
      if (extra) {
        if (i + extra > n) { cp = 0xfffd; i = n; }
        else {
          for (var k = 0; k < extra; k++) {
            var x = b[i];
            if ((x & 0xc0) !== 0x80) { cp = 0xfffd; break; }
            cp = (cp << 6) | (x & 63); i++;
          }
          if (cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) cp = 0xfffd;
        }
      }
      if (cp > 0xffff) { cp -= 0x10000; s += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023)); }
      else s += String.fromCharCode(cp);
    }
    return s;
  }
  function viewBytes(x) {
    if (x instanceof ArrayBuffer) return new Uint8Array(x);
    if (ArrayBuffer.isView(x)) return new Uint8Array(x.buffer, x.byteOffset, x.byteLength);
    throw new TypeError('The argument must be an ArrayBuffer or a typed array');
  }
  function bin(b) { var s = ''; for (var i = 0; i < b.length; i += 8192) s += String.fromCharCode.apply(null, Array.prototype.slice.call(b, i, i + 8192)); return s; }
  g.TextEncoder = class TextEncoder {
    get encoding() { return 'utf-8'; }
    encode(s) { return new Uint8Array(utf8Encode(s === undefined ? '' : String(s))); }
  };
  g.TextDecoder = class TextDecoder {
    constructor(label) {
      var l = String(label === undefined ? 'utf-8' : label).toLowerCase();
      if (l === 'utf-8' || l === 'utf8' || l === 'unicode-1-1-utf-8') this._e = 'utf-8';
      else if (l === 'latin1' || l === 'iso-8859-1' || l === 'ascii' || l === 'us-ascii' || l === 'windows-1252') this._e = 'latin1';
      else throw new RangeError('The "' + label + '" encoding is not supported');
    }
    get encoding() { return this._e; }
    decode(x) {
      if (x === undefined) return '';
      var b = viewBytes(x);
      if (this._e === 'latin1') return bin(b);
      if (b.length >= 3 && b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf) return utf8Decode(b, 3);
      return utf8Decode(b);
    }
  };
  function fromB64(s) { s = String(s).replace(/-/g, '+').replace(/_/g, '/').replace(/[^A-Za-z0-9+\/]/g, ''); while (s.length % 4) s += '='; var r = g.atob(s), o = new Uint8Array(r.length); for (var i = 0; i < r.length; i++) o[i] = r.charCodeAt(i); return o; }
  class Buffer extends Uint8Array {
    static from(v, enc) {
      if (typeof v === 'string') {
        enc = String(enc || 'utf8').toLowerCase();
        var bytes;
        if (enc === 'hex') { bytes = []; for (var i = 0; i + 1 < v.length; i += 2) { var n = parseInt(v.substr(i, 2), 16); if (isNaN(n)) break; bytes.push(n); } }
        else if (enc === 'base64' || enc === 'base64url') bytes = fromB64(v);
        else if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') { bytes = []; for (var j = 0; j < v.length; j++) bytes.push(v.charCodeAt(j) & 255); }
        else bytes = utf8Encode(v);
        return Buffer._of(bytes);
      }
      if (v instanceof ArrayBuffer) return new Buffer(v);
      if (ArrayBuffer.isView(v) || Array.isArray(v)) return Buffer._of(Array.prototype.slice.call(ArrayBuffer.isView(v) ? viewBytes(v) : v));
      throw new TypeError('The first argument must be a string, array or buffer');
    }
    static _of(bytes) { var b = new Buffer(bytes.length); for (var i = 0; i < bytes.length; i++) b[i] = bytes[i] & 255; return b; }
    static alloc(n, fill) { var b = new Buffer(n); if (fill) b.fill(typeof fill === 'string' ? fill.charCodeAt(0) : fill); return b; }
    static isBuffer(x) { return x instanceof Buffer; }
    static byteLength(v, enc) { return typeof v === 'string' ? Buffer.from(v, enc).length : viewBytes(v).length; }
    static concat(list) { var all = []; list.forEach(function (x) { all = all.concat(Array.prototype.slice.call(viewBytes(x))); }); return Buffer._of(all); }
    toString(enc, start, end) {
      enc = String(enc || 'utf8').toLowerCase();
      var b = (start !== undefined || end !== undefined) ? this.subarray(start || 0, end === undefined ? this.length : end) : this;
      if (enc === 'hex') { var h = ''; for (var i = 0; i < b.length; i++) h += (b[i] < 16 ? '0' : '') + b[i].toString(16); return h; }
      if (enc === 'base64') return g.btoa(bin(b));
      if (enc === 'base64url') return g.btoa(bin(b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
      if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') return bin(b);
      return utf8Decode(b);
    }
    toJSON() { return { type: 'Buffer', data: Array.prototype.slice.call(this) }; }
    equals(o) { if (this.length !== o.length) return false; for (var i = 0; i < this.length; i++) if (this[i] !== o[i]) return false; return true; }
    slice(a, b) { return this.subarray(a, b); }
  }
  g.Buffer = Buffer;

  // ---- AbortController / AbortSignal
  function abortError(reason) { if (reason !== undefined) return reason; var e = new Error('This operation was aborted'); e.name = 'AbortError'; return e; }
  class AbortSignal {
    constructor() { this.aborted = false; this.reason = undefined; this.onabort = null; this._l = []; }
    addEventListener(t, f) { if (t === 'abort' && typeof f === 'function') this._l.push(f); }
    removeEventListener(t, f) { this._l = this._l.filter(function (x) { return x !== f; }); }
    throwIfAborted() { if (this.aborted) throw this.reason; }
    _abort(reason) {
      if (this.aborted) return;
      this.aborted = true; this.reason = abortError(reason);
      var ev = { type: 'abort', target: this };
      if (typeof this.onabort === 'function') { try { this.onabort(ev); } catch (e) {} }
      this._l.slice().forEach(function (f) { try { f(ev); } catch (e) {} });
    }
    static abort(reason) { var s = new AbortSignal(); s._abort(reason); return s; }
    static timeout(ms) { var s = new AbortSignal(); g.setTimeout(function () { var e = new Error('The operation timed out'); e.name = 'TimeoutError'; s._abort(e); }, ms); return s; }
  }
  class AbortController {
    constructor() { this.signal = new AbortSignal(); }
    abort(reason) { this.signal._abort(reason); }
  }
  g.AbortSignal = AbortSignal; g.AbortController = AbortController;

  // ---- Headers
  class Headers {
    constructor(init) {
      this._m = {};
      if (!init) return;
      if (Array.isArray(init)) init.forEach(function (p) { this.append(p[0], p[1]); }, this);
      else if (typeof init.forEach === 'function' && typeof init.get === 'function') init.forEach(function (v, k) { this.append(k, v); }, this);
      else Object.keys(init).forEach(function (k) { this.append(k, init[k]); }, this);
    }
    append(k, v) { k = String(k).toLowerCase(); v = String(v); this._m[k] = this._m[k] === undefined ? v : this._m[k] + ', ' + v; }
    set(k, v) { this._m[String(k).toLowerCase()] = String(v); }
    get(k) { var v = this._m[String(k).toLowerCase()]; return v === undefined ? null : v; }
    has(k) { return this._m[String(k).toLowerCase()] !== undefined; }
    delete(k) { delete this._m[String(k).toLowerCase()]; }
    forEach(fn, thisArg) { Object.keys(this._m).forEach(function (k) { fn.call(thisArg, this._m[k], k, this); }, this); }
    keys() { return Object.keys(this._m)[Symbol.iterator](); }
    values() { var m = this._m; return Object.keys(m).map(function (k) { return m[k]; })[Symbol.iterator](); }
    entries() { var m = this._m; return Object.keys(m).map(function (k) { return [k, m[k]]; })[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
  }
  g.Headers = Headers;

  // ---- fetch
  function makeResponse(r) {
    return {
      ok: r.status >= 200 && r.status < 300, status: r.status, statusText: r.statusText || '', url: r.url, redirected: false,
      headers: new Headers(r.headers || {}), bodyUsed: false,
      text: function () { return Promise.resolve(r.body); },
      // The parse happens inside a job: a promise that is rejected while it is being created is reported by the engine
      // as an unhandled rejection and aborts the whole run, even though the plugin awaits (and catches) it a moment later.
      json: function () { return Promise.resolve().then(function () { return JSON.parse(r.body); }); },
      arrayBuffer: function () { return Promise.resolve(new Uint8Array(utf8Encode(r.body)).buffer); },
      clone: function () { return makeResponse(r); }
    };
  }
  function hostOf(u) { var m = /^[a-z][a-z0-9+.\-]*:\/\/([^\/?#:]*)/i.exec(u); return m ? m[1] : 'the web'; }
  g.fetch = function (url, opts) {
    opts = opts || {};
    var sig = opts.signal;
    if (sig && sig.aborted) return Promise.reject(abortError(sig.reason));
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
    var target = String(url instanceof URL ? url.href : (url && url.url ? url.url : url));
    var spec = ascii(JSON.stringify({ url: target, method: opts.method ? String(opts.method) : 'GET', headers: headers, body: body }));
    var host = hostOf(target);
    diag.inflight++;
    var p = hostFetch(spec).then(function (raw) {
      diag.inflight--;
      var r = JSON.parse(raw);
      if (r.error) { diag.last = host + ' failed (' + plain(r.error).slice(0, 80) + ')'; throw new TypeError('fetch failed: ' + r.error); }
      diag.last = host + ' answered HTTP ' + r.status;
      return makeResponse(r);
    }, function (e) { diag.inflight--; diag.last = host + ' failed'; throw e; });
    if (!sig) return p;
    return new Promise(function (resolve, reject) {
      sig.addEventListener('abort', function () { reject(abortError(sig.reason)); });
      p.then(resolve, reject);
    });
  };

  // ---- timers
  var timers = {}, nextTimer = 1, liveTimers = 0;
  g.setTimeout = function (fn, ms) {
    var id = nextTimer++, args = Array.prototype.slice.call(arguments, 2);
    timers[id] = true; liveTimers++;
    hostSleep(Math.max(0, +ms || 0)).then(function () { if (timers[id]) { delete timers[id]; liveTimers--; fn.apply(undefined, args); } });
    return id;
  };
  g.clearTimeout = function (id) { if (timers[id]) { delete timers[id]; liveTimers--; } };

  // ---- CommonJS-ish surface
  g.module = { exports: {} };
  g.exports = g.module.exports;
  g.SCRAPER_SETTINGS = {};
  g.navigator = { userAgent: 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Liuying-Plugin', language: 'en-US', languages: ['en-US'], platform: 'Linux' };
  var loaded = {};
  g.require = function (name) {
    name = String(name);
    if (name === 'buffer') return { Buffer: Buffer };
    if (loaded[name]) return loaded[name].exports;
    var code = null;
    try { code = hostModule(name); } catch (e) {}
    if (typeof code !== 'string' || !code) throw new Error("require('" + plain(name) + "') is not available in the Liuying plugin sandbox");
    var m = { exports: {} };
    loaded[name] = m;
    try { (0, g.eval)('(function (module, exports, require) {' + code + '\n})')(m, m.exports, g.require); } catch (e) { delete loaded[name]; throw e; }
    return m.exports;
  };
  g.global = g; g.self = g; g.window = g;

  // ---- contract: the host starts the plugin through this one function
  function done(v) {
    var s;
    try { s = JSON.stringify(v === undefined ? null : v); } catch (e) { hostReject(plain('The result could not be read: ' + errText(e))); return; }
    hostResolve(ascii(s === undefined ? 'null' : s));
  }
  Object.defineProperty(g, '__liuyingInvoke', {
    configurable: true, enumerable: false, writable: true,
    value: function (tmdbId, mediaType, season, episode) {
      var ex = g.module && g.module.exports;
      var fn = (ex && ex.getStreams) || (g.exports && g.exports.getStreams);
      if (typeof fn !== 'function') { hostReject('The plugin does not export getStreams().'); return; }
      var p;
      try { p = fn(tmdbId, mediaType, season, episode); } catch (e) { hostReject(plain(errText(e)).slice(0, 300)); return; }
      Promise.resolve(p).then(done, function (e) { hostReject(plain(errText(e)).slice(0, 300)); });
    }
  });
  // requests in flight + timers waiting: while this is above zero the plugin may still answer
  Object.defineProperty(g, '__liuyingBusy', {
    configurable: true, enumerable: false, writable: true,
    value: function () { return diag.inflight + liveTimers; }
  });
  // read by the host when the run ended and neither hostResolve nor hostReject was called
  Object.defineProperty(g, '__liuyingDiag', {
    configurable: true, enumerable: false, writable: true,
    value: function () {
      return 'The plugin stopped without giving an answer' +
        (diag.inflight > 0 ? ' while still waiting for ' + diag.inflight + ' web request' + (diag.inflight > 1 ? 's' : '') : '') +
        (diag.last ? ' (last request: ' + diag.last + ')' : '') + '.';
    }
  });
})(globalThis);
"""
}
