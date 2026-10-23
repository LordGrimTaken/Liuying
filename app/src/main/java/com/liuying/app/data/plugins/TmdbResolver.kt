package com.liuying.app.data.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * IMDb id to TMDB id, using the user's own Metadata API key (a v3 key or a v4 read token). The key is only used here, on
 * the host; plugins never see it. Without a key, or when the lookup fails, the IMDb id itself is passed to the plugin.
 */
class TmdbResolver(private val client: OkHttpClient, private val apiKey: suspend () -> String) {
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolve(imdbId: String, mediaType: String): String {
        cache["$mediaType|$imdbId"]?.let { return it }
        val key = apiKey().trim()
        if (key.isEmpty()) return imdbId
        val found = runCatching { withContext(Dispatchers.IO) { lookup(imdbId, mediaType, key) } }.getOrNull() ?: return imdbId
        cache["$mediaType|$imdbId"] = found
        return found
    }

    private fun lookup(imdbId: String, mediaType: String, key: String): String? {
        val bearer = key.startsWith("eyJ") // v4 read access token; v3 keys are 32 hex characters
        val url = "https://api.themoviedb.org/3/find/$imdbId?external_source=imdb_id" + if (bearer) "" else "&api_key=${java.net.URLEncoder.encode(key, "UTF-8")}"
        val rb = Request.Builder().url(url).header("Accept", "application/json")
        if (bearer) rb.header("Authorization", "Bearer $key")
        client.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string().orEmpty()
            if (body.length > 1_000_000) return null
            return parseFind(body, mediaType)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** First movie or tv result id of a TMDB /find answer, as text. */
        fun parseFind(body: String, mediaType: String): String? = try {
            val root = json.parseToJsonElement(body) as JsonObject
            val list = root[if (mediaType == "movie") "movie_results" else "tv_results"] as? JsonArray
            ((list?.firstOrNull() as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        } catch (e: Exception) { null }
    }
}
