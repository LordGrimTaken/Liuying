package com.liuying.app.domain

import com.liuying.app.data.repo.RowSnapshots
import com.liuying.app.data.repo.SourceTestText
import com.liuying.app.data.repo.TestStep
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.ProgressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Wave1UiLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun prog(type: String, id: String, pos: Long = 0, watched: Boolean = false, s: Int = ProgressEntry.NO_EPISODE, e: Int = ProgressEntry.NO_EPISODE) =
        ProgressEntry(type, id, s, e, id, pos, 100_000, watched, 1L, null)

    @Test fun shelfStatusMovie() {
        assertEquals(ShelfStatus.Planned, ShelfStatuses.of("movie", emptyList()))
        assertEquals(ShelfStatus.Watching, ShelfStatuses.of("movie", listOf(prog("movie", "a", pos = 5_000))))
        assertEquals(ShelfStatus.Completed, ShelfStatuses.of("movie", listOf(prog("movie", "a", watched = true))))
    }

    @Test fun shelfStatusSeries() {
        assertEquals(ShelfStatus.Planned, ShelfStatuses.of("series", listOf(prog("series", "s", s = 1, e = 1))))
        assertEquals(ShelfStatus.Watching, ShelfStatuses.of("series", listOf(prog("series", "s", watched = true, s = 1, e = 1))))
    }

    @Test fun testSummary() {
        val s = listOf(
            TestStep("catalog", TestStep.Status.Pass, "", 1), TestStep("meta", TestStep.Status.Fail, "", 1),
            TestStep("subtitles", TestStep.Status.Skipped, "", 0),
        )
        assertEquals("1 of 2 checks passed.", SourceTestText.summary(s))
        assertEquals("All 1 checks passed.", SourceTestText.summary(s.take(1)))
        assertTrue(SourceTestText.summary(emptyList()).startsWith("Nothing to check"))
    }

    @Test fun rowSnapshotRoundTripAndExpiry() {
        var now = System.currentTimeMillis()
        val snaps = RowSnapshots(tmp.newFolder("rows"), nowMs = { now })
        assertNull(snaps.get("k"))
        snaps.put("k", listOf(MetaPreview(id = "tt1", type = "movie", name = "One"), MetaPreview(id = "tt2", type = "movie", name = "Two")))
        assertEquals(listOf("tt1", "tt2"), snaps.get("k")?.map { it.id })
        // empty lists never overwrite a good snapshot
        snaps.put("k", emptyList())
        assertEquals(2, snaps.get("k")?.size)
        // a file older than the limit is ignored
        now += 15L * 24 * 3600 * 1000
        assertNull(snaps.get("k"))
    }
}
