package com.liuying.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlanTest {
    private fun item(
        key: String,
        state: DownloadState = DownloadState.Queued,
        size: Long? = 1_000,
        done: Long = 0,
        at: Long = 0,
        error: String? = null,
    ) = DownloadItem(key = key, title = key, sizeBytes = size, state = state, downloadedBytes = done, queuedAt = at, error = error)

    @Test fun progressIsAFractionAndUnknownSizesStayIndeterminate() {
        assertEquals(0.5f, item("a", size = 1_000, done = 500).fraction!!, 0.001f)
        assertNull(item("a", size = null, done = 500).fraction)
        assertNull(item("a", size = 0, done = 0).fraction)
        // A server that lies about the size cannot push the bar past the end.
        assertEquals(1f, item("a", size = 100, done = 400).fraction!!, 0.001f)
    }

    @Test fun atMostTwoTransfersRunAtOnceAndTheOldestStartsFirst() {
        val items = listOf(item("c", at = 3), item("a", at = 1), item("b", at = 2))
        assertEquals(listOf("a", "b"), DownloadLogic.plan(items).toStart.map { it.key })
        val oneRunning = listOf(item("a", DownloadState.Running, at = 1), item("b", at = 2), item("c", at = 3))
        assertEquals(listOf("b"), DownloadLogic.plan(oneRunning).toStart.map { it.key })
        val bothRunning = listOf(item("a", DownloadState.Running, at = 1), item("b", DownloadState.Running, at = 2), item("c", at = 3))
        assertTrue(DownloadLogic.plan(bothRunning).toStart.isEmpty())
    }

    @Test fun finishedAndFailedItemsDoNotOccupySlots() {
        val items = listOf(
            item("done", DownloadState.Done), item("failed", DownloadState.Failed),
            item("running", DownloadState.Running), item("next", at = 5),
        )
        assertEquals(listOf("next"), DownloadLogic.plan(items).toStart.map { it.key })
    }

    @Test fun theCapEvictsFinishedDownloadsOldestFirst() {
        val items = listOf(
            item("old", DownloadState.Done, size = 400, at = 1),
            item("new", DownloadState.Done, size = 400, at = 2),
            item("wanted", size = 400, at = 3),
        )
        val plan = DownloadLogic.plan(items, capBytes = 1_000)
        assertEquals(listOf("old"), plan.toEvict.map { it.key })
        assertNull(plan.refusedReason)
    }

    @Test fun activeDownloadsAreNeverEvicted() {
        val items = listOf(
            item("running", DownloadState.Running, size = 800, at = 1),
            item("done", DownloadState.Done, size = 400, at = 2),
        )
        val plan = DownloadLogic.plan(items, capBytes = 500)
        assertEquals(listOf("done"), plan.toEvict.map { it.key })  // only the finished one can go
    }

    @Test fun aRequestThatCannotFitInAnEmptyCacheIsRefused() {
        val items = listOf(item("huge", size = 5_000, at = 1))
        val plan = DownloadLogic.plan(items, capBytes = 1_000)
        assertEquals("Not enough space even after clearing finished downloads.", plan.refusedReason)
        assertTrue(plan.toStart.isEmpty())
    }

    @Test fun finishingPauseResumeAndFailureKeepThePartialFile() {
        val done = DownloadLogic.afterFinished(listOf(item("a", DownloadState.Running, size = 1_000, done = 400)), "a", 9L)
        assertEquals(DownloadState.Done, done.single().state)
        assertEquals(1_000L, done.single().downloadedBytes)

        val paused = DownloadLogic.pause(listOf(item("a", DownloadState.Running, done = 400)), "a")
        assertEquals(DownloadState.Paused, paused.single().state)
        assertEquals(400L, paused.single().downloadedBytes)

        val resumed = DownloadLogic.resume(paused, "a", nowMs = 50L)
        assertEquals(DownloadState.Queued, resumed.single().state)
        assertEquals(400L, resumed.single().downloadedBytes)
        assertEquals(50L, resumed.single().queuedAt)

        val failed = DownloadLogic.failed(listOf(item("a", DownloadState.Running, done = 400)), "a", "Network lost")
        assertEquals(DownloadState.Failed, failed.single().state)
        assertEquals("Network lost", failed.single().error)
        assertEquals(400L, failed.single().downloadedBytes)
        // A failed item is not retried automatically; only an explicit resume re-queues it.
        assertEquals(DownloadState.Queued, DownloadLogic.resume(failed, "a", 60L).single().state)
    }

    @Test fun pauseOnlyAffectsARunningItem() {
        val queued = listOf(item("a", DownloadState.Queued))
        assertEquals(DownloadState.Queued, DownloadLogic.pause(queued, "a").single().state)
    }

    @Test fun theSummarySaysWhatIsHappening() {
        assertEquals("No downloads", DownloadLogic.summary(emptyList()))
        assertEquals(
            "1 downloading · 1 queued · 1 finished · 1 failed",
            DownloadLogic.summary(
                listOf(item("a", DownloadState.Running), item("b"), item("c", DownloadState.Done), item("d", DownloadState.Failed)),
            ),
        )
        assertEquals("Nothing active", DownloadLogic.summary(listOf(item("a", DownloadState.Cancelled))))
    }

    @Test fun theKeyIsStableAcrossTypeTitleAndVideo() {
        assertEquals("series|ttS1|ttS1:1:2", DownloadItem.key("series", "ttS1", "ttS1:1:2"))
    }
}
