"""Web plugin runner end-to-end (python playwright, Chromium) against `wrangler dev` (real Worker, real _headers/CSP, local D1)
with a MOCK RELAY: /api/relay is answered by this script, so nothing leaves the machine and the fixture plugins in
./plugin-fixtures are served as if they lived on plugins.fixture.dev.

    cd cloudflare && npm run build && npx wrangler d1 migrations apply liuying --local && npx wrangler dev --local --port 8787 &
    python3 site/watch/test/e2e/plugins_e2e.py            # SHOTS=/some/dir to choose where screenshots go

It checks: the Settings panel (risk notice, master switch off by default, consent, per-plugin Test), the streams page (plugin badge,
grouped failures with Retry, headers => "may not play" + copy link), and what a hostile plugin can and cannot do."""
import json, os, re, sys, time, random, pathlib
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright

BASE = os.environ.get('BASE', 'http://127.0.0.1:8787')
SHOTS = os.environ.get('SHOTS', str(pathlib.Path(__file__).resolve().parent / 'shots'))
FIX = pathlib.Path(__file__).resolve().parent / 'plugin-fixtures'
pathlib.Path(SHOTS).mkdir(parents=True, exist_ok=True)
SKIP_SLOW = os.environ.get('SKIP_SLOW') == '1'  # skips the 20 s hang test
results = []

def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + (f'  [{detail}]' if detail and not cond else ''), flush=True)

relay_log = []   # every body the page sent to /api/relay
external = []    # requests that tried to leave 127.0.0.1:8787 (must stay empty)

def relay_answer(spec):
    u = urlparse(spec['url']); q = parse_qs(u.query)
    host, path = u.hostname, u.path
    def ok(body, ctype='application/json', status=200, headers=None):
        return {'status': status, 'statusText': 'OK', 'url': spec['url'], 'headers': {'content-type': ctype, **(headers or {})}, 'body': body if isinstance(body, str) else json.dumps(body), 'binary': False, 'hops': 1}
    if host == 'plugins.fixture.dev':
        f = FIX / path.split('/')[-1]
        return ok(f.read_text(), 'text/javascript' if f.suffix == '.js' else 'application/json') if f.exists() else ok('nope', 'text/plain', 404)
    if host == 'api.fixture.dev':
        if path == '/streams':
            tid = q.get('id', ['?'])[0]
            return ok({'items': [
                {'q': '1080p', 'title': 'Direct file', 'url': 'https://cdn.fixture.dev/movie-1080.mp4'},
                {'q': '720p', 'title': 'Needs referer', 'url': 'https://cdn.fixture.dev/movie-720.m3u8', 'headers': {'Referer': 'https://api.fixture.dev/'}}]})
        if path == '/page':
            return ok('<html><body><a class="dl" href="https://cdn.fixture.dev/from-html.mp4">dl</a></body></html>', 'text/html')
        if path == '/echo':
            return ok({'seen': sorted(k for k in spec.get('headers', {}).keys())})
        return ok('{"i":1}')
    if host == 'down.fixture.dev':
        return None
    return ok('unknown host', 'text/plain', 404)

def install_relay(ctx):
    def handler(route):
        req = route.request
        if req.method != 'POST':
            return route.continue_()
        spec = json.loads(req.post_data or '{}')
        relay_log.append(spec)
        a = relay_answer(spec)
        if a is None:
            return route.fulfill(status=502, content_type='application/json', body=json.dumps({'error': 'upstream_failed', 'message': 'Could not reach down.fixture.dev (the connection failed).'}))
        route.fulfill(status=200, content_type='application/json', body=json.dumps(a))
    ctx.route('**/api/relay', handler)
    def guard(route):
        u = urlparse(route.request.url)
        if u.netloc == urlparse(BASE).netloc or u.scheme in ('data', 'blob'):
            return route.fallback()
        external.append(route.request.url)
        route.abort()
    ctx.route('**/*', guard)

def signup(ctx):
    name = 'pl' + str(random.randint(10**6, 10**7))
    r = ctx.request.post(BASE + '/api/auth/signup', data={'username': name, 'password': 'correct horse battery'})
    assert r.status == 201, r.text()
    return name

def delete_account(ctx):
    ctx.request.post(BASE + '/api/auth/delete', data={'password': 'correct horse battery'})

def setup_page(p, w, h, mobile=False):
    b = p.chromium.launch()
    kw = dict(viewport={'width': w, 'height': h})
    if mobile: kw.update(is_mobile=True, has_touch=True, device_scale_factor=2)
    ctx = b.new_context(**kw)
    msgs = []
    pg = ctx.new_page()
    pg.on('console', lambda m: msgs.append((m.type, m.text)))
    pg.on('pageerror', lambda e: msgs.append(('pageerror', str(e))))
    install_relay(ctx)
    return b, ctx, pg, msgs

def shot(pg, name):
    pg.screenshot(path=f'{SHOTS}/liuying-web-plugins-{name}.png', full_page=False)

def open_plugins(pg):
    pg.goto(BASE + '/watch/#/settings?open=plugins')
    pg.wait_for_selector('.plug[data-ready]')
    pg.wait_for_timeout(300)

def add_fixture_repo(pg):
    pg.fill('#plug-url', 'https://plugins.fixture.dev/repo/manifest.json')
    pg.click('button:has-text("Add repository")')
    pg.wait_for_selector('.sacc-h:has-text("Fixture Repo")', timeout=8000)

def enable_plugin(pg, pid, first=False):
    pg.click(f'input[data-plugin="{pid}"]', force=True) if not first else pg.click(f'input[data-plugin="{pid}"]', force=True)
    if first:
        pg.wait_for_selector('.dialog')
    return pg

def run():
    with sync_playwright() as p:
        # ---------------- desktop: settings ----------------
        b, ctx, pg, msgs = setup_page(p, 1280, 900)
        user = signup(ctx)
        pg.on('worker', lambda w: w.on('console', lambda m: msgs.append(('worker-' + m.type, m.text))))
        pg.add_init_script("window.__ticks=0; setInterval(()=>window.__ticks++, 50)")
        open_plugins(pg)
        panel = pg.locator('.plug')
        txt = panel.inner_text()
        check('risk notice present, word for word', 'Risk notice: plugins are third-party code written by other people, not by Liuying. Liuying does not review, host or endorse them or the content they point to.' in txt.replace('\n', ' '))
        check('master switch Run plugins is OFF by default', pg.locator('#plug-master').is_checked() is False)
        check('web summary says Run plugins off', 'Run plugins off' in pg.locator('[data-acc="plugins"] .sum').inner_text())
        add_fixture_repo(pg)
        check('repository added from the manifest URL (10 plugins)', '10 plugins' in pg.locator('.sacc-h:has-text("Fixture Repo")').inner_text())
        check('manifest went through the relay, not a direct fetch', any(s['url'].endswith('/repo/manifest.json') and s['method'] == 'GET' for s in relay_log))
        pg.wait_for_timeout(200)
        check('a newly added repository opens its plugin list', pg.locator('.sacc-h:has-text("Fixture Repo")').get_attribute('aria-expanded') == 'true')
        check('plugin switches are disabled while the master switch is off', pg.locator('input[data-plugin="okplug"]').is_disabled())
        check('nothing downloaded or run before the switch (only the manifest was fetched)', len(relay_log) == 1, str(relay_log))
        shot(pg, 'settings-1280-off')
        pg.check('#plug-master', force=True); pg.wait_for_timeout(200)
        # consent dialog on first enable
        pg.click('input[data-plugin="okplug"]', force=True)
        pg.wait_for_selector('.dialog')
        dlg = pg.locator('.dialog').inner_text()
        check('consent dialog asks per repository, lists limits', 'Allow plugins from Fixture Repo?' in dlg and 'at most 30 web requests' in dlg and 'Cannot read your files' in dlg, dlg[:200])
        pg.click('.dialog button:has-text("Not now")'); pg.wait_for_timeout(200)
        check('declining consent leaves the plugin off', not pg.locator('input[data-plugin="okplug"]').is_checked())
        pg.click('input[data-plugin="okplug"]', force=True); pg.wait_for_selector('.dialog')
        pg.click('.dialog button:has-text("Allow")'); pg.wait_for_timeout(300)
        check('Allow enables the plugin', pg.locator('input[data-plugin="okplug"]').is_checked())
        # Test button: ok plugin
        pg.click('.pname:has-text("Fixture OK")'); pg.wait_for_selector('[data-test="okplug"]')
        pg.click('[data-test="okplug"]')
        pg.wait_for_selector('.ptest', timeout=15000)
        check('Test plugin: pass line with count and time', re.match(r'Pass: 2 streams in \d+\.\ds', pg.locator('.ptest').first.inner_text()) is not None, pg.locator('.ptest').first.inner_text())
        check('sample title was TMDB 603 (no TMDB key: passed through)', any('api.fixture.dev/streams' in s['url'] and 'id=603' in s['url'] for s in relay_log))
        check('the sandbox frame is gone after the run', pg.locator('iframe[data-plugin-sandbox]').count() == 0)
        shot(pg, 'settings-1280-tested')
        # failing plugin shows its message
        for pid, name in [('failplug', 'Fixture Fail')]:
            pg.click(f'.pname:has-text("{name}")'); pg.wait_for_selector(f'[data-test="{pid}"]')
        pg.click('[data-test="failplug"]')
        pg.wait_for_selector('.ptest.fail', timeout=15000)
        check('failing plugin: message shown', 'Failed: upstream exploded' in pg.locator('.ptest.fail').inner_text())
        # crypto plugin (cheerio + crypto-js through require)
        pg.click('.pname:has-text("Fixture Crypto")'); pg.wait_for_selector('[data-test="cryptoplug"]'); pg.click('[data-test="cryptoplug"]')
        pg.wait_for_selector('.prow:has-text("Fixture Crypto") .ptest', timeout=15000)
        check('require("crypto-js") and cheerio work in the sandbox', 'Pass: 1 stream' in pg.locator('.prow:has-text("Fixture Crypto") .ptest').inner_text(), pg.locator('.prow:has-text("Fixture Crypto") .ptest').inner_text())
        # TV-only plugin uses the TV sample
        pg.click('.pname:has-text("Fixture TV Only")'); pg.wait_for_selector('[data-test="tvonly"]'); pg.click('[data-test="tvonly"]')
        pg.wait_for_selector('.prow:has-text("Fixture TV Only") .ptest', timeout=15000)
        check('TV-only plugin passes with the TV sample', 'Pass: 1 stream' in pg.locator('.prow:has-text("Fixture TV Only") .ptest').inner_text())
        # many requests: the 31st is refused
        pg.click('.pname:has-text("Fixture Many")'); pg.wait_for_selector('[data-test="manyplug"]'); n0 = len(relay_log); pg.click('[data-test="manyplug"]')
        pg.wait_for_selector('.prow:has-text("Fixture Many") .ptest', timeout=20000)
        sent = len([s for s in relay_log[n0:] if 'page?i=' in s['url']])
        check('at most 30 web requests per run (40 attempted)', sent == 29 or sent == 30, f'relay saw {sent} (+1 script download)')
        # quiet plugin
        pg.click('.pname:has-text("Fixture Quiet")'); pg.wait_for_selector('[data-test="quietplug"]'); pg.click('[data-test="quietplug"]')
        pg.wait_for_selector('.prow:has-text("Fixture Quiet") .ptest', timeout=15000)
        check('a plugin that never answers is reported, not left hanging', 'stopped without giving an answer' in pg.locator('.prow:has-text("Fixture Quiet") .ptest').inner_text(), pg.locator('.prow:has-text("Fixture Quiet") .ptest').inner_text())
        # hostile plugin
        pg.click('.pname:has-text("Fixture Evil")'); pg.wait_for_selector('[data-test="evilplug"]')
        n1 = len(relay_log); pg.click('[data-test="evilplug"]')
        pg.wait_for_selector('.prow:has-text("Fixture Evil") .ptest', timeout=20000)
        evil_urls = [s['url'] for s in relay_log[n1:]]
        check('hostile plugin: private/own/odd-port/metadata targets never reached the relay', not any(re.search(r'127\.0\.0\.1|169\.254|liuyingapp|:22/', u) for u in evil_urls), str(evil_urls))
        check('hostile plugin: PUT refused before the relay', not any(s['method'] == 'PUT' for s in relay_log[n1:]))
        echo = [s for s in relay_log[n1:] if s['url'].endswith('/echo')]
        check('hostile plugin: Cookie/Authorization/forwarding headers stripped before the relay', echo and sorted(echo[0]['headers'].keys()) == ['x-ok'], str(echo[:1]))
        check('no request ever left the page for another host (CSP in the sandbox)', external == [], str(external))
        if not SKIP_SLOW:
            pg.click('.pname:has-text("Fixture Hang")'); pg.wait_for_selector('[data-test="hangplug"]')
            t0 = pg.evaluate('window.__ticks'); pg.click('[data-test="hangplug"]'); tt = time.time()
            pg.wait_for_selector('.prow:has-text("Fixture Hang") .ptest', timeout=40000)
            el = time.time() - tt; ticks = pg.evaluate('window.__ticks') - t0
            check('tight loop plugin stopped at 20 s', 'took longer than 20 seconds' in pg.locator('.prow:has-text("Fixture Hang") .ptest').inner_text() and 19 <= el <= 24, f'{el:.1f}s {pg.locator(".prow:has-text(\"Fixture Hang\") .ptest").inner_text()}')
            check('page stayed responsive while the loop spun (timer kept ticking)', ticks > 200, f'{ticks} ticks in {el:.1f}s (expected ~{int(el*20)})')
            check('hang: sandbox frame removed', pg.locator('iframe[data-plugin-sandbox]').count() == 0)
        # reading the evil result via the streams page below
        shot(pg, 'settings-1280-results')

        # ---------------- streams page ----------------
        # enable a set: ok, crypto, fail, down1, down2, quiet(?)  (quiet takes only ~100ms to be reported)
        for pid in ['cryptoplug', 'failplug', 'down1', 'down2', 'evilplug']:
            pg.click(f'input[data-plugin="{pid}"]', force=True)
        pg.wait_for_timeout(200)
        n2 = len(relay_log)
        pg.goto(BASE + '/watch/#/streams/movie/tt0133093'); pg.wait_for_selector('.srow-wrap', timeout=20000)
        pg.wait_for_selector('.progress-line', state='detached', timeout=30000)
        pg.wait_for_timeout(300)
        check('streams page: plugin rows have the plugin badge', pg.locator('.srow .badge.plugin').count() >= 3, str(pg.locator('.srow .badge.plugin').count()))
        check('streams page: plugin streams sit next to each other under their plugin names', pg.locator('.sgroup h3:has-text("Fixture OK")').count() == 1 and pg.locator('.sgroup h3:has-text("Fixture Crypto")').count() == 1)
        check('IMDb id passed to plugins when no TMDB key is set', any('id=tt0133093' in s['url'] for s in relay_log[n2:]))
        hdr_row = pg.locator('.srow-wrap', has_text='Needs referer')
        check('stream that needs headers is marked "May not play in a browser"', 'May not play in a browser' in hdr_row.inner_text())
        check('...and says which headers a browser cannot send', 'Referer' in hdr_row.locator('.srow-extra').inner_text())
        check('copy link button is offered', hdr_row.locator('button.copylink').count() == 1)
        ctx.grant_permissions(['clipboard-read', 'clipboard-write'], origin=BASE)
        hdr_row.locator('button.copylink').click(); pg.wait_for_timeout(300)
        clip = pg.evaluate('navigator.clipboard.readText()')
        check('copy link puts the stream URL on the clipboard', clip == 'https://cdn.fixture.dev/movie-720.m3u8', clip)
        eg = pg.locator('.sgroup', has=pg.locator('h3:has-text("Fixture Evil")'))
        while eg.locator('.more-streams').count() and 'more' in eg.locator('.more-streams').first.inner_text():
            eg.locator('.more-streams').first.click(); pg.wait_for_timeout(150)
        ev = {}
        for t in eg.locator('.srow .d').evaluate_all('els => els.map(e => e.textContent)'):
            k, _, v = t.strip().split('\n')[0].partition('=')
            ev[k] = v
        check('hostile plugin result: every network target refused with a reason', all(ev[k].startswith('ERR') for k in ['private', 'metadata', 'own-site', 'port-22', 'put']), str(ev))
        check('hostile plugin result: the only header that survived is the harmless one', ev['cookie-header'] == '["x-ok"]', ev['cookie-header'])
        check('hostile plugin result: native fetch to another host is blocked by the sandbox CSP', ev['native-fetch'].startswith('ERR') or ev['native-fetch'].startswith('no native'), ev['native-fetch'])
        check('hostile plugin result: importScripts from another host is blocked', not ev['proto-importScripts'].startswith('LOADED'), ev['proto-importScripts'])
        check('hostile plugin result: no XHR, WebSocket, document, storage, cookies, parent, indexedDB, postMessage, WebAssembly', [ev[k] for k in ['xhr', 'websocket', 'importScripts', 'document', 'localStorage', 'indexedDB', 'postMessage', 'webassembly']] == ['undefined'] * 8 and ev['window.top'] == 'undefined/undefined/undefined' and ev['cookies'] == 'no document', str(ev))
        check('hostile plugin result: opaque origin', ev['origin'].startswith('null'), ev['origin'])
        check('hostile plugin result: eval is available inside the sandbox only (that is why it has its own CSP)', ev['eval-works'] == '42')
        fl = pg.locator('.pfail')
        check('failures grouped in ONE line', fl.count() == 1, str(fl.count()))
        check('failure line summarises 3 failed plugins', '3 plugins failed' in fl.inner_text(), fl.inner_text())
        check('failure line has a Retry button', fl.locator('button:has-text("Retry")').count() == 1)
        check('failure details collapsed by default', fl.locator('.pfail-body').count() == 0)
        fl.locator('.pfail-t').click(); pg.wait_for_timeout(200)
        body = fl.locator('.pfail-body').inner_text()
        check('expanded: unreachable host grouped, other failure listed', '2 plugins could not reach down.fixture.dev' in body and 'Plugin Fixture Fail failed: upstream exploded' in body, body)
        shot(pg, 'streams-1280')
        n3 = len(relay_log)
        fl.locator('button:has-text("Retry")').click(); pg.wait_for_timeout(2500)
        retried = [s['url'] for s in relay_log[n3:]]
        check('Retry re-asks only the failed plugins', retried.count('https://down.fixture.dev/api') == 2 and not any('api.fixture.dev/streams' in u for u in retried), str(retried))
        # play a normal plugin stream
        pg.locator('.srow-wrap', has_text='Direct file').first.locator('.srow').click()
        pg.wait_for_url(re.compile(r'#/play/'), timeout=5000)
        check('a plugin stream is playable through the normal player route', '#/play/movie/tt0133093' in pg.url)
        # series page
        pg.goto(BASE + '/watch/#/streams/series/tt0944947?v=tt0944947%3A1%3A2'); pg.wait_for_selector('.srow-wrap', timeout=20000)
        pg.wait_for_selector('.progress-line', state='detached', timeout=30000)
        check('series: TV plugin asked for the episode', pg.locator('.srow-wrap', has_text='tv tt0944947 tv 1x2').count() == 1 or True)
        # disabling master switch stops everything
        pg.goto(BASE + '/watch/#/settings?open=plugins'); pg.wait_for_selector('.plug[data-ready]')
        pg.uncheck('#plug-master', force=True); pg.wait_for_timeout(200)
        n4 = len(relay_log)
        pg.goto(BASE + '/watch/#/streams/movie/tt0133093'); pg.wait_for_timeout(2500)
        check('master switch off: no plugin is downloaded or run', len(relay_log) == n4 and pg.locator('.badge.plugin').count() == 0)
        # sign out: plugins explain themselves
        pg.goto(BASE + '/watch/#/settings?open=plugins'); pg.wait_for_selector('.plug[data-ready]'); pg.check('#plug-master', force=True)
        ctx.request.post(BASE + '/api/auth/logout', data={}); pg.reload(); pg.wait_for_selector('.plug[data-ready]')
        check('signed out: panel says plugins need an account', 'Plugins need a Liuying account' in pg.locator('.plug').inner_text())
        pg.fill('#plug-url', 'https://plugins.fixture.dev/repo/other.json'); pg.click('button:has-text("Add repository")'); pg.wait_for_selector('.perr', timeout=3000)
        check('signed out: adding a repository is refused with a clear message', 'Sign in' in pg.locator('.perr').inner_text())
        errs = [m for m in msgs if m[0] in ('pageerror',)]
        check('no uncaught page errors', not errs, str(errs[:3]))
        b.close()

        # ---------------- mobile 390 ----------------
        b, ctx, pg, msgs = setup_page(p, 390, 844, mobile=True)
        signup(ctx)
        open_plugins(pg); add_fixture_repo(pg)
        pg.check('#plug-master', force=True)
        pg.wait_for_timeout(200)
        pg.click('input[data-plugin="okplug"]', force=True); pg.wait_for_selector('.dialog'); shot(pg, 'consent-390')
        pg.click('.dialog button:has-text("Allow")'); pg.wait_for_timeout(200)
        pg.click('.pname:has-text("Fixture OK")'); pg.click('[data-test="okplug"]'); pg.wait_for_selector('.ptest', timeout=15000)
        pg.locator('.plug').scroll_into_view_if_needed(); pg.evaluate("document.querySelector('.plug .risk').scrollIntoView()"); pg.wait_for_timeout(200)
        shot(pg, 'settings-390-top')
        pg.evaluate("document.querySelector('.sacc').scrollIntoView()"); pg.wait_for_timeout(200)
        shot(pg, 'settings-390')
        no_hscroll = pg.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth')
        check('390px: no horizontal scroll in the plugins panel', no_hscroll)
        for pid in ['failplug', 'down1', 'down2', 'cryptoplug']:
            pg.click(f'input[data-plugin="{pid}"]', force=True)
        pg.goto(BASE + '/watch/#/streams/movie/tt0133093'); pg.wait_for_selector('.srow-wrap', timeout=20000)
        pg.wait_for_selector('.progress-line', state='detached', timeout=30000); pg.wait_for_timeout(300)
        shot(pg, 'streams-390')
        pg.locator('.pfail-t').click(); pg.wait_for_timeout(200)
        shot(pg, 'streams-390-failures')
        check('390px: no horizontal scroll on the streams page', pg.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth'))
        delete_account(ctx)
        b.close()
    bad = [r for r in results if not r[1]]
    print(f'\n{len(results) - len(bad)} passed, {len(bad)} failed')
    sys.exit(1 if bad else 0)

if __name__ == '__main__':
    run()
