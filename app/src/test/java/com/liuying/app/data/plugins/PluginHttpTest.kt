package com.liuying.app.data.plugins

import com.liuying.app.domain.plugins.PluginRunException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The network door, tested with a canned transport (no sockets): the URL rules run exactly as in the app. */
class PluginHttpTest {
    private val seen = ArrayList<Request>()

    private fun http(handler: (Request) -> Response): PluginHttp {
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { chain -> seen += chain.request(); handler(chain.request()) }).build()
        return PluginHttp("Liuying/test (Android)", client)
    }

    private fun resp(req: Request, code: Int = 200, body: String = "ok", vararg h: Pair<String, String>, type: String = "text/plain; charset=utf-8"): Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "X")
            .apply { h.forEach { addHeader(it.first, it.second) } }.body(body.toResponseBody(type.toMediaType())).build()

    private fun failure(block: suspend () -> Any?): PluginRunException = try { runBlocking { block() }; throw AssertionError("expected a failure") } catch (e: PluginRunException) { e }

    @Test fun fetchesAndSendsTheLiuyingUserAgent() {
        val s = http { resp(it, body = "hello") }.newSession()
        val r = runBlocking { s.fetch(PluginHttp.FetchSpec("https://example.com/a")) }
        assertEquals(200, r.status); assertEquals("hello", r.body)
        assertEquals("Liuying/test (Android)", seen.single().header("User-Agent"))
    }

    @Test fun pluginHeadersAreForwardedButHostAndFramingOnesAreDropped() {
        val s = http { resp(it) }.newSession()
        runBlocking { s.fetch(PluginHttp.FetchSpec("https://example.com/", headers = mapOf("Referer" to "https://r.example.com/", "Host" to "evil.test", "Content-Length" to "5", "Accept-Encoding" to "br"))) }
        val q = seen.single()
        assertEquals("https://r.example.com/", q.header("Referer")); assertEquals(null, q.header("Host")); assertEquals(null, q.header("Content-Length"))
    }

    @Test fun blocksPrivateTargetsBeforeAnyRequestIsMade() {
        val s = http { resp(it) }.newSession()
        listOf("http://127.0.0.1/", "http://localhost:8080/", "http://192.168.1.1/", "http://169.254.169.254/", "file:///etc/passwd", "http://[::1]/").forEach { u ->
            val e = failure { s.fetch(PluginHttp.FetchSpec(u)) }
            assertEquals(u, PluginRunException.Kind.Blocked, e.kind)
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun followsUpToFiveRedirectsThenStops() {
        // /1 -> /2 ... : hop n answers with a redirect to hop n+1
        val s = http { r ->
            val n = r.url.encodedPath.trim('/').toIntOrNull() ?: 0
            if (n < 5) resp(r, 302, "", "Location" to "/${n + 1}") else resp(r, 200, "end")
        }.newSession()
        assertEquals("end", runBlocking { s.fetch(PluginHttp.FetchSpec("https://example.com/0")) }.body) // exactly 5 redirects: allowed

        seen.clear()
        val loop = http { resp(it, 302, "", "Location" to "/again") }.newSession()
        val e = failure { loop.fetch(PluginHttp.FetchSpec("https://example.com/")) }
        assertTrue(e.message!!, e.message!!.contains("Too many redirects"))
        assertEquals(6, seen.size) // the original request plus five redirects, the sixth redirect is refused
    }

    @Test fun refusesARedirectIntoAPrivateNetwork() {
        val s = http { resp(it, 302, "", "Location" to "http://192.168.0.1/admin") }.newSession()
        val e = failure { s.fetch(PluginHttp.FetchSpec("https://example.com/")) }
        assertEquals(PluginRunException.Kind.Blocked, e.kind)
        assertEquals(1, seen.size)
    }

    @Test fun stripsCredentialsOnCrossHostRedirectAndDowngradesPostOn302() {
        val s = http { r -> if (r.url.host == "a.example.com") resp(r, 302, "", "Location" to "https://b.example.org/x") else resp(r) }.newSession()
        runBlocking { s.fetch(PluginHttp.FetchSpec("https://a.example.com/", "POST", mapOf("Authorization" to "Bearer t", "Cookie" to "k=v", "Content-Type" to "text/plain"), "data")) }
        val second = seen[1]
        assertEquals(null, second.header("Authorization")); assertEquals(null, second.header("Cookie")); assertEquals("GET", second.method)
    }

    @Test fun capsResponseSizeAtFiveMegabytes() {
        val big = "a".repeat(5 * 1024 * 1024 + 10)
        val e = failure { http { resp(it, body = big) }.newSession().fetch(PluginHttp.FetchSpec("https://example.com/")) }
        assertTrue(e.message!!.contains("5 MB"))
        val exactly = "a".repeat(5 * 1024 * 1024)
        assertEquals(exactly.length, runBlocking { http { resp(it, body = exactly) }.newSession().fetch(PluginHttp.FetchSpec("https://example.com/")) }.body.length)
    }

    @Test fun capsRequestsPerRun() {
        val s = http { resp(it) }.newSession()
        repeat(30) { runBlocking { s.fetch(PluginHttp.FetchSpec("https://example.com/$it")) } }
        val e = failure { s.fetch(PluginHttp.FetchSpec("https://example.com/31")) }
        assertTrue(e.message!!.contains("Too many web requests"))
        assertEquals(30, seen.size)
        // a new run starts with a fresh budget
        assertEquals(200, runBlocking { http { resp(it) }.newSession().fetch(PluginHttp.FetchSpec("https://example.com/")) }.status)
    }

    @Test fun redirectHopsCountAgainstTheRequestCap() {
        val s = http { r -> if (r.url.encodedPath == "/") resp(r, 302, "", "Location" to "/x") else resp(r) }.newSession(maxRequests = 3)
        runBlocking { s.fetch(PluginHttp.FetchSpec("https://example.com/")) } // 2 requests
        val e = failure { s.fetch(PluginHttp.FetchSpec("https://example.com/")) } // third passes, fourth is over
        assertTrue(e.message!!.contains("Too many web requests"))
    }

    @Test fun cookiesAreNeverExposed() {
        val s = http { resp(it, 200, "x", "Set-Cookie" to "sid=1", "X-A" to "1", "X-A" to "2") }.newSession()
        val r = runBlocking { s.fetch(PluginHttp.FetchSpec("https://example.com/")) }
        assertFalse(r.headers.containsKey("set-cookie")); assertEquals("1, 2", r.headers["x-a"])
        assertFalse(PluginHttp.defaultClient().cookieJar.loadForRequest(okhttp3.HttpUrl.Builder().scheme("https").host("example.com").build()).isNotEmpty())
    }

    @Test fun defaultClientDoesNotFollowRedirectsOrCache() {
        val c = PluginHttp.defaultClient()
        assertFalse(c.followRedirects); assertFalse(c.followSslRedirects); assertEquals(null, c.cache)
        assertEquals(15_000, c.callTimeoutMillis)
    }

    @Test fun badMethodsAndHeadersAreReportedNotThrownIntoTheApp() {
        val s = http { resp(it) }.newSession()
        assertEquals(PluginRunException.Kind.Blocked, failure { s.fetch(PluginHttp.FetchSpec("https://example.com/", "CONNECT")) }.kind)
        val out = Json.parseToJsonElement(runBlocking { s.fetchJson("""{"url":"https://example.com/","headers":{"Bad Header":"x\ny"}}""") }).jsonObject
        assertTrue(out.containsKey("error"))
        val blocked = Json.parseToJsonElement(runBlocking { s.fetchJson("""{"url":"http://127.0.0.1/"}""") }).jsonObject
        assertTrue(blocked["error"]!!.jsonPrimitive.content.contains("private"))
    }
}
