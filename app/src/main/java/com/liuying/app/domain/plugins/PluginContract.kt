package com.liuying.app.domain.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull

/** Hard limits for one plugin run. */
object PluginLimits {
    const val RUN_TIMEOUT_MS = 20_000L
    const val MEMORY_BYTES = 48L * 1024 * 1024
    const val STACK_BYTES = 1L * 1024 * 1024
    const val MAX_REQUESTS = 30
    const val MAX_RESPONSE_BYTES = 5L * 1024 * 1024
    const val MAX_REQUEST_BODY_BYTES = 1L * 1024 * 1024
    const val REQUEST_TIMEOUT_MS = 15_000L
    const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
    const val MAX_STREAMS = 100
    const val MAX_LOG_LINES = 50
    const val MAX_TIMER_MS = 5_000L
}

/** What the plugin is asked for. Mirrors `getStreams(tmdbId, mediaType, season, episode)`. */
data class PluginRunRequest(
    /** TMDB id when it could be resolved, otherwise the IMDb id (documented fallback). */
    val tmdbId: String,
    /** "movie" or "tv". */
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
)

data class PluginStream(
    val name: String,
    val title: String?,
    val url: String,
    val quality: String?,
    val headers: Map<String, String> = emptyMap(),
)

class PluginRunException(message: String, val kind: Kind = Kind.Failed) : Exception(message) {
    enum class Kind { Failed, Timeout, Blocked }
}

/** Seam for the sandboxed runner. Implementations must isolate failures and enforce time/memory/network limits. */
interface PluginRunner {
    val runnable: Boolean
    suspend fun run(plugin: PluginInfo, request: PluginRunRequest): Result<List<PluginStream>>
}

object NoopPluginRunner : PluginRunner {
    override val runnable = false
    override suspend fun run(plugin: PluginInfo, request: PluginRunRequest): Result<List<PluginStream>> = Result.success(emptyList())
}

/** A request from Details (addon style type + video id) as the plugin contract wants it. */
data class PluginTarget(val rawId: String, val mediaType: String, val season: Int?, val episode: Int?) {
    val isImdb: Boolean get() = rawId.startsWith("tt")
}

object PluginTargets {
    /** "movie" + "tt1" or "series" + "tt1:2:3". Other id schemes (kitsu:, tmdb: handled as ids, anything else is skipped). */
    fun parse(type: String, videoId: String): PluginTarget? {
        val parts = videoId.split(':')
        val head = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val raw = when {
            head.matches(Regex("tt\\d{5,12}")) -> head
            head == "tmdb" && parts.size >= 2 && parts[1].all { it.isDigit() } && parts[1].isNotEmpty() -> parts[1]
            else -> return null
        }
        return when (type) {
            "movie" -> PluginTarget(raw, "movie", null, null)
            "series" -> {
                val off = if (head == "tmdb") 2 else 1
                val s = parts.getOrNull(off)?.toIntOrNull() ?: return null
                val e = parts.getOrNull(off + 1)?.toIntOrNull() ?: return null
                PluginTarget(raw, "tv", s, e)
            }
            else -> null
        }
    }

    /** Whether the plugin's declared types (addon "movie"/"series" or scraper "movie"/"tv") cover this media type. Empty = any. */
    fun supports(declared: List<String>, mediaType: String): Boolean {
        if (declared.isEmpty()) return true
        val d = declared.map { it.lowercase() }
        return if (mediaType == "movie") "movie" in d else ("tv" in d || "series" in d)
    }
}

/** Builds the JS the engine evaluates and parses what comes back. No engine types here, so it is testable anywhere. */
object PluginContract {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The call that starts the plugin after its script has been evaluated. */
    fun invocation(r: PluginRunRequest): String {
        fun lit(s: String) = JsonPrimitive(s).toString()
        return "__liuyingInvoke(${lit(r.tmdbId)}, ${lit(r.mediaType)}, ${r.season ?: "undefined"}, ${r.episode ?: "undefined"});"
    }

    /**
     * Parses the JSON a plugin resolved with. Accepts an array, or an object with a `streams` array. Entries without a
     * public http(s) url are dropped; text is trimmed; at most [PluginLimits.MAX_STREAMS] are kept.
     */
    fun parseResult(raw: String, pluginName: String): Result<List<PluginStream>> {
        val root = try { json.parseToJsonElement(raw) } catch (e: Exception) {
            return Result.failure(PluginRunException("The plugin returned something that is not JSON."))
        }
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> root["streams"] as? JsonArray
            is JsonNull -> JsonArray(emptyList())
            else -> null
        } ?: return Result.failure(PluginRunException("getStreams must return an array of streams."))
        val out = ArrayList<PluginStream>()
        for (el in arr) {
            if (out.size >= PluginLimits.MAX_STREAMS) break
            val o = el as? JsonObject ?: continue
            val url = o.str("url", 4096) ?: continue
            if (PluginUrlSafety.check(url) !is PluginUrlSafety.Verdict.Allowed) continue
            val headers = (o["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                val key = k.trim().take(100); val value = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.take(2000)
                if (key.isEmpty() || value == null || key.any { it.isISOControl() || it == ':' } || value.any { it == '\n' || it == '\r' }) null else key to value
            }?.take(20)?.toMap().orEmpty()
            out += PluginStream(
                name = o.str("name", 200) ?: pluginName, title = o.str("title", 500), url = url,
                quality = o.str("quality", 40), headers = headers,
            )
        }
        return Result.success(out)
    }

    private fun JsonObject.str(k: String, max: Int): String? {
        val p = this[k] as? JsonPrimitive ?: return null
        val s = (if (p.isString) p.contentOrNull else p.intOrNull?.toString() ?: p.doubleOrNull?.toString()) ?: return null
        return s.trim().take(max).takeIf { it.isNotEmpty() }
    }
}

/** Wraps one run in the wall-clock limit and turns every failure into a [Result]; cancellation still propagates. */
object PluginRunGuard {
    suspend fun <T> run(timeoutMs: Long = PluginLimits.RUN_TIMEOUT_MS, block: suspend () -> T): Result<T> = try {
        Result.success(kotlinx.coroutines.withTimeout(timeoutMs) { block() })
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Result.failure(PluginRunException("The plugin took longer than ${timeoutMs / 1000} seconds and was stopped.", PluginRunException.Kind.Timeout))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: PluginRunException) {
        Result.failure(e)
    } catch (e: Throwable) { // includes OutOfMemoryError from the engine's own allocations
        Result.failure(PluginRunException(describe(e)))
    }

    /** A short sentence a person can read: network failures say which host, not "No address associated with hostname". */
    fun describe(e: Throwable): String = when {
        e is java.net.UnknownHostException -> e.message?.takeIf { it.startsWith("Could not reach") || it.startsWith("blocked") }?.take(300)
            ?: "Could not reach the server (DNS lookup failed)."
        e is java.net.SocketTimeoutException -> "The server did not answer in time."
        e is javax.net.ssl.SSLException -> "A secure connection could not be made (${e.message?.take(100) ?: "TLS error"})."
        e is java.io.IOException -> "Network error: ${e.message?.take(200) ?: e.javaClass.simpleName}"
        else -> e.message?.take(300) ?: e.javaClass.simpleName
    }
}
