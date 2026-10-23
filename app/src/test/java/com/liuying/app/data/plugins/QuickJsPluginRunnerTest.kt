package com.liuying.app.data.plugins

import com.liuying.app.domain.plugins.PluginInfo
import com.liuying.app.domain.plugins.PluginRunException
import com.liuying.app.domain.plugins.PluginRunRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs fixture plugins in the real QuickJS engine (desktop build of the same library) with a fake transport behind
 * the host `fetch`. Device behaviour of the Android build of the engine is covered by the manual checklist in docs/plugins.md.
 */
class QuickJsPluginRunnerTest {
    private val requests = ArrayList<Request>()

    private fun runner(script: String, timeoutMs: Long = 20_000, answer: (Request) -> Response = { ok(it, "{}") }): QuickJsPluginRunner {
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { c -> requests += c.request(); answer(c.request()) }).build()
        return QuickJsPluginRunner(PluginHttp("Liuying/test (Android)", client), loadScript = { script }, timeoutMs = timeoutMs)
    }

    private fun ok(req: Request, body: String, type: String = "application/json") =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody(type.toMediaType())).build()

    private val info = PluginInfo("fx", "Fixture", "1", "", "https://repo.example.com/fx.js", emptyList(), emptyList())
    private fun run(r: QuickJsPluginRunner, req: PluginRunRequest = PluginRunRequest("603", "movie")) = runBlocking { r.run(info, req) }

    @Test fun runsAFixturePluginThatUsesFetch() {
        val script = """
            async function getStreams(tmdbId, mediaType, season, episode) {
              const res = await fetch('https://api.example.com/streams?id=' + encodeURIComponent(tmdbId) + '&t=' + mediaType, { headers: { 'X-Test': '1' } });
              if (!res.ok) throw new Error('HTTP ' + res.status);
              const data = await res.json();
              return data.items.map(function (i) { return { name: 'Fixture', title: i.title + ' ' + mediaType + ' ' + tmdbId, url: i.url, quality: i.q, headers: { Referer: 'https://api.example.com/' } }; });
            }
            module.exports = { getStreams };
        """
        val r = run(runner(script) { ok(it, """{"items":[{"title":"A","url":"https://cdn.example.com/a.m3u8","q":"1080p"},{"title":"B","url":"https://cdn.example.com/b.mp4","q":"720p"}]}""") })
        val streams = r.getOrThrow()
        assertEquals(2, streams.size)
        assertEquals("A movie 603", streams[0].title); assertEquals("1080p", streams[0].quality)
        assertEquals(mapOf("Referer" to "https://api.example.com/"), streams[0].headers)
        assertEquals("https://api.example.com/streams?id=603&t=movie", requests.single().url.toString())
        assertEquals("1", requests.single().header("X-Test")); assertEquals("Liuying/test (Android)", requests.single().header("User-Agent"))
    }

    @Test fun passesSeasonAndEpisodeAndSupportsExportsAssignment() {
        val script = "exports.getStreams = function (id, type, s, e) { return Promise.resolve([{ name: type + ' ' + s + 'x' + e, url: 'https://c.example.com/' + id }]); };"
        val s = run(runner(script), PluginRunRequest("1396", "tv", 2, 7)).getOrThrow()
        assertEquals("tv 2x7", s.single().name)
    }

    @Test fun movieCallGetsUndefinedSeasonAndEpisode() {
        val script = "module.exports.getStreams = (id, type, s, e) => [{ name: String(typeof s) + String(typeof e), url: 'https://c.example.com/x' }];"
        assertEquals("undefinedundefined", run(runner(script)).getOrThrow().single().name)
    }

    @Test fun aSyncResultWorksToo() {
        assertEquals(1, run(runner("module.exports = { getStreams: () => [{ url: 'https://c.example.com/x' }] };")).getOrThrow().size)
    }

    @Test fun pluginErrorsBecomeFailuresWithTheirMessage() {
        val e1 = run(runner("module.exports = { getStreams: async () => { throw new Error('upstream is down'); } };")).exceptionOrNull()!!
        assertTrue(e1.message!!, e1.message!!.contains("upstream is down"))
        val e2 = run(runner("module.exports = { getStreams: () => { null.x; } };")).exceptionOrNull()!!
        assertTrue(e2 is PluginRunException)
        val e3 = run(runner("this is not javascript (")).exceptionOrNull()!!
        assertTrue(e3.message!!, e3.message!!.startsWith("Script error"))
    }

    @Test fun missingExportAndNoResultAreReported() {
        assertTrue(run(runner("var x = 1;")).exceptionOrNull()!!.message!!.contains("getStreams"))
        val hang = run(runner("module.exports = { getStreams: () => new Promise(function () {}) };")).exceptionOrNull()!!
        assertTrue(hang.message!!, hang.message!!.contains("without giving an answer"))
        assertTrue(run(runner("module.exports = { getStreams: () => 42 };")).isFailure)
    }

    @Test fun blockedFetchIsAnErrorTheScriptCanCatchAndNoRequestIsMade() {
        val script = """
            module.exports = { getStreams: async () => {
              const out = [];
              for (const u of ['http://127.0.0.1:8080/', 'http://localhost/x', 'http://192.168.1.1/', 'http://169.254.169.254/latest', 'file:///etc/passwd']) {
                try { await fetch(u); out.push('LEAK ' + u); } catch (e) { out.push('blocked'); }
              }
              return [{ name: out.join(','), url: 'https://c.example.com/x' }];
            } };
        """
        val r = run(runner(script)).getOrThrow()
        assertEquals("blocked,blocked,blocked,blocked,blocked", r.single().name)
        assertTrue(requests.isEmpty())
    }

    @Test fun perRunRequestCapStopsTheThirtyFirstFetch() {
        val script = """
            module.exports = { getStreams: async () => {
              let n = 0, err = '';
              for (let i = 0; i < 40; i++) { try { await fetch('https://a.example.com/' + i); n++; } catch (e) { err = String(e.message); break; } }
              return [{ name: n + '|' + err, url: 'https://c.example.com/x' }];
            } };
        """
        val name = run(runner(script)).getOrThrow().single().name
        assertTrue(name, name.startsWith("30|") && name.contains("Too many web requests"))
        assertEquals(30, requests.size)
    }

    @Test fun shimsBehaveLikeTheirWebCounterparts() {
        val script = """
            module.exports = { getStreams: () => {
              const u = new URL('https://User:pw@Example.com:8443/a/b/../c?x=1&y=a%20b#frag');
              const rel = new URL('../z?q=2', 'https://example.com/a/b/c');
              const p = new URLSearchParams({ a: '1 2', b: 'x&y' }); p.append('a', '3');
              const out = [
                btoa('Hello, World!'), atob('SGVsbG8sIFdvcmxkIQ=='), atob('YQ'), btoa(''),
                u.hostname, u.port, u.pathname, u.search, u.hash, u.origin, u.searchParams.get('y'), u.username,
                rel.href, p.toString(), p.getAll('a').join('+'), String(new URLSearchParams('?k=v+w').get('k')),
                new URL('/p', 'http://h.example.com').href, new URL('http://h.example.com:80/').port === '' ? 'dp' : 'np'
              ];
              let bad = 'no'; try { new URL('not a url'); } catch (e) { bad = 'throws'; }
              out.push(bad);
              return [{ title: out.join('|'), url: 'https://c.example.com/x' }];
            } };
        """
        val got = run(runner(script)).getOrThrow().single().title!!
        assertEquals(
            "SGVsbG8sIFdvcmxkIQ==|Hello, World!|a||example.com|8443|/a/c|?x=1&y=a%20b|#frag|https://example.com:8443|a b|User|" +
                "https://example.com/a/z?q=2|a=1+2&b=x%26y&a=3|1 2+3|v w|http://h.example.com/p|dp|throws", got,
        )
    }

    @Test fun consoleAndTimersWork() {
        val script = """
            module.exports = { getStreams: async () => {
              console.log('hi', { a: 1 }); console.error('e');
              await new Promise(function (r) { setTimeout(r, 30); });
              return [{ name: 'after-timer', url: 'https://c.example.com/x' }];
            } };
        """
        assertEquals("after-timer", run(runner(script)).getOrThrow().single().name)
    }

    @Test fun theSandboxHasNoHostGlobalsAndNoAppData() {
        val script = """
            module.exports = { getStreams: () => {
              const names = ['__hostFetch', '__hostLog', '__hostSleep', '__hostResolve', '__hostReject', 'process', 'Android', 'android', 'java', 'Packages', 'XMLHttpRequest', 'WebSocket', 'localStorage', 'document'];
              return [{ name: names.map(function (n) { return typeof globalThis[n]; }).join(','), url: 'https://c.example.com/x' }];
            } };
        """
        val name = run(runner(script)).getOrThrow().single().name
        assertEquals(List(14) { "undefined" }.joinToString(","), name)
    }

    @Test fun eachRunGetsAFreshEngine() {
        val r = runner("globalThis.counter = (globalThis.counter || 0) + 1; module.exports = { getStreams: () => [{ name: 'c' + globalThis.counter, url: 'https://c.example.com/x' }] };")
        assertEquals("c1", run(r).getOrThrow().single().name)
        assertEquals("c1", run(r).getOrThrow().single().name)
    }

    @Test fun anInfiniteLoopIsStoppedByTheWallClockLimit() {
        val t0 = System.currentTimeMillis()
        val e = run(runner("module.exports = { getStreams: () => { while (true) {} } };", timeoutMs = 1500)).exceptionOrNull() as PluginRunException
        val took = System.currentTimeMillis() - t0
        assertEquals(PluginRunException.Kind.Timeout, e.kind)
        assertTrue("returned after ${took}ms", took < 8_000)
    }

    @Test fun aSlowFetchIsStoppedByTheWallClockLimit() {
        val slow = runner("module.exports = { getStreams: async () => { await fetch('https://slow.example.com/'); return []; } };", timeoutMs = 800) {
            Thread.sleep(5_000); ok(it, "{}")
        }
        val e = run(slow).exceptionOrNull() as PluginRunException
        assertEquals(PluginRunException.Kind.Timeout, e.kind)
    }

    @Test fun runawayMemoryIsAFailureNotACrash() {
        val e = run(runner("module.exports = { getStreams: () => { const a = []; while (true) a.push('x'.repeat(1024 * 1024) + a.length); } };", timeoutMs = 10_000)).exceptionOrNull()!!
        assertTrue(e.message!!, e is PluginRunException)
    }

    private fun nameOf(script: String, answer: (Request) -> Response = { ok(it, "{}") }) = run(runner(script, answer = answer)).getOrThrow().single().name

    @Test fun anAwaitedJsonParseFailureIsCatchableByThePlugin() {
        val script = "module.exports={getStreams: async function(){ try { const r = await fetch('https://a.example.com/x'); await r.json(); return [{name:'no',url:'https://c.example.com/x'}]; } catch(e){ return [{name:'caught '+e.name,url:'https://c.example.com/x'}]; } }};"
        assertEquals("caught SyntaxError", nameOf(script) { ok(it, "<html>nope</html>", "text/html") })
        val gen = """
          var __async=(t,a,g)=>new Promise((res,rej)=>{var ok=v=>{try{st(g.next(v))}catch(e){rej(e)}},er=v=>{try{st(g.throw(v))}catch(e){rej(e)}},st=x=>x.done?res(x.value):Promise.resolve(x.value).then(ok,er);st((g=g.apply(t,a)).next())});
          function getStreams(){return __async(this,null,function*(){try{const r=yield fetch('https://a.example.com/x');const j=yield r.json();return [j]}catch(e){return []}})}
          module.exports={getStreams:getStreams};"""
        assertTrue(run(runner(gen) { ok(it, "", "text/html") }).getOrThrow().isEmpty())
    }

    @Test fun anUncaughtJsonFailureIsAReadableFailureNotASilentOne() {
        val e = run(runner("module.exports={getStreams: async function(){ const r = await fetch('https://a.example.com/x'); return await r.json(); }};") { ok(it, "<html>", "text/html") }).exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.contains("SyntaxError"))
    }

    @Test fun nonAsciiAndEmojiSurviveBothDirections() {
        val script = "module.exports={getStreams: async function(){ " +
            "const r = await fetch('https://a.example.com/x', { method: 'POST', body: '\\ud83c\\udfac\\u4e2d' }); const j = await r.json(); " +
            "return [{name: j.t + ' \\ud83c\\udfac \\u4e2d\\u6587', title: '\\u00e9', url: 'https://c.example.com/x'}]; }};"
        val got = nameOf(script) { ok(it, "{\"t\":\"\\ud83d\\udcfa \\u00fc \\u4e2d\"}") }
        assertEquals("\uD83D\uDCFA \u00fc \u4e2d \uD83C\uDFAC \u4e2d\u6587", got)
        assertEquals("\uD83C\uDFAC\u4e2d", requests.last().let { okio.Buffer().also { b -> it.body!!.writeTo(b) }.readUtf8() })
    }

    @Test fun emojiInTheScriptSourceIsFine() {
        assertEquals("\uD83C\uDFAC x", nameOf("module.exports={getStreams: ()=>[{name:'\uD83C\uDFAC x',url:'https://c.example.com/x'}]};"))
    }

    @Test fun aLeftoverTimerDoesNotDelayTheAnswer() {
        val t0 = System.currentTimeMillis()
        val n = nameOf("module.exports={getStreams: async()=>{ setTimeout(function(){}, 4000); return [{name:'quick',url:'https://c.example.com/x'}]; }};")
        assertEquals("quick", n); assertTrue("took ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 3000)
    }

    @Test fun aPluginThatNeverAnswersSaysWhatItWasWaitingFor() {
        val e = run(runner("module.exports={getStreams: ()=> fetch('https://slow.example.com/x').then(function(){ return new Promise(function(){}); })};")).exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.contains("without giving an answer") && e.message!!.contains("slow.example.com") && e.message!!.contains("HTTP 200"))
        assertTrue(run(runner("module.exports={getStreams: ()=>[]};")).getOrThrow().isEmpty())
    }

    @Test fun bufferTextEncodingAndHeadersShims() {
        val script = """
          module.exports={getStreams: ()=>{
            const b = Buffer.from('h\u00e9llo');
            const out = [
              b.toString('hex'), b.toString('base64'), Buffer.from(b.toString('base64'), 'base64').length, Buffer.from('ff00', 'hex').length,
              new TextDecoder().decode(new TextEncoder().encode('ab')), Buffer.concat([Buffer.from('a'), Buffer.from('b')]).toString(),
              Buffer.isBuffer(b), new Headers({A:'1'}).get('a'), typeof AbortController, typeof queueMicrotask, typeof SCRAPER_SETTINGS,
              'a-b-c'.replaceAll('-', '+'), [1,2,3].at(-1), Object.fromEntries([['k', 1]]).k,
              new TextEncoder().encode('\ud83c\udfac').length, new TextDecoder().decode(new Uint8Array([0xf0,0x9f,0x8e,0xac])).length
            ];
            return [{name: out.join('|'), url:'https://c.example.com/x'}];
          }};"""
        assertEquals("68c3a96c6c6f|aMOpbGxv|6|2|ab|ab|true|1|function|function|object|a+b+c|3|1|4|2", nameOf(script))
    }

    @Test fun abortSignalCancelsAFetchInFlight() {
        val script = """
          module.exports={getStreams: async()=>{
            const c = new AbortController(); setTimeout(function(){ c.abort(); }, 20);
            let r = 'none'; try { await fetch('https://slow.example.com/x', { signal: c.signal }); r = 'fetched'; } catch (e) { r = e.name; }
            let pre = 'none'; try { await fetch('https://a.example.com/', { signal: AbortSignal.abort() }); } catch (e) { pre = e.name; }
            return [{name: r + ',' + pre, url:'https://c.example.com/x'}];
          }};"""
        assertEquals("AbortError,AbortError", nameOf(script) { Thread.sleep(400); ok(it, "{}") })
    }

    @Test fun bundledModulesLoadOnDemandAndNothingElseDoes() {
        val script = """
          module.exports={getStreams: ()=>{
            const cheerio = require('cheerio-without-node-native'); const CryptoJS = require('crypto-js');
            const q = cheerio.load('<ul><li class="a">x</li><li>y</li></ul>');
            const key = CryptoJS.enc.Utf8.parse('0123456789abcdef'), iv = CryptoJS.enc.Utf8.parse('fedcba9876543210'); const enc = CryptoJS.AES.encrypt('secret', key, { iv: iv }).toString();
            let bad = 'ok'; try { require('fs'); } catch (e) { bad = 'blocked'; }
            return [{name: [q('li').length, q('li.a').text(), CryptoJS.AES.decrypt(enc, key, { iv: iv }).toString(CryptoJS.enc.Utf8), CryptoJS.MD5('a').toString(), CryptoJS.SHA256('a').toString().slice(0, 8), bad].join('|'), url:'https://c.example.com/x'}];
          }};"""
        assertEquals("2|x|secret|0cc175b9c0f1b6a831c399e269772661|ca978112|blocked", nameOf(script))
    }

    @Test fun repeatedRunsAlwaysAnswer() {
        repeat(40) { i ->
            assertEquals("sync$i", nameOf("module.exports={getStreams: ()=>[{name:'sync$i',url:'https://c.example.com/x'}]};"))
            assertEquals("net$i", nameOf("module.exports={getStreams: async()=>{ const r = await fetch('https://a.example.com/x'); const j = await r.json(); return [{name:'net$i',url:'https://c.example.com/x'}]; }};"))
        }
    }

    @Test fun requestsStillRunningWhenThePluginAnswersAreDropped() {
        val t0 = System.currentTimeMillis()
        val slow = runner("module.exports={getStreams: async()=>{ fetch('https://slow.example.com/x').catch(function(){}); return [{name:'first',url:'https://c.example.com/x'}]; }};") { Thread.sleep(5_000); ok(it, "{}") }
        assertEquals("first", run(slow).getOrThrow().single().name)
        assertTrue("took ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 3_000)
    }
}
