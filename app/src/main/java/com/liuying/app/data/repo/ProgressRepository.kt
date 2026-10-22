package com.liuying.app.data.repo

import com.liuying.app.data.db.DismissedEntity
import com.liuying.app.data.db.ProgressDao
import com.liuying.app.data.db.ProgressEntity
import com.liuying.app.data.settings.SettingsRepository
import com.liuying.app.domain.Clock
import com.liuying.app.domain.ProgressRules
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.model.PlaybackRef
import com.liuying.app.domain.model.ProgressEntry
import com.liuying.app.ui.player.ProgressReporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * Watch progress and the player's [ProgressReporter]. The player only knows media and episode ids, so the app
 * registers a [PlaybackRef] before each launch; reports for unregistered ids are ignored.
 */
class ProgressRepository(
    private val dao: ProgressDao,
    private val settings: SettingsRepository,
    private val clock: Clock = SystemClock,
) : ProgressReporter {
    @Volatile var onLocalChange: (() -> Unit)? = null
    private val active = ConcurrentHashMap<String, PlaybackRef>()

    fun observeAll(): Flow<List<ProgressEntry>> = dao.observeAll().map { l -> l.map { it.toEntry() } }
    fun observeForTitle(type: String, titleId: String): Flow<List<ProgressEntry>> =
        dao.observeForTitle(type, titleId).map { l -> l.map { it.toEntry() } }
    fun observeDismissed(): Flow<Map<Pair<String, String>, Long>> =
        dao.observeDismissed().map { l -> l.associate { (it.type to it.titleId) to it.dismissedAt } }
    suspend fun snapshot(): List<ProgressEntry> = dao.getAll().map { it.toEntry() }

    fun registerPlayback(ref: PlaybackRef) {
        active[key(ref.titleId, if (ref.season == ProgressEntry.NO_EPISODE) null else ref.videoId)] = ref
    }

    override suspend fun report(mediaId: String, episodeId: String?, positionMs: Long, durationMs: Long) {
        val ref = active[key(mediaId, episodeId)] ?: return
        val s = settings.current()
        if (!s.saveWatchHistory) return
        val existing = dao.get(ref.type, ref.titleId, ref.season, ref.episode)?.toEntry()
        val updated = ProgressRules.applyUpdate(existing, ref, positionMs, durationMs, clock.nowMs(), s.completionThreshold)
        dao.upsert(updated.toEntity())
        onLocalChange?.invoke()
    }

    /**
     * Manual watched/unwatched. Unwatching keeps a zeroed row (rather than deleting) so the change out-ranks older
     * remote copies in last-writer-wins sync.
     */
    suspend fun setWatched(ref: PlaybackRef, watched: Boolean) {
        val existing = dao.get(ref.type, ref.titleId, ref.season, ref.episode)?.toEntry()
        val e = ProgressEntry(
            ref.type, ref.titleId, ref.season, ref.episode, ref.videoId,
            positionMs = 0, durationMs = existing?.durationMs ?: 0, watched = watched, updatedAt = clock.nowMs(),
            streamHint = existing?.streamHint,
        )
        dao.upsert(e.toEntity())
        onLocalChange?.invoke()
    }

    suspend fun dismissContinue(type: String, titleId: String) {
        dao.upsertDismissed(DismissedEntity(type, titleId, clock.nowMs()))
    }

    suspend fun get(ref: PlaybackRef): ProgressEntry? = dao.get(ref.type, ref.titleId, ref.season, ref.episode)?.toEntry()

    // Sync entry point: no change callback.
    suspend fun putFromSync(entries: List<ProgressEntry>) { if (entries.isNotEmpty()) dao.upsertAll(entries.map { it.toEntity() }) }

    private fun key(mediaId: String, episodeId: String?) = "$mediaId|${episodeId ?: ""}"
}

internal fun ProgressEntity.toEntry() = ProgressEntry(type, titleId, season, episode, videoId, positionMs, durationMs, watched, updatedAt, streamHint)
internal fun ProgressEntry.toEntity() = ProgressEntity(type, titleId, season, episode, videoId, positionMs, durationMs, watched, updatedAt, streamHint)
