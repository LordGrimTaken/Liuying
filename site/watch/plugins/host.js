// Runs ONE plugin in the sandbox frame and enforces the per-run limits. This is the trust boundary: everything the frame sends
// is untrusted input (a plugin can post any message it likes), so each message is validated here before anything happens.
//
//   page (this file)  <-- postMessage -->  iframe sandbox="allow-scripts" (opaque origin, CSP default-src 'none')
//                                              \-- Worker running engine.js (inherits the CSP, no network at all)
//
// The frame never sees cookies, storage or the page. Its only network is this file forwarding validated requests to the relay.
import { ALLOWED_METHODS, LIMITS, checkTarget, sanitizeRequestHeaders } from './rules.js';
import { parseResult } from './contract.js';

export const SANDBOX_URL = '/watch/plugins/sandbox.htm';
const BOOT_TIMEOUT_MS = 6000;

export class RelayError extends Error {
  constructor(message, kind = 'relay') { super(message); this.kind = kind; }
}

const assetCache = new Map();
/** Text of a same-origin asset (engine and the two bundled npm modules), fetched once. */
export function loadAsset(path, fetchImpl = fetch) {
  if (!assetCache.has(path)) {
    assetCache.set(path, fetchImpl(path, { credentials: 'omit' }).then((r) => {
      if (!r.ok) throw new Error(`Could not load ${path} (HTTP ${r.status}).`);
      return r.text();
    }).catch((e) => { assetCache.delete(path); throw e; }));
  }
  return assetCache.get(path);
}
export const MODULE_FILES = { 'cheerio-without-node-native': '/watch/plugins/vendor/cheerio.js', 'crypto-js': '/watch/plugins/vendor/crypto-js.js' };

/** Checks a fetch request from the frame. Returns `{ok:true, req}` with a normalised request or `{ok:false, error}`. Pure. */
export function validateFetchSpec(spec) {
  if (!spec || typeof spec !== 'object' || Array.isArray(spec)) return { ok: false, error: 'fetch received a bad request.' };
  const method = String(spec.method ?? 'GET').toUpperCase();
  if (!ALLOWED_METHODS.has(method)) return { ok: false, error: `HTTP method ${method.slice(0, 12)} is not allowed.` };
  const chk = checkTarget(spec.url);
  if (!chk.ok) return { ok: false, error: chk.reason };
  let body = null;
  if (spec.body !== undefined && spec.body !== null) {
    if (typeof spec.body !== 'string') return { ok: false, error: 'The request body must be text.' };
    if (method !== 'POST') return { ok: false, error: `A ${method} request cannot have a body.` };
    if (new TextEncoder().encode(spec.body).length > LIMITS.MAX_REQUEST_BODY_BYTES) return { ok: false, error: 'The request body is too large (limit 64 KB).' };
    body = spec.body;
  }
  return { ok: true, req: { url: chk.url.href, method, headers: sanitizeRequestHeaders(spec.headers).headers, body } };
}

/**
 * @param {object} o
 * @param {string} o.script            plugin source
 * @param {object} o.args              {tmdbId, mediaType, season, episode}
 * @param {(req:object, o:{signal:AbortSignal})=>Promise<object>} o.relay   sends a validated request to /api/relay
 * @param {string} o.engine            engine.js text
 * @param {object} o.modules           {name: source} for require()
 * @param {string} o.pluginName
 * @returns {Promise<{ok:true, streams:object[], requests:number, elapsedMs:number}|{ok:false, error:string, kind:string, requests:number, elapsedMs:number}>}
 */
export function runInSandbox({ script, args, relay, engine, modules, pluginName, timeoutMs = LIMITS.RUN_TIMEOUT_MS, doc = document, startedAt = Date.now() }) {
  return new Promise((resolve) => {
    const ctl = new AbortController();
    let frame = null, finished = false, requests = 0, loads = 0, runSent = false, inflight = 0;
    const timers = [];
    const elapsed = () => Date.now() - startedAt;
    const finish = (r) => {
      if (finished) return;
      finished = true;
      timers.forEach(clearTimeout);
      ctl.abort();
      window.removeEventListener('message', onMessage);
      try { frame?.remove(); } catch { /* already gone */ }
      frame = null;
      resolve({ requests, elapsedMs: elapsed(), ...r });
    };
    const fail = (error, kind = 'failed') => finish({ ok: false, error, kind });
    const remaining = () => Math.max(1, timeoutMs - elapsed());
    timers.push(setTimeout(() => fail(`The plugin took longer than ${Math.round(timeoutMs / 1000)} seconds and was stopped.`, 'timeout'), remaining()));
    const bootTimer = setTimeout(() => fail('The plugin sandbox did not start (is your browser blocking frames?).', 'sandbox'), BOOT_TIMEOUT_MS);
    timers.push(bootTimer);

    const post = (m) => { try { frame?.contentWindow?.postMessage(m, '*'); } catch { /* frame gone */ } };

    async function onFetch(m) {
      if (typeof m.id !== 'number') return;
      const reply = (o) => { if (!finished) post({ type: 'fetch-result', id: m.id, ...o }); };
      const v = validateFetchSpec(m.spec);
      if (!v.ok) { reply({ ok: false, error: v.error }); return; }
      if (requests >= LIMITS.MAX_REQUESTS) { reply({ ok: false, error: `Too many web requests (limit ${LIMITS.MAX_REQUESTS} per run).` }); return; }
      requests++;
      if (inflight >= 10) { reply({ ok: false, error: 'Too many requests at once.' }); return; }
      inflight++;
      const rc = new AbortController();
      const onAbort = () => rc.abort();
      ctl.signal.addEventListener('abort', onAbort);
      const rt = setTimeout(() => rc.abort(), LIMITS.REQUEST_TIMEOUT_MS + 1500);
      try {
        const res = await relay(v.req, { signal: rc.signal });
        if (!res || typeof res.status !== 'number' || typeof res.body !== 'string') throw new RelayError('The relay answered with something unexpected.');
        if (res.body.length > LIMITS.MAX_RESPONSE_BYTES * 1.4) throw new RelayError('The response is larger than 5 MB.');
        requests += Math.max(0, Math.min(Number(res.hops) || 1, LIMITS.MAX_REDIRECTS + 1) - 1); // redirect hops count
        reply({ ok: true, res: { status: res.status, statusText: String(res.statusText ?? ''), url: String(res.url ?? v.req.url), headers: res.headers && typeof res.headers === 'object' ? res.headers : {}, body: res.body, binary: !!res.binary } });
      } catch (e) {
        reply({ ok: false, error: rc.signal.aborted && !(e instanceof RelayError) ? 'The server did not answer in time.' : String(e?.message || 'fetch failed').slice(0, 300) });
      } finally { clearTimeout(rt); ctl.signal.removeEventListener('abort', onAbort); inflight--; }
    }

    function onMessage(ev) {
      if (!frame || ev.source !== frame.contentWindow || ev.origin !== 'null') return;
      const m = ev.data;
      if (!m || typeof m !== 'object' || typeof m.type !== 'string') return;
      switch (m.type) {
        case 'boot': post({ type: 'init', engine }); clearTimeout(bootTimer); timers.push(setTimeout(() => { if (!runSent) fail('The plugin sandbox did not start.', 'sandbox'); }, BOOT_TIMEOUT_MS)); break;
        case 'engine-ready':
          if (runSent) break;
          runSent = true;
          post({ type: 'run', script, tmdbId: String(args.tmdbId), mediaType: args.mediaType === 'tv' ? 'tv' : 'movie', season: Number.isInteger(args.season) ? args.season : null, episode: Number.isInteger(args.episode) ? args.episode : null, modules });
          break;
        case 'fetch': onFetch(m); break;
        case 'result': {
          if (typeof m.json !== 'string' || m.json.length > 4 * 1024 * 1024) { fail('The result was too large or could not be read.'); break; }
          const p = parseResult(m.json, pluginName);
          if (p.ok) finish({ ok: true, streams: p.streams }); else fail(p.error);
          break;
        }
        case 'reject': fail(String(m.message ?? 'The plugin failed.').slice(0, 300)); break;
        case 'fatal': fail(String(m.message ?? 'The plugin crashed.').slice(0, 300), 'sandbox'); break;
        default: break; // anything else a plugin posts is ignored
      }
    }

    try {
      frame = doc.createElement('iframe');
      frame.setAttribute('sandbox', 'allow-scripts'); // no allow-same-origin: opaque origin, no cookies, no storage, no parent access
      frame.setAttribute('referrerpolicy', 'no-referrer');
      frame.setAttribute('aria-hidden', 'true');
      frame.setAttribute('title', 'Plugin sandbox');
      frame.setAttribute('tabindex', '-1');
      frame.dataset.pluginSandbox = '1';
      frame.style.cssText = 'position:fixed;left:-9999px;top:0;width:1px;height:1px;border:0;visibility:hidden;pointer-events:none';
      frame.addEventListener('load', () => { if (++loads > 1) fail('The plugin tried to navigate its frame and was stopped.', 'sandbox'); });
      window.addEventListener('message', onMessage);
      frame.src = SANDBOX_URL;
      doc.body.append(frame);
    } catch (e) { fail(`The plugin sandbox could not be created: ${e?.message || e}`, 'sandbox'); }
  });
}
