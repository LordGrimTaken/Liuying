package com.liuying.app.domain

import com.liuying.app.data.settings.LibrarySort
import com.liuying.app.domain.model.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLogicTest {
    private fun item(id: String, name: String, type: String = "movie", year: String? = null, added: Long = 0) =
        LibraryItem(type, id, name, null, null, year, emptyList(), added)

    private val items = listOf(
        item("a", "The Zebra", "movie", "2001", 1), item("b", "Apple", "series", "2019-2022", 3),
        item("c", "Mango", "anime", null, 2),
    )

    @Test fun filtersByTypeAndQuery() {
        assertEquals(listOf("a"), LibraryLogic.apply(items, TypeFilter.Movies, "", LibrarySort.RecentlyAdded).map { it.id })
        assertEquals(listOf("c"), LibraryLogic.apply(items, TypeFilter.Other, "", LibrarySort.RecentlyAdded).map { it.id })
        assertEquals(listOf("b"), LibraryLogic.apply(items, TypeFilter.All, " app ", LibrarySort.RecentlyAdded).map { it.id })
    }

    @Test fun sorts() {
        assertEquals(listOf("b", "c", "a"), LibraryLogic.apply(items, TypeFilter.All, "", LibrarySort.RecentlyAdded).map { it.id })
        assertEquals(listOf("b", "c", "a"), LibraryLogic.apply(items, TypeFilter.All, "", LibrarySort.TitleAZ).map { it.id })
        assertEquals(listOf("b", "a", "c"), LibraryLogic.apply(items, TypeFilter.All, "", LibrarySort.Year).map { it.id })
        val lp = mapOf(("movie" to "a") to 99L)
        assertEquals("a", LibraryLogic.apply(items, TypeFilter.All, "", LibrarySort.LastWatched, lastPlayed = lp).first().id)
        val w = setOf("series" to "b")
        assertEquals("b", LibraryLogic.apply(items, TypeFilter.All, "", LibrarySort.UnwatchedFirst, watched = w).last().id)
    }

    @Test fun labels() {
        assertEquals("S2 E5 - Pilot", EpisodeLabels.line(2, 5, "Pilot"))
        assertEquals("Special 1", EpisodeLabels.code(0, 1))
        assertEquals("1 h 5 min left", EpisodeLabels.remaining(65 * 60_000L))
    }

    @Test fun completedLastMovesFinishedTitlesToTheEndAndKeepsTheRestInOrder() {
        val order = listOf("started-series", "new-film", "finished-film", "half-series", "finished-series")
        val complete = setOf("finished-film", "finished-series")
        assertEquals(
            listOf("started-series", "new-film", "half-series", "finished-film", "finished-series"),
            LibraryLogic.completedLast(order, { it in complete }),
        )
    }

    @Test fun completedLastLeavesEverythingAloneWhenNothingIsComplete() {
        val order = listOf("a", "b", "c")
        assertEquals(order, LibraryLogic.completedLast(order, { false }))
        assertEquals("a", LibraryLogic.completedLast(emptyList<String>(), { true }).firstOrNull() ?: "a")
    }
}
