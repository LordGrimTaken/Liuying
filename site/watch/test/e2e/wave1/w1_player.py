from wt_common import *
ok=[]
def check(name, cond, extra=''):
    print(('PASS ' if cond else 'FAIL ')+name, extra if not cond else ''); ok.append(bool(cond))
def vt(pg): return pg.evaluate("document.querySelector('video').currentTime")
with sync_playwright() as p:
    c=Ctx(p); pg=c.page
    pg.goto(BASE+'/watch'); add_source(pg, ADDON)
    pg.goto(BASE+'/watch#/streams/series/mock:s1?v=mock:s1:1:2'); pg.wait_for_selector('button.srow', timeout=10000)
    pg.click('button.srow:has-text("Mock 1080p")'); pg.wait_for_selector('.player video')
    check('plays', wait_js(pg,"document.querySelector('video').currentTime>0.5",15000))
    check('prev and next buttons present', pg.locator('.pl-bot [aria-label*="revious episode"]').count()==1 and pg.locator('.pl-bot [aria-label*="ext episode"]').count()==1)
    t=vt(pg); pg.keyboard.press('s'); pg.wait_for_timeout(400)
    check('S long skip', vt(pg)>t+30 or vt(pg)>=140, (t, vt(pg)))
    pg.keyboard.press('i'); pg.wait_for_timeout(1300)
    check('stats overlay', pg.is_visible('.pl-stats') and 'Resolution' in pg.inner_text('.pl-stats'), pg.inner_text('.player')[:200])
    shot(pg,'w1-player-stats')
    pg.keyboard.press('i'); pg.wait_for_timeout(200)
    check('stats off', not pg.is_visible('.pl-stats'))
    # panel: streams tab
    pg.mouse.move(400,400); pg.click('.pl-top button:has-text("Streams")'); pg.wait_for_timeout(1500)
    check('streams tab lists streams', pg.locator('.pl-panel .opt').count()>=2, pg.inner_text('.pl-panel')[:300])
    shot(pg,'w1-player-streams')
    print(pg.inner_text('.pl-panel')[:400]); pg.locator('.pl-panel .opt:has-text("Mock 1080p")').first.click(); pg.wait_for_timeout(2500)
    check('switched stream and still playing', wait_js(pg,"document.querySelector('video').currentTime>0.5 && !document.querySelector('video').paused",10000), pg.inner_text('.pl-toast'))
    # playback tab: sleep timer
    pg.mouse.move(300,300)
    pg.keyboard.press('Escape') if pg.is_visible('.pl-panel.open') else None
    pg.click('.pl-top button[aria-label*="ettings"], .pl-top button[aria-label*="ubtitles"]') if False else None
    pg.evaluate("document.querySelector('.sleepchip')")
    pg.mouse.move(400,400); pg.click('.pl-top button:has-text("Streams")'); pg.wait_for_timeout(300)
    pg.click('.pl-panel [role=tab]:has-text("Playback")'); pg.wait_for_timeout(200)
    pg.click('.pl-panel .opt:has-text("15 minutes")'); pg.wait_for_timeout(300)
    check('sleep chip shows', pg.is_visible('.sleepchip') and ':' in pg.inner_text('.sleepchip'), pg.inner_text('.pl-top'))
    pg.click('.pl-panel .opt:has-text("Off")'); pg.wait_for_timeout(200)
    check('sleep chip off', not pg.is_visible('.sleepchip'))
    pg.click('.pl-panel [role=tab]:has-text("Style")'); pg.wait_for_timeout(200)
    pg.click('.pl-panel .presets .chip:has-text("Yellow")'); pg.wait_for_timeout(200)
    check('preset applied', pg.get_attribute('.pl-panel .presets .chip:has-text("Yellow")','aria-pressed')=='true')
    pg.keyboard.press('Escape'); pg.wait_for_timeout(300)
    # prev episode
    pg.keyboard.press('Shift+P'); pg.wait_for_timeout(2500)
    check('prev episode goes to S1E1 playback or streams', 'mock%3As1%3A1%3A1' in pg.url or 'mock:s1:1:1' in pg.url, pg.url)
    shot(pg,'w1-player-end')
    errs=real_errors(c.errors); check('no console errors', not errs, errs[:3])
    c.close()
print('ALL' if all(ok) else 'SOME FAILED', sum(ok), len(ok))
