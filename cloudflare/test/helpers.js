import { createD1 } from './d1shim.js';
import { handleApi } from '../src/api.js';

export function setup({ iterations = '1000' } = {}) {
  const env = { DB: createD1(), PBKDF2_ITERATIONS: iterations };
  const clock = { t: Date.now() };
  let n = 0;
  class Client {
    constructor(ip) { this.ip = ip ?? `198.51.100.${(++n % 250) + 1}`; this.sid = null; }
    async req(method, path, body, headers = {}) {
      const h = { 'content-type': 'application/json', 'cf-connecting-ip': this.ip, ...headers };
      if (this.sid) h.cookie = `liuying_sid=${this.sid}`;
      const res = await handleApi(new Request(`https://liuying.test${path}`, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) }), env, null, { now: () => clock.t });
      for (const c of res.headers.getSetCookie()) {
        const m = /^liuying_sid=([^;]*)/.exec(c);
        if (m) this.sid = m[1] || null;
      }
      const text = await res.text();
      let data; try { data = JSON.parse(text); } catch { data = text; }
      return { status: res.status, data, headers: res.headers };
    }
    get(p) { return this.req('GET', p); }
    post(p, b = {}) { return this.req('POST', p, b); }
    put(p, b) { return this.req('PUT', p, b); }
    del(p) { return this.req('DELETE', p); }
  }
  return { env, clock, Client, db: env.DB };
}
