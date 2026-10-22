import sys, time, json
sys.path.insert(0, '/tmp/claude-0/-home-claude/6b06abf3-0ebc-5f84-baa2-1bb0a801447f/scratchpad')
from wt_common import *
B = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:4195'
ok = []
def check(n, c, extra=''):
    print(('PASS ' if c else 'FAIL ') + n, extra if not c else ''); ok.append(bool(c))
user = 'w1t_' + str(int(time.time()))[-7:]
with sync_playwright() as p:
    b = p.chromium.launch(); ctx = b.new_context(viewport={'width': 390, 'height': 900}); pg = ctx.new_page()
    errs = []
    pg.on('console', lambda m: errs.append(m.text) if m.type == 'error' or 'Content Security' in m.text else None)
    pg.goto(B + '/signup'); pg.wait_for_selector('#username')
    check('recovery checkbox default on', pg.is_checked('#recovery'))
    pg.fill('#username', user); pg.fill('#password', 'correct horse battery'); pg.fill('#confirm', 'correct horse battery')
    # simulate cold start: delay the API 3.6s
    def slow(route):
        time.sleep(3.6); route.continue_()
    pg.route('**/api/auth/signup', slow)
    pg.click('button.submit')
    t = time.time()
    got = wait_js(pg, "!document.getElementById('wake').hidden", 6000, 150)
    check('waking message appears after ~3s', got)
    wait_js(pg, "!document.getElementById('rc-panel').hidden", 8000)
    check('wake message hidden after response', pg.evaluate("document.getElementById('wake').hidden"))
    code = pg.inner_text('#rc-code').strip()
    check('code shown in format', len(code) == 23 and code.count('-') == 3, code)
    check('continue disabled until saved', pg.get_attribute('#rc-go', 'aria-disabled') == 'true')
    check('form hidden', not pg.is_visible('#auth-form'))
    pg.screenshot(path=f'{SP}/liuying-wave1-web-signup-code-390.png')
    with pg.expect_download() as dl: pg.click('#rc-dl')
    txt = open(dl.value.path()).read(); check('download contains code', code in txt)
    pg.check('#rc-ok'); check('continue enabled', pg.get_attribute('#rc-go', 'aria-disabled') == 'false')
    pg.click('#rc-go'); pg.wait_for_url('**/account'); 
    pg.wait_for_selector('#acct:not([hidden])')
    pg.click('#rec-det summary'); pg.wait_for_function if False else None
    wait_js(pg, "document.getElementById('rec-status').textContent.includes('recovery code is set')", 5000)
    check('account shows code set', 'recovery code is set' in pg.inner_text('#rec-status'))
    # sign out then recover
    pg.click('#logout'); pg.wait_for_url('**/login')
    pg.click('text=Forgot your password?'); pg.wait_for_url('**/recover')
    pg.fill('#username', user); pg.fill('#code', code.lower().replace('-', ' ')); pg.fill('#password', 'brand new pass phrase')
    pg.click('button.submit'); wait_js(pg, "!document.getElementById('rc-panel').hidden", 8000)
    code2 = pg.inner_text('#rc-code').strip(); check('new code differs', code2 != code and len(code2) == 23)
    pg.screenshot(path=f'{SP}/liuying-wave1-web-recover-code-390.png')
    pg.check('#rc-ok'); pg.click('#rc-go'); pg.wait_for_url('**/account')
    # old code rejected
    pg.click('#logout'); pg.wait_for_url('**/login'); pg.goto(B + '/recover')
    pg.fill('#username', user); pg.fill('#code', code); pg.fill('#password', 'another pass phrase 1'); pg.click('button.submit')
    wait_js(pg, "!document.getElementById('form-error').hidden", 5000)
    check('old code rejected with message', 'Incorrect username or recovery code' in pg.inner_text('#form-error'))
    # desktop shot
    pg.set_viewport_size({'width': 1280, 'height': 800}); pg.screenshot(path=f'{SP}/liuying-wave1-web-recover-1280.png')
    # delete account
    pg.goto(B + '/login'); pg.fill('#username', user); pg.fill('#password', 'brand new pass phrase'); pg.click('button.submit'); pg.wait_for_url('**/account')
    pg.click('summary:has-text("Delete account")'); pg.fill('#del-pw', 'brand new pass phrase'); pg.once('dialog', lambda d: d.accept()); pg.click('#del-go'); pg.wait_for_url(B + '/')
    check('no console errors', not [e for e in errs if '401' not in e], errs)
    b.close()
print('ALL', all(ok), sum(ok), len(ok))
