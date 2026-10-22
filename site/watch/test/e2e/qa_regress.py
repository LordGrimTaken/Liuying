"""Web-player QA regression checks (python playwright). Usage:
  node edge-addon.js &            # https://localhost:4182 (CERT_DIR with key.pem/cert.pem)
  BASE=http://localhost:3000 python3 qa_regress.py
"""
import os, sys, time
from playwright.sync_api import sync_playwright
BASE = os.environ.get('BASE', 'http://localhost:3000'); EDGE = 'https://localhost:4182/manifest.json'
HERE = os.path.dirname(os.path.abspath(__file__)); AUDIT = open(HERE + '/audit.js').read()
ok = []
def check(n, c, extra=''):
    print(('PASS ' if c else 'FAIL ') + n, '' if c else extra); ok.append(bool(c))
with sync_playwright() as p:
    b = p.chromium.launch(); ctx = b.new_context(viewport={'width': 390, 'height': 844}, ignore_https_errors=True); pg = ctx.new_page()
    pg.on('dialog', lambda d: (ok.append(False), print('FAIL unexpected dialog', d.message), d.dismiss()))
    pg.goto(BASE + '/watch/'); pg.wait_for_selector('#view')
    pg.goto(BASE + '/watch/#/sources?add=1'); pg.fill('#src-url', EDGE); pg.click('button:has-text("Continue")'); pg.wait_for_selector('.preview .preview'); pg.click('.preview .preview button:has-text("Install")'); pg.wait_for_timeout(300)
    def go(r, ms=1800): pg.goto(BASE + '/watch/#' + r); pg.reload(); pg.wait_for_timeout(ms)
    # long unbroken title must not widen the streams page or hero
    go('/streams/movie/edge:long1'); check('streams: no horizontal overflow', pg.evaluate(AUDIT)['docOverflow'] <= 0 and pg.evaluate("document.querySelector('.s-head h1').getBoundingClientRect().right <= innerWidth"))
    go('/'); check('hero title clamped to 3 lines', pg.evaluate("(() => { const h = document.querySelector('.hero h2'); return !h || h.getBoundingClientRect().height < 260; })()"))
    # 1800-episode season is chunked
    go('/title/series/edge:s4', 2500); n = pg.evaluate("document.querySelectorAll('.ep').length"); check('episodes chunked', 0 < n <= 120, n)
    check('show-more button present', pg.locator('.more-eps').count() == 1)
    # deep link: Back must not leave the site
    pg2 = ctx.new_page(); pg2.goto('about:blank'); pg2.goto(BASE + '/watch/#/title/movie/edge:cjk'); pg2.wait_for_timeout(1500); pg2.click('.det-hero .back'); pg2.wait_for_timeout(600)
    check('deep-link back stays in app', pg2.url.startswith(BASE + '/watch/'), pg2.url)
    # modal traps focus
    go('/'); pg.locator('.pcard').first.click(button='right'); pg.wait_for_timeout(300)
    out = 0
    for _ in range(8):
        pg.keyboard.press('Tab'); out += 0 if pg.evaluate("!!document.activeElement.closest('#modal')") else 1
    check('modal keeps focus inside', out == 0, out); pg.keyboard.press('Escape')
    # poster quick/more buttons are not tab stops
    go('/'); check('poster actions removed from tab order', pg.evaluate("[...document.querySelectorAll('.pcard .quick,.pcard .more')].every(e => e.tabIndex === -1)"))
    # soft session probe: no 401 in the console
    errs = []; pg.on('console', lambda m: errs.append(m.text) if m.type == 'error' else None); go('/'); check('no 401 console error', not [e for e in errs if '401' in e], errs)
    # hls.js is not loaded on ordinary pages
    check('hls.js not eagerly loaded', not pg.evaluate("!!window.Hls"))
    b.close()
print('ALL', all(ok), sum(ok), len(ok)); sys.exit(0 if all(ok) else 1)
