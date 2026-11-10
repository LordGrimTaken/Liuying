package com.liuying.app.domain.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyLogicTest {

    private fun state(positionMs: Long, playing: Boolean, mediaId: String = "tt1", episodeId: String? = null) =
        PartyPlaybackState(mediaId, episodeId, positionMs, playing, updatedAt = 1_700_000_000_000, hostName = "mei")

    // ------------------------------------------------------------------------------------ push backoff

    @Test fun aHealthyHostWritesAtTheBaseInterval() {
        assertEquals(PartyLogic.WRITE_INTERVAL_MS, PartyLogic.pushDelayMs(0))
        assertEquals(PartyLogic.WRITE_INTERVAL_MS, PartyLogic.pushDelayMs(-1))
    }

    @Test fun failedWritesBackOffToThePushCap() {
        assertEquals(2_000L, PartyLogic.pushDelayMs(1))
        assertEquals(4_000L, PartyLogic.pushDelayMs(2))
        assertEquals(8_000L, PartyLogic.pushDelayMs(3))
        assertEquals(16_000L, PartyLogic.pushDelayMs(4))
        assertEquals(PartyLogic.MAX_PUSH_MS, PartyLogic.pushDelayMs(5))
        assertEquals(PartyLogic.MAX_PUSH_MS, PartyLogic.pushDelayMs(50))
    }

    @Test fun thePushBackoffRespectsCustomBounds() {
        assertEquals(500L, PartyLogic.pushDelayMs(0, baseMs = 500L, maxMs = 3_000L))
        assertEquals(3_000L, PartyLogic.pushDelayMs(9, baseMs = 500L, maxMs = 3_000L))
    }

    // ------------------------------------------------------------------------------------ poll backoff

    @Test fun aHealthyRoomPollsAtTheBaseInterval() {
        assertEquals(PartyLogic.POLL_MS, PartyLogic.pollDelayMs(0))
        assertEquals(PartyLogic.POLL_MS, PartyLogic.pollDelayMs(-2))
    }

    @Test fun eachConsecutiveFailureDoublesTheWaitUpToTheCap() {
        assertEquals(2_000L, PartyLogic.pollDelayMs(1))
        assertEquals(4_000L, PartyLogic.pollDelayMs(2))
        assertEquals(8_000L, PartyLogic.pollDelayMs(3))
        assertEquals(15_000L, PartyLogic.pollDelayMs(4))
        assertEquals(15_000L, PartyLogic.pollDelayMs(50))
    }

    @Test fun theBackoffRespectsCustomBounds() {
        assertEquals(500L, PartyLogic.pollDelayMs(0, baseMs = 500L, maxMs = 3_000L))
        assertEquals(500L, PartyLogic.pollDelayMs(1, baseMs = 500L, maxMs = 3_000L))   // first retry keeps the base
        assertEquals(1_000L, PartyLogic.pollDelayMs(2, baseMs = 500L, maxMs = 3_000L))
        assertEquals(3_000L, PartyLogic.pollDelayMs(9, baseMs = 500L, maxMs = 3_000L))
    }

    // ------------------------------------------------------------------------------------ codes

    @Test fun codesAreSixUppercaseLetters() {
        assertEquals("ABCDEF", PartyLogic.normalizeCode("ABCDEF"))
        assertEquals("ABCDEF", PartyLogic.normalizeCode("abcdef"))
        assertEquals("ABCDEF", PartyLogic.normalizeCode("  abc-def "))
        assertNull(PartyLogic.normalizeCode(""))
        assertNull(PartyLogic.normalizeCode("ABCDE"))
        assertNull(PartyLogic.normalizeCode("ABCDEFG"))
        assertNull(PartyLogic.normalizeCode("ABCD1F"))
        assertNull(PartyLogic.normalizeCode("ABCDEF!"))
        assertNull(PartyLogic.normalizeCode("https://www.liuyingapp.com/watch/?party=ABCDEF"))
    }

    @Test fun joinAcceptsAPastedLink() {
        assertEquals("ABCDEF", PartyLogic.codeFromInput("ABCDEF"))
        assertEquals("ABCDEF", PartyLogic.codeFromInput("https://www.liuyingapp.com/watch/?party=ABCDEF"))
        assertEquals("ABCDEF", PartyLogic.codeFromInput("https://www.liuyingapp.com/watch/?party=abcdef&x=1"))
        assertEquals("ABCDEF", PartyLogic.codeFromInput("watch?pArTy=ABCDEF"))
        assertNull(PartyLogic.codeFromInput("https://www.liuyingapp.com/watch/"))
        assertNull(PartyLogic.codeFromInput("hello"))
    }

    @Test fun shareLinkUsesTheWatchRoute() {
        assertEquals("https://www.liuyingapp.com/watch/?party=ABCDEF", PartyLogic.shareLink("abcdef"))
        assertEquals("https://www.liuyingapp.com/watch/?party=ABCDEF", PartyLogic.shareLink("ABCDEF"))
        assertNull(PartyLogic.shareLink("nope"))
        assertNull(PartyLogic.shareLink(null))
    }

    // ------------------------------------------------------------------------------------ drift + rev

    @Test fun appliesANewerStateWhenThePositionDrifts() {
        val d = PartyLogic.evaluate(lastRev = 3, responseRev = 4, state = state(100_000, true), localPositionMs = 97_000, localPlaying = true)
        assertEquals(4L, d.lastRev)
        assertEquals(100_000L, d.apply?.positionMs)
    }

    @Test fun doesNotApplyWithinTwoSeconds() {
        val d = PartyLogic.evaluate(lastRev = 3, responseRev = 4, state = state(100_000, true), localPositionMs = 98_000, localPlaying = true)
        assertNull(d.apply)
        assertEquals(4L, d.lastRev) // the revision was seen even though nothing moved
    }

    @Test fun exactlyTwoSecondsIsNotDrift() {
        assertNull(PartyLogic.evaluate(0, 1, state(102_000, true), 100_000, true).apply)
        assertTrue(PartyLogic.evaluate(0, 1, state(102_001, true), 100_000, true).apply != null)
    }

    @Test fun appliesOnAPlayPauseDifferenceEvenWhenClose() {
        val remote = state(100_000, playing = false)
        val d = PartyLogic.evaluate(lastRev = 7, responseRev = 8, state = remote, localPositionMs = 100_500, localPlaying = true)
        assertEquals(false, d.apply?.playing)
        assertEquals(8L, d.lastRev)
    }

    @Test fun ignoresStatesThatAreNotNewer() {
        val same = PartyLogic.evaluate(5, 5, state(500_000, true), 0, false)
        assertNull(same.apply)
        assertEquals(5L, same.lastRev)

        val older = PartyLogic.evaluate(5, 4, state(500_000, true), 0, false)
        assertNull(older.apply)
        assertEquals(5L, older.lastRev)
    }

    @Test fun aSeenRevisionIsNeverAppliedTwice() {
        val first = PartyLogic.evaluate(0, 1, state(100_000, true), 0, false)
        assertTrue(first.apply != null)
        // The next poll repeats the same rev: applying again would fight the local player for the remaining second.
        val second = PartyLogic.evaluate(first.lastRev, 1, state(100_000, true), 1_000, true)
        assertNull(second.apply)
        assertEquals(1L, second.lastRev)
    }

    @Test fun onlyTheMatchingTitleIsApplied() {
        val local = PartyLocalPlayback(positionMs = 0, playing = false, mediaId = "tt1", episodeId = "tt1:1:1")
        assertTrue(PartyLogic.sameTitle(state(100_000, true, "tt1", "tt1:1:1"), local))
        assertFalse(PartyLogic.sameTitle(state(100_000, true, "tt1", "tt1:1:2"), local))
        assertFalse(PartyLogic.sameTitle(state(100_000, true, "tt2", null), local))
        // A caller that does not know its title must still be able to follow.
        assertTrue(PartyLogic.sameTitle(state(100_000, true, "tt9", null), local.copy(mediaId = null)))
    }

    // ------------------------------------------------------------------------------------ host writes

    @Test fun hostWritesAtMostOncePerTwoSeconds() {
        assertTrue(PartyLogic.shouldPush(nowMs = 1_000, lastPushAtMs = 0))
        assertFalse(PartyLogic.shouldPush(nowMs = 2_999, lastPushAtMs = 1_000))
        assertTrue(PartyLogic.shouldPush(nowMs = 3_000, lastPushAtMs = 1_000))
        assertTrue(PartyLogic.shouldPush(nowMs = 10_000, lastPushAtMs = 3_000))
    }

    @Test fun aClockSteppingBackDoesNotWedgeTheHost() {
        assertTrue(PartyLogic.shouldPush(nowMs = 500, lastPushAtMs = 1_000))
    }

    @Test fun pushesWhenPlayingTogglesOrThePositionMovedEnough() {
        val previous = PartyLocalPlayback(100_000, true, "tt1", null)
        assertTrue(PartyLogic.pushWorthwhile(PartyLocalPlayback(100_000, false, "tt1", null), previous))
        assertTrue(PartyLogic.pushWorthwhile(PartyLocalPlayback(101_000, true, "tt1", null), previous))
        assertFalse(PartyLogic.pushWorthwhile(PartyLocalPlayback(100_999, true, "tt1", null), previous))
        assertTrue(PartyLogic.pushWorthwhile(previous, null))
    }
}
