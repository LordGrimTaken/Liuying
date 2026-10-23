package com.liuying.app.data.plugins

import com.liuying.app.domain.plugins.PluginLimits
import com.liuying.app.domain.plugins.PluginRunException
import com.liuying.app.domain.plugins.PluginUrlSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The only network door a plugin has. Owns its own OkHttp client: no cookie jar, no cache, no interceptors from the app,
 * redirects followed by hand so every hop is re-checked. [newSession] gives each run its own request budget.
 */
class PluginHttp(
    private val userAgent: String,
    /** Replaceable in tests (the default refuses loopback, which is where MockWebServer lives). */
    private val client: OkHttpClient = defaultClient(),
) {
    fun newSession(maxRequests: Int = PluginLimits.MAX_REQUESTS): Session = Session(maxRequests)

    inner class Session(private val maxRequests: Int) {
        private val used = AtomicInteger()

        /** The JSON the JS shim expects: `{status,statusText,url,headers,body}` or `{error}`. Never throws except on cancellation. */
        suspend fun fetchJson(specJson: String): String = try {
            val r = fetch(parseSpec(specJson))
            buildJsonObject {
                put("status", r.status); put("statusText", r.statusText); put("url", r.url)
                put("headers", JsonObject(r.headers.mapValues { JsonPrimitive(it.value) })); put("body", r.body)
            }.toString().let(PluginModules::asciiJson)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            buildJsonObject { put("error", (e.message ?: e.javaClass.simpleName).take(300)) }.toString().let(PluginModules::asciiJson)
        }

        suspend fun fetch(spec: FetchSpec): FetchResult {
            val method = spec.method.uppercase()
            if (method !in ALLOWED_METHODS) throw PluginRunException("HTTP method $method is not allowed.", PluginRunException.Kind.Blocked)
            var url = when (val v = PluginUrlSafety.check(spec.url)) {
                is PluginUrlSafety.Verdict.Allowed -> v.url
                is PluginUrlSafety.Verdict.Blocked -> throw PluginRunException(v.reason, PluginRunException.Kind.Blocked)
            }
            var m = method
            var body = spec.body
            var headers = spec.headers.filterKeys { it.lowercase() !in FORBIDDEN_HEADERS }
            if (body != null && body.toByteArray().size > PluginLimits.MAX_REQUEST_BODY_BYTES) throw PluginRunException("The request body is too large.")
            var hops = 0
            while (true) {
                if (used.incrementAndGet() > maxRequests) throw PluginRunException("Too many web requests (limit $maxRequests per run).")
                val res = withContext(Dispatchers.IO) { once(url, m, headers, body) }
                if (res.redirect == null) return res.result
                if (++hops > PluginUrlSafety.MAX_REDIRECTS) throw PluginRunException("Too many redirects.")
                val next = when (val v = PluginUrlSafety.redirect(url, res.redirect)) {
                    is PluginUrlSafety.Verdict.Allowed -> v.url
                    is PluginUrlSafety.Verdict.Blocked -> throw PluginRunException("Redirect refused: ${v.reason}", PluginRunException.Kind.Blocked)
                }
                if (res.result.status in setOf(301, 302, 303) && m != "GET" && m != "HEAD") { m = "GET"; body = null; headers = headers.filterKeys { it.lowercase() != "content-type" } }
                if (next.host != url.host || next.scheme != url.scheme || next.port != url.port) headers = headers.filterKeys { it.lowercase() !in setOf("authorization", "cookie") }
                url = next
            }
        }

        private suspend fun once(url: HttpUrl, method: String, headers: Map<String, String>, body: String?): Hop {
            val rb = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "*/*")
            headers.forEach { (k, v) -> rb.header(k, v) } // invalid names or values throw IllegalArgumentException, reported as a fetch error
            val ct = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
            val payload = when {
                method == "GET" || method == "HEAD" -> null
                else -> (body ?: "").toRequestBody(ct?.toMediaTypeOrNull() ?: "text/plain; charset=utf-8".toMediaTypeOrNull())
            }
            rb.method(method, payload)
            val call = client.newCall(rb.build())
            // blocking OkHttp I/O is not cancellable by itself: a watcher child cancels the call when this coroutine is cancelled
            return coroutineScope {
                val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
                try { exec(call, url, method) } finally { watcher.cancel() }
            }
        }

        private fun exec(call: okhttp3.Call, url: HttpUrl, method: String): Hop {
            return run {
                call.execute().use { resp ->
                    val hdrs = LinkedHashMap<String, String>()
                    for (i in 0 until resp.headers.size) {
                        val k = resp.headers.name(i).lowercase()
                        if (k == "set-cookie") continue // the plugin never sees cookies
                        hdrs[k] = hdrs[k]?.let { it + ", " + resp.headers.value(i) } ?: resp.headers.value(i)
                    }
                    val loc = resp.header("Location")
                    val isRedirect = resp.code in setOf(301, 302, 303, 307, 308) && loc != null
                    val text = if (isRedirect || method == "HEAD") "" else readCapped(resp)
                    Hop(FetchResult(resp.code, resp.message, url.toString(), hdrs, text), if (isRedirect) loc else null)
                }
            }
        }

        private fun readCapped(resp: okhttp3.Response): String {
            val b = resp.body ?: return ""
            val len = b.contentLength()
            if (len > PluginLimits.MAX_RESPONSE_BYTES) throw PluginRunException("The response is larger than 5 MB.")
            val src = b.source()
            val buf = Buffer()
            while (buf.size <= PluginLimits.MAX_RESPONSE_BYTES) { if (src.read(buf, 8192) == -1L) break }
            if (buf.size > PluginLimits.MAX_RESPONSE_BYTES) throw PluginRunException("The response is larger than 5 MB.")
            val cs: Charset = b.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            return buf.readString(cs)
        }
    }

    private class Hop(val result: FetchResult, val redirect: String?)

    data class FetchSpec(val url: String, val method: String = "GET", val headers: Map<String, String> = emptyMap(), val body: String? = null)
    data class FetchResult(val status: Int, val statusText: String, val url: String, val headers: Map<String, String>, val body: String)

    companion object {
        private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding", "upgrade", "te", "proxy-authorization", "proxy-connection", "expect")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .cache(null).cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(PluginLimits.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .dns(SafeDns).build()

        fun parseSpec(raw: String): FetchSpec {
            val o = try { json.parseToJsonElement(raw) as JsonObject } catch (e: Exception) { throw PluginRunException("fetch received a bad request.") }
            val headers = (o["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            return FetchSpec(
                url = (o["url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                method = (o["method"] as? JsonPrimitive)?.contentOrNull ?: "GET",
                headers = headers,
                body = (o["body"] as? JsonPrimitive)?.takeIf { it.booleanOrNull == null || it.isString }?.contentOrNull,
            )
        }
    }
}
