package com.liuying.app.domain

import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.Video

/** One episode a bulk action will mark watched. */
data class EpisodeToMark(val type: String, val titleId: String, val videoId: String, val season: Int, val episode: Int)

/**
 * What a bulk "Mark watched" on the shelf will actually do.
 *
 * Films are simple. A series can only be marked when the app knows its episode list (the meta cache, or a
 * fetch that succeeded), so anything it cannot see is reported instead of silently ignored — which is what
 * used to happen: the action filtered to movies and dropped every series without a word.
 */
data class BulkWatchPlan(
    val movies: List<LibraryItem> = emptyList(),
    val episodes: List<EpisodeToMark> = emptyList(),
    /** Series whose episode list is unknown, so nothing could be marked for them. */
    val unknownSeries: List<LibraryItem> = emptyList(),
) {
    val markedTitles: Int get() = movies.size + episodes.map { it.type to it.titleId }.distinct().size
    val isEmpty: Boolean get() = movies.isEmpty() && episodes.isEmpty()
}

object BulkWatch {
    /** Key used by [plan]'s `episodes` map: "type|id". */
    fun key(item: LibraryItem): String = "${item.type}|${item.id}"

    fun key(type: String, id: String): String = "$type|$id"

    /**
     * @param episodes episode lists per [key]; a series missing from the map is treated as unknown.
     */
    fun plan(items: List<LibraryItem>, episodes: Map<String, List<Video>>): BulkWatchPlan {
        val movies = ArrayList<LibraryItem>()
        val marks = ArrayList<EpisodeToMark>()
        val unknown = ArrayList<LibraryItem>()
        for (item in items) {
            if (item.type.equals("movie", true)) {
                movies += item
                continue
            }
            val list = episodes[key(item)].orEmpty().filter { it.season != null && it.episode != null && it.id.isNotBlank() }
            if (list.isEmpty()) {
                unknown += item
                continue
            }
            for (v in list) {
                marks += EpisodeToMark(item.type, item.id, v.id, v.season!!, v.episode!!)
            }
        }
        return BulkWatchPlan(movies, marks, unknown)
    }

    /** The sentence shown after the action. Null when there is nothing to say (nothing selected). */
    fun summary(plan: BulkWatchPlan): String? {
        if (plan.movies.isEmpty() && plan.episodes.isEmpty() && plan.unknownSeries.isEmpty()) return null
        val marked = plan.markedTitles
        val text = if (marked == 1) "Marked 1 title watched" else "Marked $marked titles watched"
        if (plan.unknownSeries.isEmpty()) return text
        val n = plan.unknownSeries.size
        return "$text · $n series skipped (no episode list — open their Details once)"
    }
}
