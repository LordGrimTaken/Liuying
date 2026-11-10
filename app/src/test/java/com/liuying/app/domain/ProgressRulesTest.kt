package com.liuying.app.domain

import com.liuying.app.domain.model.PlaybackRef
import com.liuying.app.domain.model.ProgressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRulesTest {
    private val minute = 60_000L

    private fun entry(pos: Long, dur: Long, watched: Boolean = false) =
        ProgressEntry("movie", "tt1", ProgressEntry.NO_EPISODE, ProgressEntry.NO_EPISODE, "tt1", pos, dur, watched, 1L)

    @Test fun nothingWatchedMeansNoResume() {
        assertNull(ProgressRules.resumePositionMs(entry(0, 90 * minute)))
        // Below the "started" floor (3 % and a minute) the title starts from the beginning.
        assertNull(ProgressRules.resumePositionMs(entry(30_000, 90 * minute)))
        // 3 minutes into a 5 minute clip is properly started, so it does resume.
        assertEquals(3 * minute, ProgressRules.resumePositionMs(entry(3 * minute, 5 * minute)))
        assertEquals(90 * minute, ProgressRules.resumePositionMs(entry(90 * minute, 0)))  // unknown duration: trust the position
    }

    @Test fun aStartedTitleResumesWhereItStopped() {
        assertEquals(20 * minute, ProgressRules.resumePositionMs(entry(20 * minute, 90 * minute)))
    }

    @Test fun aFinishedTitleDoesNotResume() {
        assertNull(ProgressRules.resumePositionMs(entry(85 * minute, 90 * minute))) // 94 %, past the default 90 %
    }

    @Test fun theResumeWindowFollowsTheUsersCompletionThreshold() {
        // 93 % watched: not finished when the threshold is 98 %, so it must resume — the old fixed 92 % cap
        // restarted it from zero and never called it watched either.
        val p = entry(93 * minute, 100 * minute)
        assertEquals(93 * minute, ProgressRules.resumePositionMs(p, threshold = 0.98))
        // At the default threshold the same position is finished.
        assertNull(ProgressRules.resumePositionMs(p, threshold = 0.90))
        // 93 % is finished once the threshold drops to 92 %, and then there is nothing to resume.
        assertNull(ProgressRules.resumePositionMs(p, threshold = 0.92))
    }

    @Test fun aDifferentStreamDurationKeepsTheFraction() {
        val stored = entry(45 * minute, 90 * minute) // 50 %
        assertEquals(30 * minute, ProgressRules.resumePositionMs(stored, newDurationMs = 60 * minute))
        // A small difference keeps the absolute position.
        assertEquals(45 * minute, ProgressRules.resumePositionMs(stored, newDurationMs = 92 * minute))
        // The result is scaled to the new duration, never past it.
        assertEquals(5 * minute, ProgressRules.resumePositionMs(stored, newDurationMs = 10 * minute))
    }

    @Test fun rewindNeverGoesBelowZeroAndCapsAtThirtySeconds() {
        assertEquals(0L, ProgressRules.rewound(10_000, 30))
        assertEquals(0L, ProgressRules.rewound(null, 10))
        assertEquals(330_000L, ProgressRules.rewound(6 * minute, 60))     // the tail is not rewindable
        assertEquals(5 * minute, ProgressRules.rewound(5 * minute + 30_000, 30))
    }

    @Test fun theCompletionThresholdIsClampedToTheSupportedRange() {
        assertEquals(0.80, ProgressRules.clampThreshold(0.10), 0.0001)
        assertEquals(0.98, ProgressRules.clampThreshold(1.0), 0.0001)
        assertEquals(0.90, ProgressRules.clampThreshold(0.90), 0.0001)
    }

    @Test fun applyUpdateStoresAFinishedRowAsWatchedAndKeepsTheOldFlag() {
        val ref = PlaybackRef.movie("movie", "tt1")
        val done = ProgressRules.applyUpdate(null, ref, 95 * minute, 100 * minute, now = 7L)
        assertTrue(done.watched)
        assertEquals(0L, done.positionMs)
        assertEquals(100 * minute, done.durationMs)

        // A previously watched title stays watched even if the user re-watches from the start.
        val again = ProgressRules.applyUpdate(done, ref, 2 * minute, 100 * minute, now = 8L)
        assertTrue(again.watched)
        assertEquals(2 * minute, again.positionMs)
    }

    @Test fun applyUpdateClampsPositionAndKeepsAKnownDuration() {
        val ref = PlaybackRef.movie("movie", "tt1")
        val e = ProgressRules.applyUpdate(null, ref, 200 * minute, 100 * minute, now = 7L)
        assertEquals(100 * minute, e.durationMs)
        assertEquals(0L, e.positionMs) // clamped to the end, therefore completed

        val unknown = ProgressRules.applyUpdate(entry(5 * minute, 90 * minute), ref, 6 * minute, 0, now = 8L)
        assertEquals(90 * minute, unknown.durationMs) // a report without a duration keeps the known one
        assertFalse(unknown.watched)
    }
}
