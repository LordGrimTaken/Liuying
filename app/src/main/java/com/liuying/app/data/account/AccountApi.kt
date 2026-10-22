package com.liuying.app.data.account

import com.liuying.app.domain.sync.RemoteLibraryItem
import com.liuying.app.domain.sync.RemoteProgress
import com.liuying.app.domain.sync.RemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Where the session cookie, username and last-agreed sync sets live. */
interface SessionStore {
    var sid: String?
    var username: String?
    fun base(kind: String): Set<String>
    fun setBase(kind: String, values: Set<String>)
    fun clear()
}

class MemorySessionStore : SessionStore {
    override var sid: String? = null
    override var username: String? = null
    private val bases = HashMap<String, Set<String>>()
    override fun base(kind: String) = bases[kind].orEmpty()
    override fun setBase(kind: String, values: Set<String>) { bases[kind] = values }
    override fun clear() { sid = null; username = null; bases.clear() }
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val code: String, val message: String, val http: Int = 0, val retryAfterSec: Int? = null) : ApiResult<Nothing> {
        val unauthenticated: Boolean get() = http == 401 && code == "unauthenticated"
        val offline: Boolean get() = http == 0
    }
}

data class RemoteState(
    val sources: List<RemoteSource>,
    val library: List<RemoteLibraryItem>,
    val progress: List<RemoteProgress>,
    val serverTime: Long,
    /** Opaque settings object shared with the web player; `{}` when never saved. */
    val settings: JsonObject = JsonObject(emptyMap()),
)

/**
 * Client for the Liuying account server (see server/README.md). JSON bodies, `liuying_sid` session cookie kept in
 * [session], and an Origin header equal to the host as the server's CSRF check requires. Never throws.
 */
class AccountApi(
    private val http: OkHttpClient,
    private val session: SessionStore,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val origin: String = baseUrl.trimEnd('/')
    private val jsonType = "application/json".toMediaType()
    private val parser = Json { ignoreUnknownKeys = true }

    val username: String? get() = session.username
    val signedIn: Boolean get() = session.sid != null && session.username != null

    suspend fun signUp(username: String, password: String): ApiResult<String> = authenticate("/api/auth/signup", username, password)
    suspend fun logIn(username: String, password: String): ApiResult<String> = authenticate("/api/auth/login", username, password)

    private suspend fun authenticate(path: String, username: String, password: String): ApiResult<String> {
        val body = buildJsonObject { put("username", username); put("password", password) }.toString()
        return when (val r = call("POST", path, body)) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> {
                val name = (r.value as? JsonObject)?.get("user")?.asObj()?.str("username") ?: username
                if (session.sid == null) return ApiResult.Err("bad_response", "The server did not start a session.", 200)
                session.username = name
                ApiResult.Ok(name)
            }
        }
    }

    /** Always clears the local session, even if the server cannot be reached. */
    suspend fun logOut() {
        call("POST", "/api/auth/logout", "{}")
        session.clear()
    }

    suspend fun me(): ApiResult<String> = when (val r = call("GET", "/api/auth/me", null)) {
        is ApiResult.Err -> r
        is ApiResult.Ok -> ApiResult.Ok((r.value as? JsonObject)?.get("user")?.asObj()?.str("username") ?: session.username.orEmpty())
    }

    suspend fun getState(): ApiResult<RemoteState> = when (val r = call("GET", "/api/sync/state", null)) {
        is ApiResult.Err -> r
        is ApiResult.Ok -> ApiResult.Ok(parseState(r.value))
    }

    suspend fun putSources(sources: List<RemoteSource>): ApiResult<Unit> {
        val body = buildJsonObject {
            put("sources", buildJsonArray { sources.forEach { add(buildJsonObject { put("url", it.url); put("enabled", it.enabled) }) } })
        }.toString()
        return call("PUT", "/api/sync/sources", body).unit()
    }

    /** PUT replaces the whole object on the server, so callers pass the full merged object (see SettingsSyncLogic). */
    suspend fun putSettings(settings: JsonObject): ApiResult<Unit> =
        call("PUT", "/api/sync/settings", buildJsonObject { put("settings", settings) }.toString()).unit()

    suspend fun postLibrary(adds: List<RemoteLibraryItem>, removes: List<String>): ApiResult<Unit> {
        val body = buildJsonObject {
            put("add", buildJsonArray {
                adds.forEach { i ->
                    add(buildJsonObject {
                        put("id", i.id); put("type", i.type); put("name", i.name)
                        if (i.poster != null) put("poster", i.poster) else put("poster", JsonNull)
                    })
                }
            })
            put("remove", buildJsonArray { removes.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        return call("POST", "/api/sync/library", body).unit()
    }

    suspend fun postProgress(items: List<RemoteProgress>): ApiResult<Unit> {
        val body = buildJsonObject {
            put("items", buildJsonArray {
                items.forEach { p ->
                    add(buildJsonObject {
                        put("mediaId", p.mediaId)
                        if (p.episodeId != null) put("episodeId", p.episodeId) else put("episodeId", JsonNull)
                        put("positionMs", p.positionMs); put("durationMs", p.durationMs)
                        put("watched", p.watched); put("dismissed", p.dismissed); put("updatedAt", p.updatedAt)
                    })
                }
            })
        }.toString()
        return call("POST", "/api/sync/progress", body).unit()
    }

    // ------------------------------------------------------------------------------------ transport

    private fun ApiResult<JsonElement>.unit(): ApiResult<Unit> = when (this) {
        is ApiResult.Err -> this
        is ApiResult.Ok -> ApiResult.Ok(Unit)
    }

    private suspend fun call(method: String, path: String, body: String?): ApiResult<JsonElement> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .header("Origin", origin)
        session.sid?.let { b.header("Cookie", "$COOKIE=$it") }
        val rb = body?.toRequestBody(jsonType)
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, rb ?: "{}".toRequestBody(jsonType))
        }
        try {
            http.newCall(b.build()).execute().use { resp ->
                captureCookie(resp.headers("Set-Cookie"))
                val text = resp.body?.string().orEmpty()
                val json: JsonElement? = if (text.isBlank()) null else runCatching { parser.parseToJsonElement(text) }.getOrNull()
                if (resp.isSuccessful) {
                    ApiResult.Ok(json ?: JsonObject(emptyMap()))
                } else {
                    val obj = json as? JsonObject
                    val code = obj?.str("error") ?: "http_${resp.code}"
                    ApiResult.Err(code, friendly(code, obj?.str("message")), resp.code, resp.header("Retry-After")?.toIntOrNull())
                }
            }
        } catch (e: IOException) {
            ApiResult.Err("network", "Could not reach liuyingapp.com. Check your connection and try again.", 0)
        }
    }

    private fun captureCookie(headers: List<String>) {
        for (h in headers) {
            val first = h.substringBefore(';').trim()
            if (!first.startsWith("$COOKIE=")) continue
            val v = first.substringAfter('=')
            session.sid = v.ifEmpty { null }
        }
    }

    // ------------------------------------------------------------------------------------ parsing

    companion object {
        const val DEFAULT_BASE_URL = "https://www.liuyingapp.com"
        const val COOKIE = "liuying_sid"

        fun friendly(code: String, serverMessage: String?): String = when (code) {
            "invalid_username" -> "Usernames are 3-24 characters: letters, numbers and underscores."
            "invalid_password" -> "Passwords must be 8-128 characters."
            "username_taken" -> "That username is taken. Try another."
            "invalid_credentials" -> "Wrong username or password."
            "rate_limited" -> "Too many attempts. Wait a little and try again."
            "unauthenticated" -> "You are signed out."
            else -> serverMessage?.takeIf { it.isNotBlank() } ?: "Something went wrong ($code)."
        }

        internal fun parseState(el: JsonElement): RemoteState {
            val o = el as? JsonObject ?: return RemoteState(emptyList(), emptyList(), emptyList(), 0)
            val sources = o["sources"].arr().mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                val url = so.str("url") ?: return@mapNotNull null
                RemoteSource(url, (so["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
            }
            val library = o["library"].arr().mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                val id = so.str("id") ?: return@mapNotNull null
                RemoteLibraryItem(id, so.str("type") ?: "other", so.str("name") ?: id, so.str("poster"), so.str("addedAt"))
            }
            val progress = o["progress"].arr().mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                val media = so.str("mediaId") ?: return@mapNotNull null
                RemoteProgress(
                    media, so.str("episodeId"),
                    so.lng("positionMs") ?: 0, so.lng("durationMs") ?: 0,
                    (so["watched"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    (so["dismissed"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    so.lng("updatedAt") ?: return@mapNotNull null,
                )
            }
            return RemoteState(sources, library, progress, o.lng("serverTime") ?: 0, o["settings"] as? JsonObject ?: JsonObject(emptyMap()))
        }

        private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
        private fun JsonElement.asObj(): JsonObject? = this as? JsonObject
        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.lng(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    }
}
