package com.liuying.app.domain

import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkWatchTest {
    private fun lib(type: String, id: String) = LibraryItem(type, id, "Title $id", null, null, null, emptyList(), 0L)

    private fun ep(id: String, s: Int, e: Int) = Video(id = "$id:$s:$e", season = s, episode = e)

    @Test fun moviesAreAlwaysMarkable() {
        val plan = BulkWatch.plan(listOf(lib("movie", "tt1"), lib("movie", "tt2")), emptyMap())
        assertEquals(2, plan.movies.size)
        assertTrue(plan.episodes.isEmpty())
        assertTrue(plan.unknownSeries.isEmpty())
        assertEquals(2, plan.markedTitles)
    }

    @Test fun aSeriesWithAKnownEpisodeListIsExpanded() {
        val item = lib("series", "ttS1")
        val plan = BulkWatch.plan(listOf(item), mapOf(BulkWatch.key(item) to listOf(ep("ttS1", 1, 1), ep("ttS1", 1, 2))))
        assertEquals(2, plan.episodes.size)
        assertEquals(EpisodeToMark("series", "ttS1", "ttS1:1:2", 1, 2), plan.episodes[1])
        assertEquals(1, plan.markedTitles)
        assertTrue(plan.unknownSeries.isEmpty())
    }

    @Test fun aSeriesWithoutAnEpisodeListIsReportedNotDropped() {
        val item = lib("series", "ttS9")
        val plan = BulkWatch.plan(listOf(item), emptyMap())
        assertTrue(plan.isEmpty)
        assertEquals(listOf(item), plan.unknownSeries)
        assertEquals("Marked 0 titles watched · 1 series skipped (no episode list — open their Details once)", BulkWatch.summary(plan))
    }

    @Test fun aMixedSelectionKeepsEveryPartOfTheMessage() {
        val film = lib("movie", "tt1")
        val known = lib("series", "ttS1")
        val unknown = lib("series", "ttS2")
        val plan = BulkWatch.plan(
            listOf(film, known, unknown),
            mapOf(BulkWatch.key(known) to listOf(ep("ttS1", 1, 1))),
        )
        assertEquals(1, plan.movies.size)
        assertEquals(1, plan.episodes.size)
        assertEquals(listOf(unknown), plan.unknownSeries)
        assertEquals(2, plan.markedTitles)
        assertEquals("Marked 2 titles watched · 1 series skipped (no episode list — open their Details once)", BulkWatch.summary(plan))
    }

    @Test fun episodesWithoutSeasonOrNumberAreIgnored() {
        val item = lib("series", "ttS1")
        val plan = BulkWatch.plan(listOf(item), mapOf(BulkWatch.key(item) to listOf(Video(id = "x"), Video(id = "y", season = 1), ep("ttS1", 2, 3))))
        assertEquals(1, plan.episodes.size)
        assertEquals(2 to 3, plan.episodes[0].season to plan.episodes[0].episode)
    }

    @Test fun theSummaryFollowsTheDirection() {
        val film = lib("movie", "tt1")
        val plan = BulkWatch.plan(listOf(film), emptyMap())
        assertEquals("Marked 1 title watched", BulkWatch.summary(plan))
        assertEquals("Marked 1 title unwatched", BulkWatch.summary(plan, watched = false))
        val two = BulkWatch.plan(listOf(film, lib("movie", "tt2")), emptyMap())
        assertEquals("Marked 2 titles unwatched", BulkWatch.summary(two, watched = false))
    }

    @Test fun anEmptySelectionSaysNothing() {
        assertNull(BulkWatch.summary(BulkWatch.plan(emptyList(), emptyMap())))
    }
}
