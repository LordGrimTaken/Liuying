package com.liuying.app.domain.sync

import com.liuying.app.domain.IsoDate
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.ProgressEntry

/** Progress item as exchanged with /api/sync/progress. [episodeId] is null for movies. */
data class RemoteProgress(
    val mediaId: String,
    val episodeId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val dismissed: Boolean,
    val updatedAt: Long,
)

data class RemoteLibraryItem(val id: String, val type: String, val name: String, val poster: String?, val addedAt: String?)

data class RemoteSource(val url: String, val enabled: Boolean)

data class SetMerge<K>(val addLocal: Set<K>, val removeLocal: Set<K>, val addRemote: Set<K>, val removeRemote: Set<K>)

data class ProgressMerge(val storeLocal: List<ProgressEntry>, val pushRemote: List<RemoteProgress>, val skipped: Int)

/** Pure merge rules for local-first sync. No I/O, so everything here is unit tested. */
object SyncLogic {
    const val MAX_ID = 128
    const val MAX_NAME = 300
    const val MAX_URL = 2048
    const val PROGRESS_BATCH = 200
    private val serverTypes = setOf("movie", "series", "channel", "tv", "other")

    /**
     * Three-way set merge. [base] is what both sides agreed on at the last successful sync, which lets a missing
     * element mean "deleted" (it was in base) or "new on the other side" (it was not). First sync has an empty base,
     * which gives a plain union.
     */
    fun <K> threeWay(local: Set<K>, remote: Set<K>, base: Set<K>): SetMerge<K> {
        val onlyRemote = remote - local
        val onlyLocal = local - remote
        return SetMerge(
            addLocal = onlyRemote - base,
            removeRemote = onlyRemote.intersect(base),
            addRemote = onlyLocal - base,
            removeLocal = onlyLocal.intersect(base),
        )
    }

    // ------------------------------------------------------------------------------------ progress

    fun remoteKey(e: ProgressEntry): Pair<String, String?> = e.titleId to (if (e.isEpisode) e.videoId else null)

    /** "tt1:2:5" for title "tt1" is season 2 episode 5; a single trailing number ("kitsu:9:4") is season 1. */
    fun parseEpisodeId(titleId: String, videoId: String): Pair<Int, Int>? {
        if (!videoId.startsWith("$titleId:")) return null
        val rest = videoId.substring(titleId.length + 1).split(':')
        val nums = rest.map { it.toIntOrNull() ?: return null }
        return when (nums.size) {
            1 -> if (nums[0] >= 0) 1 to nums[0] else null
            2 -> if (nums[0] >= 0 && nums[1] >= 0) nums[0] to nums[1] else null
            else -> null
        }
    }

    /** Null when the entry cannot be represented (id longer than the server accepts). */
    fun toRemote(e: ProgressEntry, now: Long): RemoteProgress? {
        if (e.titleId.isEmpty() || e.titleId.length > MAX_ID) return null
        val epId = if (e.isEpisode) e.videoId else null
        if (epId != null && (epId.isEmpty() || epId.length > MAX_ID)) return null
        val dur = e.durationMs.coerceIn(0, 10_000_000_000L)
        val pos = e.positionMs.coerceIn(0, dur)
        return RemoteProgress(e.titleId, epId, pos, dur, e.watched, false, e.updatedAt.coerceIn(0, now))
    }

    /**
     * Last-writer-wins per (mediaId, episodeId) by updatedAt; ties change nothing. [typeOfTitle] resolves the addon
     * type for titles only the server knows about; unknown ones are guessed from the episode id.
     */
    fun mergeProgress(
        local: List<ProgressEntry>,
        remote: List<RemoteProgress>,
        typeOfTitle: (String) -> String?,
        now: Long,
    ): ProgressMerge {
        val localByKey = local.associateBy { remoteKey(it) }
        val remoteByKey = remote.associateBy { it.mediaId to it.episodeId }
        val store = ArrayList<ProgressEntry>()
        val push = ArrayList<RemoteProgress>()
        var skipped = 0

        for (r in remote) {
            val l = localByKey[r.mediaId to r.episodeId]
            if (l != null) {
                if (r.updatedAt > l.updatedAt) {
                    store += l.copy(positionMs = r.positionMs, durationMs = r.durationMs, watched = r.watched, updatedAt = r.updatedAt)
                }
                continue
            }
            val type = typeOfTitle(r.mediaId) ?: if (r.episodeId != null) "series" else "movie"
            if (r.episodeId == null) {
                store += ProgressEntry(type, r.mediaId, ProgressEntry.NO_EPISODE, ProgressEntry.NO_EPISODE, r.mediaId,
                    r.positionMs, r.durationMs, r.watched, r.updatedAt)
            } else {
                val se = parseEpisodeId(r.mediaId, r.episodeId)
                if (se == null) { skipped++; continue }
                store += ProgressEntry(type, r.mediaId, se.first, se.second, r.episodeId, r.positionMs, r.durationMs, r.watched, r.updatedAt)
            }
        }
        for (l in local) {
            val r = remoteByKey[remoteKey(l)]
            if (r == null || l.updatedAt > r.updatedAt) toRemote(l, now)?.let { push += it }
        }
        return ProgressMerge(store, push, skipped)
    }

    // ------------------------------------------------------------------------------------ library

    fun toRemoteLibrary(i: LibraryItem): RemoteLibraryItem? {
        if (i.id.isEmpty() || i.id.length > MAX_ID) return null
        val name = i.name.trim().take(MAX_NAME).ifEmpty { return null }
        val type = if (i.type in serverTypes) i.type else "other"
        val poster = i.poster?.takeIf { (it.startsWith("http://") || it.startsWith("https://")) && it.length <= MAX_URL }
        return RemoteLibraryItem(i.id, type, name, poster, null)
    }

    // ------------------------------------------------------------------------------------ sources

    fun validSourceUrl(url: String) = (url.startsWith("http://") || url.startsWith("https://")) && url.length <= MAX_URL

    // ------------------------------------------------------------------------------------ time

    private val isoRx = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})?$")

    /** Parses ISO-8601 instants ("2026-10-22T10:00:00.123Z"); null when it does not look like one. */
    fun parseIsoMs(s: String?): Long? {
        val m = isoRx.find(s?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val day = IsoDate.daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt())
        var ms = ((day * 24 + g[4].toInt()) * 60 + g[5].toInt()) * 60_000L + g[6].toInt() * 1000L
        if (g[7].isNotEmpty()) ms += (g[7] + "00").take(3).toLong()
        val tz = g[8]
        if (tz.isNotEmpty() && tz != "Z") {
            val sign = if (tz[0] == '-') -1 else 1
            val digits = tz.substring(1).replace(":", "")
            ms -= sign * (digits.substring(0, 2).toInt() * 60L + digits.substring(2).toInt()) * 60_000L
        }
        return ms
    }
}
