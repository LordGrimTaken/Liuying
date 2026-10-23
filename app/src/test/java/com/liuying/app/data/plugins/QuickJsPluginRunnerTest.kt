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
        assertTrue(hang.message!!, hang.message!!.contains("without returning"))
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
}
