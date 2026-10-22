import json, sys, time
from playwright.sync_api import sync_playwright
BASE='http://localhost:4181'
ADDON='https://localhost:4180/manifest.json'
BROKEN='https://localhost:4180/broken/manifest.json'
import os
SP=os.environ.get('SHOTS', os.path.dirname(os.path.abspath(__file__)) + '/shots')
os.makedirs(SP, exist_ok=True)
class Ctx:
    def __init__(s, p, w=1280, h=800, mobile=False, storage=None):
        s.browser=p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required'])
        kw=dict(viewport={'width':w,'height':h}, ignore_https_errors=True)
        if mobile: kw.update(is_mobile=True, has_touch=True, device_scale_factor=2)
        if storage: kw['storage_state']=storage
        s.ctx=s.browser.new_context(**kw)
        s.errors=[]
        s.page=s.ctx.new_page()
        s.watch(s.page)
    def watch(s, page):
        page.on('console', lambda m: s.errors.append(f'[{m.type}] {m.text}') if m.type in ('error',) or 'Content Security' in m.text or 'Refused' in m.text else None)
        page.on('pageerror', lambda e: s.errors.append(f'[pageerror] {e}'))
    def close(s): s.browser.close()
def shot(page, name):
    path=f'{SP}/liuying-web-{name}.png'; page.screenshot(path=path); return path

def idb_get(page, key):
    return page.evaluate("""(key) => new Promise((res) => { const r = indexedDB.open('liuying-web'); r.onsuccess = () => { const g = r.result.transaction('kv').objectStore('kv').get(key); g.onsuccess = () => res(g.result ? g.result.v : null); g.onerror = () => res(null); }; r.onerror = () => res(null); })""", key)

def add_source(page, url):
    page.goto(BASE+'/watch/#/sources?add=1'); page.wait_for_selector('#src-url')
    page.fill('#src-url', url); page.click('button:has-text("Continue")'); page.wait_for_selector('.preview .preview', timeout=10000)
    page.click('.preview .preview button:has-text("Install"), .preview .preview button:has-text("Replace")'); page.wait_for_timeout(300)

IGNORED = ('401 (Unauthorized)', '500 (Internal Server Error)', 'status of 404')
def real_errors(errs, ignore=IGNORED):
    return [e for e in errs if not any(i in e for i in ignore)]

def wait_js(page, expr, timeout=15000, step=200):
    t=time.time()
    while (time.time()-t)*1000<timeout:
        try:
            if page.evaluate(expr): return True
        except Exception: pass
        page.wait_for_timeout(step)
    return False

def idb_put(page, key, value):
    page.evaluate("""([key, val]) => new Promise((res) => { const r = indexedDB.open('liuying-web'); r.onsuccess = () => { const tx = r.result.transaction('kv','readwrite'); tx.objectStore('kv').put({t: Date.now() + 1e6, v: val}, key); tx.oncomplete = () => res(true); }; })""", [key, value])

def seed(page):
    now=int(time.time()*1000); A='https://localhost:4180/art/poster/'
    def lib(i,t,n): return {'id':i,'type':t,'name':n,'poster':f'{A}{i.replace(":","%3A")}.svg','addedAt':now-1000*len(i)}
    idb_put(page,'library',[lib('mock:m1','movie','The Moon Gate'),lib('mock:m2','movie','Mock Movie 2'),lib('mock:m3','movie','Mock Movie 3'),lib('mock:s1','series','Flowing Shadows'),lib('mock:s2','series','Mock Series 2')])
    prog={}
    def pr(m,e,pos,dur,w,ago,**kw):
        r={'mediaId':m,'episodeId':e,'positionMs':pos,'durationMs':dur,'watched':w,'dismissed':False,'updatedAt':now-ago}; r.update(kw); prog[f'{m}\u0000{e or ""}']=r
    pr('mock:m3',None,3600000,9000000,False,1000,type='movie',name='Mock Movie 3',poster=f'{A}mock%3Am3.svg')
    pr('mock:s1','mock:s1:1:1',0,150000,True,5000,type='series',name='Flowing Shadows')
    pr('mock:m2',None,0,150000,True,90000,type='movie',name='Mock Movie 2')
    idb_put(page,'progress',prog)

def mkctx(p, mobile):
    return Ctx(p, 390, 844, mobile=True) if mobile else Ctx(p, 1280, 800)
