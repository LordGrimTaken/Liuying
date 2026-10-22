package com.liuying.app.domain

import com.liuying.app.domain.model.ProgressEntry
import com.liuying.app.domain.model.Video

enum class ContinueKind { InProgress, UpNext }

/** One Continue Watching card (series are collapsed to a single card). Display metadata is filled in by the repository. */
data class ContinueItem(
    val type: String,
    val titleId: String,
    val kind: ContinueKind,
    /** Video id to hand to the stream sources. */
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    val title: String? = null,
    val episodeTitle: String? = null,
    val poster: String? = null,
    /** Landscape image: episode still, else backdrop. */
    val thumbnail: String? = null,
) {
    val fraction: Double get() = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
    val remainingMs: Long get() = if (kind == ContinueKind.UpNext) 0 else (durationMs - positionMs).coerceAtLeast(0)
    val key: String get() = "$type:$titleId"
}

object ContinueWatchingLogic {
    const val DEFAULT_LIMIT = 20

    /**
     * @param progress all progress rows
     * @param dismissals (type to titleId) -> dismissedAt; a card stays hidden until the title is played again after that
     * @param videosByTitle episode lists for series (needed for Up Next); titles without one never get an Up Next card
     */
    fun compute(
        progress: List<ProgressEntry>,
        dismissals: Map<Pair<String, String>, Long>,
        videosByTitle: Map<Pair<String, String>, List<Video>>,
        todayEpochDay: Long,
        showUpNext: Boolean = true,
        threshold: Double = ProgressRules.DEFAULT_THRESHOLD,
        limit: Int = DEFAULT_LIMIT,
    ): List<ContinueItem> {
        val out = ArrayList<ContinueItem>()
        for ((key, group) in progress.groupBy { it.type to it.titleId }) {
            val lastPlayed = group.maxOf { it.updatedAt }
            val dismissedAt = dismissals[key]
            if (dismissedAt != null && dismissedAt >= lastPlayed) continue

            val current = group.filter { ProgressRules.isInProgress(it, threshold) }.maxByOrNull { it.updatedAt }
            if (current != null) {
                out += ContinueItem(
                    type = key.first, titleId = key.second, kind = ContinueKind.InProgress, videoId = current.videoId,
                    season = current.season.takeIf { current.isEpisode }, episode = current.episode.takeIf { current.isEpisode },
                    positionMs = current.positionMs, durationMs = current.durationMs, lastPlayedAt = lastPlayed,
                )
                continue
            }
            if (!showUpNext || group.none { it.isEpisode }) continue
            val videos = videosByTitle[key] ?: continue
            val states = EpisodeLogic.buildStates(videos, group.filter { it.isEpisode }, todayEpochDay)
            val next = EpisodeLogic.nextAfterHighestWatched(states) ?: continue
            if (!next.released) continue
            out += ContinueItem(
                type = key.first, titleId = key.second, kind = ContinueKind.UpNext, videoId = next.videoId,
                season = next.season, episode = next.episode, positionMs = 0, durationMs = 0, lastPlayedAt = lastPlayed,
                episodeTitle = next.video.displayTitle, thumbnail = next.video.thumbnail,
            )
        }
        return out.sortedByDescending { it.lastPlayedAt }.take(limit)
    }
}
