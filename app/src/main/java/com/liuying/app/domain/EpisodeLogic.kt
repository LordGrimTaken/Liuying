package com.liuying.app.domain

import com.liuying.app.domain.model.ProgressEntry
import com.liuying.app.domain.model.Video

/** An episode with its local watch state. */
data class EpisodeState(
    val video: Video,
    val season: Int,
    val episode: Int,
    val released: Boolean,
    val watched: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val inProgress: Boolean,
    /** Last time progress was written for this episode (0 if never). */
    val lastPlayedAt: Long,
) {
    val videoId: String get() = video.id
    val fraction: Double get() = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
    val isSpecial: Boolean get() = season == 0
    val hasProgress: Boolean get() = watched || positionMs > 0 || lastPlayedAt > 0
}

enum class PlayKind { Resume, Next, Start, WatchAgain }

/** What the details page "Play" button should do. */
data class PlayTarget(val episode: EpisodeState?, val kind: PlayKind)

object EpisodeLogic {

    /**
     * Normalises a meta's videos into numbered episodes: keeps those with season+episode, drops duplicates
     * (same season/episode; the richer entry wins), sorts by season then episode. If nothing is numbered but
     * there are several videos (channels), they become season 1 in list order.
     */
    fun numbered(videos: List<Video>): List<Video> {
        val numbered = videos.filter { it.season != null && it.episode != null }
        if (numbered.isEmpty()) {
            if (videos.size < 2) return emptyList()
            return videos.mapIndexed { i, v -> v.copy(season = 1, episode = i + 1) }
        }
        val best = LinkedHashMap<Pair<Int, Int>, Video>()
        for (v in numbered) {
            val key = v.season!! to v.episode!!
            val cur = best[key]
            if (cur == null || richness(v) > richness(cur)) best[key] = v
        }
        return best.values.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun richness(v: Video) = listOf(v.displayTitle, v.displayOverview, v.thumbnail, v.released).count { !it.isNullOrBlank() }

    fun buildStates(videos: List<Video>, progress: List<ProgressEntry>, todayEpochDay: Long): List<EpisodeState> {
        val byVideo = progress.associateBy { it.videoId }
        val bySE = progress.filter { it.isEpisode }.associateBy { it.season to it.episode }
        return numbered(videos).map { v ->
            val s = v.season!!; val e = v.episode!!
            val p = byVideo[v.id] ?: bySE[s to e]
            EpisodeState(
                video = v, season = s, episode = e,
                released = IsoDate.isReleased(v.released, todayEpochDay),
                watched = p?.watched == true,
                positionMs = p?.positionMs ?: 0,
                durationMs = p?.durationMs ?: 0,
                inProgress = p != null && ProgressRules.isInProgress(p),
                lastPlayedAt = p?.updatedAt ?: 0,
            )
        }
    }

    /** Seasons present, regular ascending with specials (season 0) last. */
    fun seasons(states: List<EpisodeState>): List<Int> {
        val all = states.map { it.season }.distinct().sorted()
        return all.filter { it != 0 } + all.filter { it == 0 }
    }

    /**
     * Ordered candidates for "what comes next": specials only participate when the user played them.
     */
    fun sequence(states: List<EpisodeState>): List<EpisodeState> =
        states.filter { !it.isSpecial || it.hasProgress }.sortedWith(compareBy({ it.season }, { it.episode }))

    /**
     * First *released* episode after the highest watched one (skipping ahead is respected), or null. Unreleased
     * episodes are skipped: the Continue Watching "Up Next" card must not offer something that has not aired.
     */
    fun nextAfterHighestWatched(states: List<EpisodeState>): EpisodeState? {
        val seq = sequence(states)
        val idx = seq.indexOfLast { it.watched }
        if (idx < 0) return null
        return seq.drop(idx + 1).firstOrNull { it.released }
    }

    /**
     * Details-page Play target: the in-progress episode if any; else the first unwatched episode after the
     * highest watched; else S1E1; if everything released is watched, "Watch again" from the start.
     */
    fun playTarget(states: List<EpisodeState>): PlayTarget {
        if (states.isEmpty()) return PlayTarget(null, PlayKind.Start)
        states.filter { it.inProgress }.maxByOrNull { it.lastPlayedAt }?.let { return PlayTarget(it, PlayKind.Resume) }
        val seq = sequence(states)
        val idx = seq.indexOfLast { it.watched }
        val first = states.filter { !it.isSpecial }.minWithOrNull(compareBy({ it.season }, { it.episode })) ?: states.first()
        if (idx < 0) return PlayTarget(first, PlayKind.Start)
        val next = seq.drop(idx + 1).firstOrNull { it.released && !it.watched }
        return if (next != null) PlayTarget(next, PlayKind.Next) else PlayTarget(first, PlayKind.WatchAgain)
    }

    /** Season to preselect: season of the next-to-watch episode, else first regular season. */
    fun defaultSeason(states: List<EpisodeState>): Int? {
        if (states.isEmpty()) return null
        playTarget(states).episode?.let { return it.season }
        return seasons(states).firstOrNull()
    }
}
