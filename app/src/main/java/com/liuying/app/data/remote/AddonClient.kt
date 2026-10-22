package com.liuying.app.data.remote

import com.liuying.app.domain.model.CatalogResponse
import com.liuying.app.domain.model.Manifest
import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.MetaResponse
import com.liuying.app.domain.model.Resources
import com.liuying.app.domain.model.StremioJson
import com.liuying.app.domain.model.StreamResponse
import com.liuying.app.domain.model.SubtitlesResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** A manifest fetched from a user-supplied link. */
data class FetchedManifest(val manifestUrl: String, val manifest: Manifest, val rawJson: String)

/** Tracks consecutive failures per source base URL. 3 in a row = unhealthy (queried last, shorter timeout). */
class SourceHealth(private val threshold: Int = 3) {
    private val failures = ConcurrentHashMap<String, Int>()
    private val _unhealthy = MutableStateFlow<Set<String>>(emptySet())
    val unhealthy: StateFlow<Set<String>> = _unhealthy.asStateFlow()

    fun isUnhealthy(baseUrl: String): Boolean = (failures[baseUrl] ?: 0) >= threshold
    fun consecutiveFailures(baseUrl: String): Int = failures[baseUrl] ?: 0

    fun record(baseUrl: String, success: Boolean) {
        if (success) failures.remove(baseUrl) else failures.merge(baseUrl, 1, Int::plus)
        _unhealthy.value = failures.filterValues { it >= threshold }.keys
    }
}

/**
 * HTTP client for Stremio addons. Every public suspend function returns a [Result] whose failure is an
 * [AddonFailure]; nothing is thrown at callers except coroutine cancellation.
 *
 * @param timeoutScale multiplies every timeout (user setting: normal=1.0 / patient=2.0).
 */
class AddonClient(
    http: OkHttpClient,
    private val json: kotlinx.serialization.json.Json = StremioJson,
    private val timeoutScale: () -> Double = { 1.0 },
    val health: SourceHealth = SourceHealth(),
    private val userAgent: String = "Liuying/0.1 (Android)",
) {
    private val client: OkHttpClient = http

    // -------------------------------------------------------------------------------- manifest

    /** Normalizes [input] (stremio://, bare host, ...) and fetches + validates the manifest. */
    suspend fun fetchManifest(input: String, force: Boolean = true): Result<FetchedManifest> {
        val url = AddonUrls.normalizeManifestUrl(input).getOrElse { return Result.failure(it) }
        return fetchManifestFromUrl(url, force)
    }

    suspend fun fetchManifestFromUrl(manifestUrl: String, force: Boolean = false): Result<FetchedManifest> {
        val body = get(manifestUrl, MANIFEST_TIMEOUT_MS, force, cacheable = true).getOrElse { return Result.failure(it) }
        return parseManifest(manifestUrl, body)
    }

    internal fun parseManifest(manifestUrl: String, body: String): Result<FetchedManifest> {
        val manifest = try {
            json.decodeFromString(Manifest.serializer(), body)
        } catch (e: Exception) {
            return Result.failure(AddonFailure.fromUnexpected(e))
        }
        if (manifest.id.isBlank() || manifest.name.isBlank()) {
            return Result.failure(AddonFailure(AddonErrorKind.MissingIdentity, "Manifest has no id or name"))
        }
        if (manifest.resources.isEmpty() && manifest.catalogs.isEmpty()) {
            return Result.failure(AddonFailure(AddonErrorKind.Incompatible, "Manifest declares no resources or catalogs"))
        }
        return Result.success(FetchedManifest(manifestUrl, manifest, body))
    }

    // ------------------------------------------------------------------------------- resources

    suspend fun catalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        extras: Map<String, String> = emptyMap(),
        force: Boolean = false,
        timeoutMs: Long = CATALOG_TIMEOUT_MS,
    ): Result<CatalogResponse> = resource(baseUrl, Resources.CATALOG, type, catalogId, extras, force, timeoutMs, true) {
        json.decodeFromString(CatalogResponse.serializer(), it)
    }

    /** Failure(Empty) when the source answers without a usable meta. */
    suspend fun meta(
        baseUrl: String,
        type: String,
        id: String,
        force: Boolean = false,
        timeoutMs: Long = META_TIMEOUT_MS,
    ): Result<Meta> = resource(baseUrl, Resources.META, type, id, emptyMap(), force, timeoutMs, true) {
        val m = json.decodeFromString(MetaResponse.serializer(), it).meta
            ?: throw AddonFailure(AddonErrorKind.Empty, "No meta in response")
        // Some addons omit id/type inside the meta object: fill in from the request.
        m.copy(id = m.id.ifBlank { id }, type = m.type.ifBlank { type })
    }

    suspend fun streams(
        baseUrl: String,
        type: String,
        videoId: String,
        force: Boolean = false,
        timeoutMs: Long = STREAM_TIMEOUT_MS,
    ): Result<StreamResponse> = resource(baseUrl, Resources.STREAM, type, videoId, emptyMap(), force, timeoutMs, false) {
        json.decodeFromString(StreamResponse.serializer(), it)
    }

    suspend fun subtitles(
        baseUrl: String,
        type: String,
        videoId: String,
        extras: Map<String, String> = emptyMap(),
        force: Boolean = false,
        timeoutMs: Long = META_TIMEOUT_MS,
    ): Result<SubtitlesResponse> = resource(baseUrl, Resources.SUBTITLES, type, videoId, extras, force, timeoutMs, true) {
        json.decodeFromString(SubtitlesResponse.serializer(), it)
    }

    private suspend fun <T> resource(
        baseUrl: String,
        resource: String,
        type: String,
        id: String,
        extras: Map<String, String>,
        force: Boolean,
        timeoutMs: Long,
        cacheable: Boolean,
        parse: (String) -> T,
    ): Result<T> {
        val url = AddonUrls.resourceUrl(baseUrl, resource, type, id, extras)
        val effective = if (health.isUnhealthy(baseUrl)) timeoutMs / 2 else timeoutMs
        val result = get(url, effective, force, cacheable).mapCatching { body ->
            try {
                parse(body)
            } catch (f: AddonFailure) {
                throw f
            } catch (e: SerializationException) {
                throw AddonFailure(AddonErrorKind.NotJson, "Could not read the response", cause = e)
            } catch (e: IllegalArgumentException) {
                throw AddonFailure(AddonErrorKind.NotJson, "Could not read the response", cause = e)
            }
        }
        // Empty is a valid answer from a healthy source, not a health failure.
        health.record(baseUrl, result.isSuccess || (result.exceptionOrNull() as? AddonFailure)?.kind == AddonErrorKind.Empty)
        return result
    }

    // -------------------------------------------------------------------------------- transport

    private suspend fun get(url: String, timeoutMs: Long, force: Boolean, cacheable: Boolean): Result<String> {
        val scaled = (timeoutMs * timeoutScale()).toLong().coerceAtLeast(1_000)
        val first = execute(url, scaled, if (force) CacheControl.FORCE_NETWORK else null)
        if (first.isSuccess || !cacheable || force) return first
        val failure = first.exceptionOrNull() as? AddonFailure
        // Offline / flaky network: fall back to anything we have cached, however stale.
        if (failure?.kind == AddonErrorKind.NoConnection || failure?.kind == AddonErrorKind.Timeout) {
            val stale = execute(url, scaled, CacheControl.FORCE_CACHE)
            if (stale.isSuccess) return stale
        }
        return first
    }

    private suspend fun execute(url: String, timeoutMs: Long, cacheControl: CacheControl?): Result<String> {
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            return Result.failure(AddonFailure(AddonErrorKind.InvalidUrl, "Invalid url", cause = e))
        }
        builder.header("Accept", "application/json, text/plain, */*").header("User-Agent", userAgent)
        if (cacheControl != null) builder.cacheControl(cacheControl)
        val call = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(timeoutMs, 10_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
        return try {
            call.await().use { resp -> readBody(resp) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(mapIo(e))
        } catch (e: Exception) {
            Result.failure(AddonFailure.fromUnexpected(e))
        }
    }

    private fun readBody(resp: Response): Result<String> {
        if (resp.code == 504 && resp.networkResponse == null && resp.cacheResponse == null) {
            return Result.failure(AddonFailure(AddonErrorKind.NoConnection, "Not cached"))
        }
        if (!resp.isSuccessful) {
            return Result.failure(AddonFailure(AddonErrorKind.HttpStatus, "HTTP ${resp.code}", httpCode = resp.code))
        }
        val body = resp.body ?: return Result.failure(AddonFailure(AddonErrorKind.NotJson, "Empty response"))
        if (body.contentLength() > MAX_BODY_BYTES) return Result.failure(AddonFailure(AddonErrorKind.NotJson, "Response too large"))
        // chunked responses report no length: cap what is read instead of buffering whatever a source streams
        val src = body.source()
        src.request(MAX_BODY_BYTES + 1)
        if (src.buffer.size > MAX_BODY_BYTES) return Result.failure(AddonFailure(AddonErrorKind.NotJson, "Response too large"))
        val text = body.string().removePrefix("﻿").trim()
        if (text.isEmpty() || (text[0] != '{' && text[0] != '[')) {
            return Result.failure(AddonFailure(AddonErrorKind.NotJson, "Response is not JSON"))
        }
        return Result.success(text)
    }

    private fun mapIo(e: IOException): AddonFailure = when {
        e is SSLException -> AddonFailure(AddonErrorKind.Tls, e.message ?: "TLS error", cause = e)
        e is UnknownHostException || e is ConnectException -> AddonFailure(AddonErrorKind.NoConnection, e.message ?: "Cannot connect", cause = e)
        e is InterruptedIOException || e.message?.contains("timeout", ignoreCase = true) == true ->
            AddonFailure(AddonErrorKind.Timeout, "Timed out", cause = e)
        else -> AddonFailure(AddonErrorKind.NoConnection, e.message ?: "Network error", cause = e)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        const val MANIFEST_TIMEOUT_MS = 10_000L
        const val CATALOG_TIMEOUT_MS = 10_000L
        const val META_TIMEOUT_MS = 10_000L
        const val STREAM_TIMEOUT_MS = 15_000L
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024

        /**
         * Builds the shared OkHttp client: disk cache plus a network interceptor that gives responses without
         * any Cache-Control a polite default freshness (manifests 30 min, catalogs/subtitles 10 min, meta 30 min)
         * so we do not hammer addons. Server-sent Cache-Control always wins. Streams are never cached on disk.
         */
        fun createHttpClient(cacheDir: File?, maxCacheBytes: Long = 50L * 1024 * 1024): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .apply { if (cacheDir != null) cache(Cache(cacheDir, maxCacheBytes)) }
                .addNetworkInterceptor(PoliteCacheInterceptor)
                .build()
    }
}

/** Adds default freshness for addon JSON responses that carry no caching headers. */
object PoliteCacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val resp = chain.proceed(chain.request())
        if (chain.request().method != "GET" || !resp.isSuccessful) return resp
        if (resp.header("Cache-Control") != null || resp.header("Expires") != null) return resp
        val path = chain.request().url.encodedPath
        val maxAge = when {
            path.endsWith("/manifest.json") -> 30 * 60
            path.startsWith("/stream/") || "/stream/" in path -> return resp
            "/meta/" in path -> 30 * 60
            "/catalog/" in path || "/subtitles/" in path -> 10 * 60
            else -> return resp
        }
        return resp.newBuilder().header("Cache-Control", "public, max-age=$maxAge").build()
    }
}
