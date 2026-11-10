package com.liuying.app.domain

import com.liuying.app.domain.model.ProgressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesCompletionTest {
    private fun ep(id: String, s: Int, e: Int, watched: Boolean = true) =
        ProgressEntry("series", id, s, e, "$id:$s:$e", 0, 2_700_000, watched, 1L)

    @Test fun anUnknownEpisodeListIsNeverComplete() {
        assertFalse(SeriesCompletion.isComplete(listOf(ep("s", 1, 1)), null))
        assertFalse(SeriesCompletion.isComplete(listOf(ep("s", 1, 1)), 0))
        assertFalse(SeriesCompletion.isComplete(emptyList(), 2))
    }

    @Test fun everyKnownEpisodeMustBeWatched() {
        val rows = listOf(ep("s", 1, 1), ep("s", 1, 2))
        assertTrue(SeriesCompletion.isComplete(rows, 2))
        assertFalse(SeriesCompletion.isComplete(rows, 3))
        assertFalse(SeriesCompletion.isComplete(listOf(ep("s", 1, 1), ep("s", 1, 2, watched = false)), 2))
        assertFalse(SeriesCompletion.isComplete(listOf(ep("s", 1, 1, watched = false), ep("s", 1, 2)), 2))
    }

    @Test fun aRowThatIsStillInProgressBlocksCompletion() {
        val inProgress = ProgressEntry("series", "s", 1, 3, "s:1:3", 40_000, 2_700_000, false, 2L)
        assertFalse(SeriesCompletion.isComplete(listOf(ep("s", 1, 1), ep("s", 1, 2), inProgress), 2))
    }

    @Test fun countingIsByEpisodeNotByRow() {
        // A re-watch leaves two rows for the same episode; it must not count as two watched episodes.
        val rows = listOf(ep("s", 1, 1), ep("s", 1, 1), ep("s", 1, 2))
        assertFalse(SeriesCompletion.isComplete(rows, 3))
        assertTrue(SeriesCompletion.isComplete(rows, 2))
    }

    @Test fun watchedIndexCountsMoviesAndFinishedSeries() {
        val movie = ProgressEntry("movie", "tt1", -1, -1, "tt1", 0, 0, true, 1L)
        val episode = ep("ttS1", 1, 1)
        val finished = setOf(WatchedIndex.key("series", "ttS1"))
        assertEquals(setOf(WatchedIndex.key("movie", "tt1")), WatchedIndex.watchedKeys(listOf(movie)))
        assertEquals(
            setOf(WatchedIndex.key("movie", "tt1"), WatchedIndex.key("series", "ttS1")),
            WatchedIndex.watchedKeys(listOf(movie, episode), finished),
        )
        // Without the finished set a series episode is not a watched title.
        assertEquals(setOf(WatchedIndex.key("movie", "tt1")), WatchedIndex.watchedKeys(listOf(movie, episode)))
    }

    @Test fun hideWatchedRemovesAFinishedSeriesFromACatalogRow() {
        val items = listOf(
            com.liuying.app.domain.model.MetaPreview(id = "tt1", type = "movie", name = "A Film"),
            com.liuying.app.domain.model.MetaPreview(id = "ttS1", type = "series", name = "A Show"),
        )
        val watched = setOf(WatchedIndex.key("series", "ttS1"))
        assertEquals(listOf("tt1"), WatchedIndex.hideWatched(items, "movie", watched).map { it.id })
    }
}
