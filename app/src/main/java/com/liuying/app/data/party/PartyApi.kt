package com.liuying.app.data.party

import com.liuying.app.data.account.AccountApi
import com.liuying.app.data.account.ApiResult
import com.liuying.app.domain.party.PartyLogic
import com.liuying.app.domain.party.PartyPlaybackState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One room exactly as the party API returns it. `members` is only used as a count ("3 watching"). */
data class PartyRoom(
    val code: String,
    val rev: Long,
    val state: PartyPlaybackState,
    val members: Int,
)

/**
 * Client for `/api/party` (docs/watch-party.md): create, get, update, join, leave, end.
 *
 * Every call goes through [AccountApi.request], so it carries the same `liuying_sid` cookie, Origin header and error
 * mapping as the account and sync routes; the base URL is whatever that client was built with. Never throws.
 * Methods validate the code locally so a typo cannot turn into a request to an unrelated path.
 */
class PartyApi(private val api: AccountApi) {
    val signedIn: Boolean get() = api.signedIn
    val username: String? get() = api.username

    /** `POST /api/party` — the caller becomes the host. */
    suspend fun create(mediaId: String, episodeId: String?, positionMs: Long, playing: Boolean): ApiResult<PartyRoom> {
        val body = buildJsonObject {
            put("mediaId", mediaId)
            if (episodeId != null) put("episodeId", episodeId) else put("episodeId", JsonNull)
            put("positionMs", positionMs.coerceAtLeast(0))
            put("playing", playing)
            username?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        }
        return api.request("POST", "/api/party", body).toRoom(fallbackCode = null)
    }

    /** `GET /api/party/:code` — the poll. */
    suspend fun get(code: String): ApiResult<PartyRoom> = withCode(code) { c ->
        api.request("GET", "/api/party/$c").toRoom(fallbackCode = c)
    }

    /** `PUT /api/party/:code` — host only; the response is `{rev, state}` without the member list. */
    suspend fun update(code: String, positionMs: Long, playing: Boolean): ApiResult<PartyRoom> = withCode(code) { c ->
        val body = buildJsonObject {
            put("positionMs", positionMs.coerceAtLeast(0))
            put("playing", playing)
        }
        api.request("PUT", "/api/party/$c", body).toRoom(fallbackCode = c)
    }

    /** `POST /api/party/:code/join` — response is `{code, rev, state, host:false}`. */
    suspend fun join(code: String): ApiResult<PartyRoom> = withCode(code) { c ->
        api.request("POST", "/api/party/$c/join", buildJsonObject { }).toRoom(fallbackCode = c)
    }

    /** `POST /api/party/:code/leave` — 204. */
    suspend fun leave(code: String): ApiResult<Unit> = withCode(code) { c ->
        api.request("POST", "/api/party/$c/leave", buildJsonObject { }).done()
    }

    /** `DELETE /api/party/:code` — host only, ends the room for everyone; 204. */
    suspend fun end(code: String): ApiResult<Unit> = withCode(code) { c ->
        api.request("DELETE", "/api/party/$c").done()
    }

    private inline fun <T> withCode(code: String, call: (String) -> ApiResult<T>): ApiResult<T> {
        val normalized = PartyLogic.normalizeCode(code)
            ?: return ApiResult.Err("bad_request", "Watch party codes are six letters.", 400)
        return call(normalized)
    }

    private fun ApiResult<JsonElement>.done(): ApiResult<Unit> = when (this) {
        is ApiResult.Err -> this
        is ApiResult.Ok -> ApiResult.Ok(Unit)
    }

    /** Parse `{code, rev, state, members}` and the host-only `{rev, state}` (whose code comes from the request). */
    private fun ApiResult<JsonElement>.toRoom(fallbackCode: String?): ApiResult<PartyRoom> = when (this) {
        is ApiResult.Err -> this
        is ApiResult.Ok -> {
            val o = value as? JsonObject
            val code = o?.str("code") ?: fallbackCode
            val state = parseState(o?.get("state"))
            if (o == null || code == null || state == null) {
                ApiResult.Err("bad_response", "The watch party server sent an unexpected response.", 200)
            } else {
                ApiResult.Ok(PartyRoom(code, o.lng("rev") ?: 0L, state, o.memberCount()))
            }
        }
    }
}

/** The API's `state` object, or null when it cannot be understood. */
internal fun parseState(el: JsonElement?): PartyPlaybackState? {
    val o = el as? JsonObject ?: return null
    val mediaId = o.str("mediaId") ?: return null
    return PartyPlaybackState(
        mediaId = mediaId,
        episodeId = o.str("episodeId"),
        positionMs = (o.lng("positionMs") ?: 0L).coerceAtLeast(0L),
        playing = o.bool("playing") ?: false,
        updatedAt = o.lng("updatedAt"),
        hostName = o.str("hostName"),
    )
}

private fun JsonObject.str(k: String): String? =
    (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.lng(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull

private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

/** `members` is a list in the storage schema; tolerate a plain count too and use either as "N watching". */
private fun JsonObject.memberCount(): Int = when (val m = this["members"]) {
    is JsonArray -> m.size
    is JsonPrimitive -> m.intOrNull ?: 0
    else -> 0
}
