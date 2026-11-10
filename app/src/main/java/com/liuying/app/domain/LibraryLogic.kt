package com.liuying.app.domain

import com.liuying.app.data.settings.LibrarySort
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.ProgressEntry

enum class TypeFilter(val label: String) {
    All("All"), Movies("Movies"), Series("Series"), Other("Other");

    fun accepts(type: String): Boolean = when (this) {
        All -> true
        Movies -> type.equals("movie", true)
        Series -> type.equals("series", true)
        Other -> !type.equals("movie", true) && !type.equals("series", true)
    }
}

object LibraryLogic {
    /** Filter by type and name, then sort. [lastPlayed] and [watched] are keyed by (type, titleId). */
    fun apply(
        items: List<LibraryItem>,
        filter: TypeFilter,
        query: String,
        sort: LibrarySort,
        lastPlayed: Map<Pair<String, String>, Long> = emptyMap(),
        watched: Set<Pair<String, String>> = emptySet(),
    ): List<LibraryItem> {
        val q = query.trim().lowercase()
        val filtered = items.filter { filter.accepts(it.type) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        val key = { i: LibraryItem -> i.type to i.id }
        return when (sort) {
            LibrarySort.RecentlyAdded -> filtered.sortedByDescending { it.addedAt }
            LibrarySort.TitleAZ -> filtered.sortedBy { it.name.lowercase().removePrefix("the ") }
            LibrarySort.Year -> filtered.sortedWith(compareByDescending<LibraryItem> { yearOf(it.year) ?: -1 }.thenByDescending { it.addedAt })
            LibrarySort.LastWatched -> filtered.sortedWith(compareByDescending<LibraryItem> { lastPlayed[key(it)] ?: -1L }.thenByDescending { it.addedAt })
            LibrarySort.UnwatchedFirst -> filtered.sortedWith(compareBy<LibraryItem> { key(it) in watched }.thenByDescending { it.addedAt })
        }
    }

    /**
     * "Unwatched first" with a real notion of finished: everything not complete keeps its order, completed
     * entries move to the end. A series you merely started is not complete, so it stays near the top.
     */
    fun <T> completedLast(items: List<T>, isComplete: (T) -> Boolean): List<T> =
        items.sortedBy { isComplete(it) }

    fun yearOf(s: String?): Int? = s?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun lastPlayed(progress: List<ProgressEntry>): Map<Pair<String, String>, Long> =
        progress.groupBy { it.type to it.titleId }.mapValues { (_, v) -> v.maxOf { it.updatedAt } }

    /** Titles where something has been watched (movies: the movie; series: any episode). */
    fun watchedTitles(progress: List<ProgressEntry>): Set<Pair<String, String>> =
        progress.filter { it.watched }.map { it.type to it.titleId }.toSet()
}
