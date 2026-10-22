package com.liuying.app.domain.sync

import com.liuying.app.domain.model.ProgressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {
    @Test fun threeWayFirstSyncIsUnion() {
        val m = SyncLogic.threeWay(setOf("a", "b"), setOf("b", "c"), emptySet())
        assertEquals(setOf("c"), m.addLocal); assertEquals(setOf("a"), m.addRemote)
        assertTrue(m.removeLocal.isEmpty() && m.removeRemote.isEmpty())
    }

    @Test fun threeWayPropagatesDeletes() {
        val m = SyncLogic.threeWay(local = setOf("a"), remote = setOf("a", "b"), base = setOf("a", "b"))
        assertEquals(setOf("b"), m.removeRemote)
        val m2 = SyncLogic.threeWay(local = setOf("a", "b"), remote = setOf("a"), base = setOf("a", "b"))
        assertEquals(setOf("b"), m2.removeLocal)
    }

    private fun ep(t: String, s: Int, e: Int, pos: Long, at: Long) =
        ProgressEntry("series", t, s, e, "$t:$s:$e", pos, 1000, false, at)

    @Test fun progressLastWriterWins() {
        val local = listOf(ep("tt1", 1, 1, 100, 50), ep("tt1", 1, 2, 200, 90))
        val remote = listOf(
            RemoteProgress("tt1", "tt1:1:1", 500, 1000, false, false, 60),
            RemoteProgress("tt1", "tt1:1:2", 10, 1000, false, false, 80),
            RemoteProgress("tt2", null, 5, 100, true, false, 70),
        )
        val m = SyncLogic.mergeProgress(local, remote, { null }, now = 1000)
        assertEquals(500L, m.storeLocal.first { it.episode == 1 }.positionMs)
        assertEquals(1, m.pushRemote.size); assertEquals("tt1:1:2", m.pushRemote[0].episodeId)
        val movie = m.storeLocal.first { it.titleId == "tt2" }
        assertEquals("movie", movie.type); assertTrue(movie.watched)
    }

    @Test fun equalTimestampsChangeNothing() {
        val m = SyncLogic.mergeProgress(listOf(ep("t", 1, 1, 1, 5)), listOf(RemoteProgress("t", "t:1:1", 9, 1000, false, false, 5)), { null }, 100)
        assertTrue(m.storeLocal.isEmpty() && m.pushRemote.isEmpty())
    }

    @Test fun parseEpisodeIds() {
        assertEquals(2 to 5, SyncLogic.parseEpisodeId("tt1", "tt1:2:5"))
        assertEquals(1 to 4, SyncLogic.parseEpisodeId("kitsu:9", "kitsu:9:4"))
        assertNull(SyncLogic.parseEpisodeId("tt1", "other:2:5"))
        assertNull(SyncLogic.parseEpisodeId("tt1", "tt1:x:5"))
    }

    @Test fun toRemoteClampsAndValidates() {
        val e = ProgressEntry("movie", "m", -1, -1, "m", 5000, 0, false, 9_999_999)
        val r = SyncLogic.toRemote(e, now = 1000)!!
        assertEquals(0L, r.positionMs); assertEquals(1000L, r.updatedAt); assertNull(r.episodeId)
        assertNull(SyncLogic.toRemote(e.copy(titleId = "x".repeat(200)), 1000))
    }

    @Test fun parsesIso() {
        assertEquals(1_700_000_000_123L, SyncLogic.parseIsoMs("2023-11-14T22:13:20.123Z"))
        assertEquals(1_700_000_000_000L, SyncLogic.parseIsoMs("2023-11-15T00:13:20+02:00"))
        assertNull(SyncLogic.parseIsoMs("yesterday"))
    }
}
