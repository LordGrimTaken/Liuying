package com.liuying.app.domain

import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.ProgressEntry
import kotlin.random.Random

/** "Has the user watched this title?" for catalog rows and Browse (L004). */
object WatchedIndex {
    fun key(type: String, id: String) = "$type|$id"

    /**
     * Movies with a watched flag. Series are not hidden: whether a whole series is finished depends on its episode list,
     * which a catalog page does not carry.
     */
    fun watchedKeys(progress: List<ProgressEntry>): Set<String> =
        progress.filter { !it.isEpisode && it.watched }.map { key(it.type, it.titleId) }.toSet()

    fun hideWatched(items: List<MetaPreview>, fallbackType: String, watched: Set<String>): List<MetaPreview> =
        if (watched.isEmpty()) items else items.filter { key(it.type.ifBlank { fallbackType }, it.id) !in watched }
}

/** "Surprise me" (L010): a random unwatched title. */
data class PickCandidate(val preview: MetaPreview, val type: String, val sourceId: Long?)

object SurprisePick {
    fun pick(candidates: List<PickCandidate>, watched: Set<String>, random: Random = Random.Default, avoid: String? = null): PickCandidate? {
        val seen = HashSet<String>()
        val pool = candidates.filter { c ->
            val k = WatchedIndex.key(c.type, c.preview.id)
            k !in watched && seen.add(k)
        }
        if (pool.isEmpty()) return null
        // Do not offer the same title twice in a row when there is a choice.
        val varied = if (pool.size > 1 && avoid != null) pool.filter { WatchedIndex.key(it.type, it.preview.id) != avoid } else pool
        return varied[random.nextInt(varied.size)]
    }
}

/** Calendar filters and the week badge (L115). */
object CalendarFilters {
    fun episodeKey(mediaId: String, season: Int, episode: Int) = "$mediaId|$season|$episode"

    fun watchedEpisodeKeys(progress: List<ProgressEntry>): Set<String> =
        progress.filter { it.isEpisode && it.watched }.map { episodeKey(it.titleId, it.season, it.episode) }.toSet()

    fun filter(agenda: Ux.Agenda, hideWatched: Boolean, watched: Set<String>): Ux.Agenda {
        if (!hideWatched || watched.isEmpty()) return agenda
        fun days(l: List<Ux.AgendaDay>) = l.mapNotNull { d ->
            val items = d.items.filter { episodeKey(it.mediaId, it.season, it.episode) !in watched }
            if (items.isEmpty()) null else d.copy(items = items)
        }
        return agenda.copy(upcoming = days(agenda.upcoming), recent = days(agenda.recent))
    }

    /** Unwatched episodes airing today through the next [days]-1 days: the number on the Calendar badge. */
    fun weekCount(agenda: Ux.Agenda, watched: Set<String>, days: Int = 7): Int =
        agenda.upcoming.filter { it.diff in 0 until days }.sumOf { d -> d.items.count { episodeKey(it.mediaId, it.season, it.episode) !in watched } }
}

/** Shelf status chips (L005). Derived from progress; there is nothing to set by hand. */
enum class ShelfStatus(val label: String) { All("All"), Watching("Watching"), Planned("Planned"), Completed("Completed") }

object ShelfStatuses {
    /**
     * A movie is Completed once watched, Watching while in progress. A series is Watching once any episode was started
     * or watched (a catalog page does not say when a whole series is finished). Anything else is Planned.
     */
    fun of(type: String, rows: List<ProgressEntry>, knownEpisodes: Int? = null): ShelfStatus {
        val started = rows.any { it.watched || it.positionMs > 0 }
        return when {
            !started -> ShelfStatus.Planned
            type.equals("movie", true) -> if (rows.any { it.watched }) ShelfStatus.Completed else ShelfStatus.Watching
            // A series is only Completed when the app knows the whole episode list and every episode is watched;
            // guessing from the rows it happens to have would call a half-watched show finished.
            knownEpisodes != null && knownEpisodes > 0 && rows.all { it.watched } &&
                rows.filter { it.isEpisode && it.watched }.map { it.season to it.episode }.distinct().size >= knownEpisodes ->
                ShelfStatus.Completed
            else -> ShelfStatus.Watching
        }
    }
}
