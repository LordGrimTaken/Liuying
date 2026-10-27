/* Liuying plugin engine. Runs inside a dedicated Worker that lives in a sandboxed iframe (sandbox="allow-scripts", opaque
 * origin, CSP default-src 'none'), started by plugins/sandbox.htm. A browser port of the Android PluginPrelude.kt: the same host
 * API (fetch, console, atob/btoa, URL, URLSearchParams, setTimeout, Buffer, TextEncoder/TextDecoder, AbortController, Headers,
 * queueMicrotask, module/exports/require) and the same contract (module.exports.getStreams(tmdbId, mediaType, season, episode)).
 *
 * The plugin has no way out: the worker inherits the iframe's CSP (no connect-src), so fetch/XHR/WebSocket cannot reach a network
 * even if the shims below were bypassed. Its `fetch` posts a request to the page that owns the iframe, which validates it and sends it
 * through the Liuying relay. Nothing here is trusted by the page: every message is validated again on the other side.
 *
 * Messages in (from the page):  {type:'run', script, tmdbId, mediaType, season, episode, modules}   {type:'fetch-result', id, ok, res|error}
 * Messages out (to the page):   {type:'fetch', id, spec}   {type:'result', json}   {type:'reject', message}
 */
(function (g) {
  'use strict';
  var rawPost = g.postMessage.bind(g);
  var nativeSetTimeout = g.setTimeout.bind(g);
  var NativeURL = g.URL, NativeURLSearchParams = g.URLSearchParams;
  var started = false;

  function errText(e) {
    if (e === null || e === undefined) return 'Unknown error';
    if (typeof e === 'string') return e;
    var m = e.message !== undefined ? String(e.message) : String(e);
    return (e.name && e.name !== 'Error' ? e.name + ': ' : '') + m;
  }
  function plain(s) { return String(s).replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, '?'); }

  // ---- small standard-library gaps (older browsers)
  if (!String.prototype.replaceAll) Object.defineProperty(String.prototype, 'replaceAll', { configurable: true, writable: true, value: function (a, b) {
    if (a instanceof RegExp) return this.replace(a, b);
    return this.split(String(a)).join(typeof b === 'function' ? b(String(a)) : String(b));
  } });
  if (!Array.prototype.at) Object.defineProperty(Array.prototype, 'at', { configurable: true, writable: true, value: function (i) { i = Math.trunc(i) || 0; if (i < 0) i += this.length; return this[i]; } });
  if (!Object.fromEntries) Object.fromEntries = function (it) { var o = {}; for (var e of it) o[e[0]] = e[1]; return o; };
  if (!Object.hasOwn) Object.hasOwn = function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); };
  if (!Promise.allSettled) Promise.allSettled = function (a) { return Promise.all(Array.from(a, function (p) { return Promise.resolve(p).then(function (value) { return { status: 'fulfilled', value: value }; }, function (reason) { return { status: 'rejected', reason: reason }; }); })); };
  if (!Promise.any) Promise.any = function (a) { return new Promise(function (res, rej) { var n = 0, errs = [], list = Array.from(a); if (!list.length) rej(new Error('All promises were rejected')); list.forEach(function (p, i) { Promise.resolve(p).then(res, function (e) { errs[i] = e; if (++n === list.length) { var x = new Error('All promises were rejected'); x.name = 'AggregateError'; x.errors = errs; rej(x); } }); }); }); };
  if (typeof g.queueMicrotask !== 'function') g.queueMicrotask = function (fn) { Promise.resolve().then(fn); };

  // what the plugin was last doing, for the message when it ends without an answer
  var diag = { inflight: 0, last: '' };

  // ---- console: output is discarded; nothing leaves the sandbox
  var con = {};
  ['log', 'info', 'warn', 'error', 'debug', 'trace', 'table', 'dir', 'group', 'groupEnd', 'time', 'timeEnd'].forEach(function (lvl) { con[lvl] = function () {}; });
  g.console = con;

  // ---- bytes: Buffer (a subset: utf8, hex, base64, base64url, latin1). TextEncoder/TextDecoder/atob/btoa are the browser's own.
  function utf8Encode(str) { return Array.prototype.slice.call(new TextEncoder().encode(str)); }
  function utf8Decode(b) { return new TextDecoder('utf-8').decode(b instanceof Uint8Array ? b : new Uint8Array(b)); }
  function viewBytes(x) {
    if (x instanceof ArrayBuffer) return new Uint8Array(x);
    if (ArrayBuffer.isView(x)) return new Uint8Array(x.buffer, x.byteOffset, x.byteLength);
    throw new TypeError('The argument must be an ArrayBuffer or a typed array');
  }
  function bin(b) { var s = ''; for (var i = 0; i < b.length; i += 8192) s += String.fromCharCode.apply(null, Array.prototype.slice.call(b, i, i + 8192)); return s; }
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
      if (enc === 'base64url') return g.btoa(bin(b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
      if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') return bin(b);
      return utf8Decode(b);
    }
    toJSON() { return { type: 'Buffer', data: Array.prototype.slice.call(this) }; }
    equals(o) { if (this.length !== o.length) return false; for (var i = 0; i < this.length; i++) if (this[i] !== o[i]) return false; return true; }
    slice(a, b) { return this.subarray(a, b); }
  }
  g.Buffer = Buffer;

  // ---- Headers (own implementation: browsers refuse some header names on their own Headers objects)
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

  // ---- AbortController / AbortSignal (own: AbortSignal.timeout must use the counted timers below)
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

  // ---- fetch: a request is posted to the page, which relays it; the answer comes back as a message
  var pending = {}, nextId = 1;
  function makeResponse(r) {
    var bytesCache = null;
    function bytes() {
      if (bytesCache) return bytesCache;
      bytesCache = r.binary ? fromB64(r.body) : new Uint8Array(utf8Encode(r.body));
      return bytesCache;
    }
    function text() { return r.binary ? utf8Decode(bytes()) : r.body; }
    return {
      ok: r.status >= 200 && r.status < 300, status: r.status, statusText: r.statusText || '', url: r.url, redirected: r.url !== r.requestUrl, type: 'basic', bodyUsed: false,
      headers: new Headers(r.headers || {}),
      text: function () { return Promise.resolve(text()); },
      // parsed inside a job so a rejection is always handled by the plugin's own await/catch
      json: function () { return Promise.resolve().then(function () { return JSON.parse(text()); }); },
      arrayBuffer: function () { var b = bytes(); return Promise.resolve(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength)); },
      clone: function () { return makeResponse(r); }
    };
  }
  function hostOf(u) { var m = /^[a-z][a-z0-9+.\-]*:\/\/([^\/?#:]*)/i.exec(u); return m ? m[1] : 'the web'; }
  g.fetch = function (url, opts) {
    opts = opts || {};
    var sig = opts.signal;
    if (sig && sig.aborted) return Promise.reject(abortError(sig.reason));
    var headers = {}, src = opts.headers || (url && typeof url === 'object' && !(url instanceof NativeURL) ? url.headers : undefined);
    if (src) {
      if (Array.isArray(src)) src.forEach(function (p) { headers[String(p[0])] = String(p[1]); });
      else if (typeof src.forEach === 'function' && typeof src.get === 'function') src.forEach(function (v, k) { headers[String(k)] = String(v); });
      else Object.keys(src).forEach(function (k) { headers[k] = String(src[k]); });
    }
    var body = opts.body;
    if (body !== undefined && body !== null) {
      if (body instanceof NativeURLSearchParams) {
        body = body.toString();
        var has = Object.keys(headers).some(function (k) { return k.toLowerCase() === 'content-type'; });
        if (!has) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      } else if (ArrayBuffer.isView(body) || body instanceof ArrayBuffer) body = utf8Decode(viewBytes(body));
      else if (typeof body !== 'string') body = String(body);
    } else body = null;
    var target = String(url instanceof NativeURL ? url.href : (url && url.url ? url.url : url));
    var spec = { url: target, method: opts.method ? String(opts.method) : (url && url.method) || 'GET', headers: headers, body: body };
    var host = hostOf(target);
    var id = nextId++;
    diag.inflight++;
    var p = new Promise(function (resolve, reject) {
      pending[id] = function (m) {
        diag.inflight--;
        if (!m.ok) { diag.last = host + ' failed (' + plain(m.error).slice(0, 80) + ')'; reject(new TypeError('fetch failed: ' + m.error)); return; }
        var r = m.res; r.requestUrl = target;
        diag.last = host + ' answered HTTP ' + r.status;
        resolve(makeResponse(r));
      };
    });
    rawPost({ type: 'fetch', id: id, spec: spec });
    if (!sig) return p;
    return new Promise(function (resolve, reject) {
      sig.addEventListener('abort', function () { reject(abortError(sig.reason)); });
      p.then(resolve, reject);
    });
  };

  // ---- timers: at most 5 s each (the run has 20 s in total); no setInterval; only functions, never strings
  var timers = {}, nextTimer = 1, liveTimers = 0;
  g.setTimeout = function (fn, ms) {
    if (typeof fn !== 'function') return 0;
    var id = nextTimer++, args = Array.prototype.slice.call(arguments, 2);
    timers[id] = true; liveTimers++;
    nativeSetTimeout(function () { if (timers[id]) { delete timers[id]; liveTimers--; try { fn.apply(undefined, args); } finally { settleSoon(); } } }, Math.min(5000, Math.max(0, +ms || 0)));
    return id;
  };
  g.clearTimeout = function (id) { if (timers[id]) { delete timers[id]; liveTimers--; settleSoon(); } };

  // ---- CommonJS-ish surface
  g.module = { exports: {} };
  g.exports = g.module.exports;
  g.SCRAPER_SETTINGS = {};
  Object.defineProperty(g, 'navigator', { configurable: true, writable: true, value: { userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Liuying-Plugin', language: 'en-US', languages: ['en-US'], platform: 'Linux' } });
  var moduleSources = {}, loaded = Object.create(null);
  function plainName(n) { return plain(n).slice(0, 80); }
  g.require = function (name) {
    name = String(name);
    if (name === 'buffer') return { Buffer: Buffer };
    if (loaded[name]) return loaded[name].exports;
    var code = Object.prototype.hasOwnProperty.call(moduleSources, name) ? moduleSources[name] : null;
    if (typeof code !== 'string' || !code) throw new Error("require('" + plainName(name) + "') is not available in the Liuying plugin sandbox");
    var m = { exports: {} };
    loaded[name] = m;
    try { (0, g.eval)('(function (module, exports, require) {' + code + '\n})')(m, m.exports, g.require); } catch (e) { delete loaded[name]; throw e; }
    return m.exports;
  };
  // `self` is already the global (a read-only accessor in workers)
  g.global = g; g.window = g;

  // ---- take the ways out away from the plugin (defence in depth: the CSP already blocks every network path)
  ['XMLHttpRequest', 'WebSocket', 'EventSource', 'importScripts', 'indexedDB', 'caches', 'Worker', 'SharedWorker', 'WebTransport', 'BroadcastChannel',
    'setInterval', 'clearInterval', 'postMessage', 'onmessage', 'close', 'FileReaderSync', 'Notification', 'WebAssembly'].forEach(function (n) {
    try { Object.defineProperty(g, n, { configurable: true, writable: true, value: undefined }); } catch (e) { try { g[n] = undefined; } catch (e2) {} }
  });
  try { Object.defineProperty(g.navigator, 'sendBeacon', { value: undefined }); } catch (e) {}

  // ---- contract: the page starts the plugin through one message
  var answered = false, idleTimer = 0;
  function answer(msg) { if (answered) return; answered = true; rawPost(msg); }
  function done(v) {
    var s;
    try { s = JSON.stringify(v === undefined ? null : v); } catch (e) { answer({ type: 'reject', message: plain('The result could not be read: ' + errText(e)) }); return; }
    answer({ type: 'result', json: s === undefined ? 'null' : s });
  }
  // after the plugin's own code went quiet (no requests, no timers) without answering, say what it was waiting for
  function settleSoon() {
    if (!started || answered) return;
    if (idleTimer) return;
    idleTimer = nativeSetTimeout(function () {
      idleTimer = 0;
      if (answered || diag.inflight + liveTimers > 0) return;
      nativeSetTimeout(function () {
        if (answered || diag.inflight + liveTimers > 0) return;
        answer({ type: 'reject', message: 'The plugin stopped without giving an answer' + (diag.last ? ' (last request: ' + diag.last + ')' : '') + '.' });
      }, 30);
    }, 30);
  }
  function invoke(m) {
    var ex = g.module && g.module.exports;
    var fn = (ex && ex.getStreams) || (g.exports && g.exports.getStreams);
    if (typeof fn !== 'function') { answer({ type: 'reject', message: 'The plugin does not export getStreams().' }); return; }
    var p;
    try { p = fn(m.tmdbId, m.mediaType, m.season === null ? undefined : m.season, m.episode === null ? undefined : m.episode); } catch (e) { answer({ type: 'reject', message: plain(errText(e)).slice(0, 300) }); return; }
    Promise.resolve(p).then(done, function (e) { answer({ type: 'reject', message: plain(errText(e)).slice(0, 300) }); });
    settleSoon();
  }
  function run(m) {
    started = true;
    moduleSources = (m.modules && typeof m.modules === 'object') ? m.modules : {};
    try { (0, g.eval)(String(m.script)); } catch (e) { answer({ type: 'reject', message: plain('The plugin script failed to load: ' + errText(e)).slice(0, 300) }); return; }
    invoke(m);
  }
  g.addEventListener('message', function (e) {
    var m = e.data;
    if (!m || typeof m !== 'object') return;
    if (m.type === 'run' && !started) run(m);
    else if (m.type === 'fetch-result' && typeof m.id === 'number' && pending[m.id]) { var cb = pending[m.id]; delete pending[m.id]; cb(m); settleSoon(); }
  });
  rawPost({ type: 'engine-ready' });
})(self);
