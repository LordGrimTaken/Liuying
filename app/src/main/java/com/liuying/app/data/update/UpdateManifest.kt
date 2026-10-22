package com.liuying.app.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What /downloads/latest.json says about the newest build. */
data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releasedAt: String,
    val notes: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** Returns null for anything malformed: a bad manifest must never trigger an update. */
        fun parse(text: String): UpdateManifest? {
            val obj: JsonObject = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return null }
            fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
            fun num(k: String) = (obj[k] as? JsonPrimitive)?.longOrNull
            val code = num("versionCode") ?: return null
            val name = str("versionName")?.trim().orEmpty()
            val url = str("apkUrl")?.trim().orEmpty()
            val sha = str("sha256")?.trim()?.lowercase().orEmpty()
            if (code <= 0 || name.isEmpty() || url.isEmpty() || !SHA256.matches(sha)) return null
            return UpdateManifest(name, code, url, sha, num("sizeBytes") ?: 0L, str("releasedAt").orEmpty(), str("notes").orEmpty())
        }
    }
}

object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** Only a strictly higher versionCode counts; equal or lower (a rollback manifest) is never offered. */
    fun isNewer(installedCode: Long, manifest: UpdateManifest) = manifest.versionCode > installedCode

    /** Automatic check: setting on, and none in the last 6 hours (a clock set back also re-checks). */
    fun shouldAutoCheck(enabled: Boolean, nowMs: Long, lastCheckMs: Long): Boolean =
        enabled && (lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_INTERVAL_MS)
}
