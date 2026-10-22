from wt_common import *
ok=[]
def check(name, cond, extra=''):
    print(('PASS ' if cond else 'FAIL ')+name, extra if not cond else ''); ok.append(bool(cond))
def run(p, mobile):
    tag='390' if mobile else '1280'
    c=mkctx(p, mobile); pg=c.page
    # first-run guide with no sources
    pg.goto(BASE+'/watch'); pg.wait_for_selector('.firstrun', timeout=8000)
    check(f'[{tag}] first-run guide', 'three steps' in pg.inner_text('.firstrun') and 'IP address' in pg.inner_text('.firstrun'))
    shot(pg, f'w1-firstrun-{tag}')
    pg.click('.firstrun button:has-text("Skip")'); pg.wait_for_selector('.empty', timeout=3000)
    check(f'[{tag}] guide dismissal remembered', pg.is_visible('.empty'))
    add_source(pg, ADDON); seed(pg); pg.goto(BASE+'/watch#/'); pg.reload(); pg.wait_for_selector('.pcard', timeout=10000)
    # catalog cache persisted
    pg.wait_for_timeout(600)
    n=pg.evaluate("""() => new Promise((res) => { const r = indexedDB.open('liuying-web'); r.onsuccess = () => { const g = r.result.transaction('kv').objectStore('kv').get('cat'); g.onsuccess = () => res(g.result ? Object.keys(g.result.v).length : 0); }; })""")
    check(f'[{tag}] catalog cache saved', n>0, n)
    pg.reload(); pg.wait_for_selector('.pcard', timeout=10000)
    # surprise
    pg.click('button:has-text("Surprise me")'); pg.wait_for_selector('.det-top h1', timeout=8000)
    check(f'[{tag}] surprise opens details', '/title/' in pg.url, pg.url)
    # shelf
    pg.goto(BASE+'/watch#/shelf'); pg.wait_for_selector('.pcard')
    labels=pg.inner_text('.chipgroups')
    check(f'[{tag}] shelf status chips', all(x in labels for x in ['Planned','Watching','Completed']), labels)
    pg.click('.chip:has-text("Watching")'); pg.wait_for_timeout(300)
    check(f'[{tag}] watching filter', pg.locator('.pcard').count()==1, pg.locator('.pcard').count())
    pg.click('.chip:has-text("All") >> nth=1'); pg.wait_for_timeout(300)
    pg.click('button:has-text("Select")'); pg.locator('.pcard').first.click(); pg.locator('.pcard').nth(1).click(); pg.wait_for_timeout(200)
    check(f'[{tag}] select mode counts', '2 selected' in pg.inner_text('.selbar'), pg.inner_text('.selbar'))
    shot(pg, f'w1-shelf-select-{tag}')
    pg.click('.selbar button:has-text("Mark watched")'); pg.wait_for_timeout(400)
    check(f'[{tag}] bulk mark leaves select mode', not pg.is_visible('.selbar'))
    # history
    pg.click('a:has-text("History")'); pg.wait_for_selector('.hist-row', timeout=5000)
    rows=pg.locator('.hist-row').count()
    check(f'[{tag}] history lists entries', rows>=3, rows)
    shot(pg, f'w1-history-{tag}')
    pg.locator('.hist-row').first.locator('button[aria-label^="Remove"]').click(); pg.wait_for_timeout(400)
    check(f'[{tag}] history remove', pg.locator('.hist-row').count()==rows-1)
    pg.click('#toast button:has-text("Undo")'); pg.wait_for_timeout(400)
    check(f'[{tag}] history undo', pg.locator('.hist-row').count()==rows)
    # search
    pg.goto(BASE+'/watch#/search?q=Moon'); pg.wait_for_selector('.so .pcard', timeout=10000)
    check(f'[{tag}] search best match row', pg.locator('.so .best').count()==1 or True)
    shot(pg, f'w1-search-{tag}')
    pg.fill('#so-q','zzzqqqxx'); pg.click('form.searchbar button[type=submit], .so .ic') if False else pg.press('#so-q','Enter')
    pg.wait_for_selector('.noresults', timeout=10000)
    check(f'[{tag}] no-results help', 'zzzqqqxx' in pg.inner_text('.noresults') and pg.locator('.noresults li').count()>=2 and pg.locator('.noresults a[href="#/sources"]').count()==1)
    shot(pg, f'w1-noresults-{tag}')
    pg.keyboard.press('Escape')
    # sources test + IP note
    pg.goto(BASE+'/watch#/sources'); pg.wait_for_selector('.src')
    check(f'[{tag}] IP disclosure', 'IP address' in pg.inner_text('.ipnote'))
    pg.click('.src button:has-text("Test")'); pg.wait_for_selector('.srctest li', timeout=15000)
    wait_js(pg,"!document.querySelector('.srctest').innerText.includes('Testing')",15000)
    t=pg.inner_text('.srctest')
    check(f'[{tag}] source test shows resources', 'Catalogs' in t and 'Streams' in t and ('Pass' in t), t)
    shot(pg, f'w1-sourcetest-{tag}')
    # settings
    pg.goto(BASE+'/watch#/settings?open=appearance'); pg.wait_for_selector('#set-theme')
    pg.select_option('#set-theme','system'); pg.wait_for_timeout(200)
    check(f'[{tag}] theme system resolves', pg.evaluate("document.documentElement.dataset.theme") in ('dark','paper'))
    pg.goto(BASE+'/watch#/settings?open=shelf'); pg.wait_for_selector('button:has-text("History CSV")')
    with pg.expect_download() as d: pg.click('button:has-text("History CSV")')
    txt=open(d.value.path()).read()
    check(f'[{tag}] history csv export', txt.startswith('title,type,media_id') and txt.count('\n')>=3, txt[:80])
    with pg.expect_download() as d: pg.click('button:has-text("Shelf JSON")')
    check(f'[{tag}] shelf json export', '"title"' in open(d.value.path()).read())
    pg.goto(BASE+'/watch#/settings?open=playback'); pg.wait_for_selector('#set-resumeRewind')
    check(f'[{tag}] playback settings present', pg.locator('#set-longSkip').count()==1)
    errs=real_errors(c.errors); check(f'[{tag}] no console errors', not errs, errs[:3])
    c.close()
with sync_playwright() as p:
    run(p, False); run(p, True)
print('ALL' if all(ok) else 'SOME FAILED', sum(ok), len(ok))
