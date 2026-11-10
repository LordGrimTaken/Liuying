package com.liuying.app.domain

import com.liuying.app.domain.model.ProgressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ViewingStatsTest {
    private val tz = TimeZone.getTimeZone("UTC")
    private val dayMs = 86_400_000L
    private val today = 20_000L * dayMs

    private fun movie(id: String, watched: Boolean, pos: Long, dur: Long, at: Long) =
        ProgressEntry("movie", id, ProgressEntry.NO_EPISODE, ProgressEntry.NO_EPISODE, id, pos, dur, watched, at)

    private fun episode(id: String, s: Int, e: Int, watched: Boolean, pos: Long, dur: Long, at: Long) =
        ProgressEntry("series", id, s, e, "$id:$s:$e", pos, dur, watched, at)

    @Test fun emptyProgressIsEmpty() {
        val s = ViewingStatsLogic.compute(emptyList(), today, tz)
        assertTrue(s.isEmpty)
        assertEquals(0, s.currentStreak)
        assertEquals("0m", s.duration())
    }

    @Test fun finishedAndPartialRowsBothCountAsTime() {
        val s = ViewingStatsLogic.compute(
            listOf(
                movie("a", watched = true, pos = 0, dur = 2 * dayMs / 24, at = today),      // 2 h
                movie("b", watched = false, pos = 30 * 60_000L, dur = 2 * dayMs / 24, at = today), // 30 min, unfinished
            ),
            today, tz,
        )
        assertEquals(2 * 3_600_000L + 30 * 60_000L, s.watchedMs)
        assertEquals(1, s.filmsFinished)
        assertEquals("2h 30m", s.duration())
    }

    @Test fun clearedRowsDoNotCount() {
        val s = ViewingStatsLogic.compute(listOf(movie("a", watched = false, pos = 0, dur = 7_200_000, at = today)), today, tz)
        assertTrue(s.isEmpty)
        assertEquals(0, s.currentStreak)
    }

    @Test fun episodesAndFilmsAreCountedSeparately() {
        val s = ViewingStatsLogic.compute(
            listOf(
                episode("s1", 1, 1, watched = true, pos = 0, dur = 3_000_000, at = today),
                episode("s1", 1, 2, watched = true, pos = 0, dur = 3_000_000, at = today),
                episode("s1", 1, 3, watched = false, pos = 60_000, dur = 3_000_000, at = today),
                movie("m1", watched = true, pos = 0, dur = 7_000_000, at = today),
            ),
            today, tz,
        )
        assertEquals(2, s.episodesFinished)
        assertEquals(1, s.filmsFinished)
    }

    @Test fun aStreakCountsBackFromToday() {
        val s = ViewingStatsLogic.compute(
            listOf(
                movie("a", true, 0, 3_600_000, today),
                movie("b", true, 0, 3_600_000, today - dayMs),
                movie("c", true, 0, 3_600_000, today - 2 * dayMs),
                movie("d", true, 0, 3_600_000, today - 5 * dayMs),
            ),
            today, tz,
        )
        assertEquals(3, s.currentStreak)
        assertEquals(3, s.bestStreak)
    }

    @Test fun aStreakThatEndedYesterdayIsStillCurrent() {
        val s = ViewingStatsLogic.compute(
            listOf(movie("a", true, 0, 3_600_000, today - dayMs), movie("b", true, 0, 3_600_000, today - 2 * dayMs)),
            today, tz,
        )
        assertEquals(2, s.currentStreak)
    }

    @Test fun aStreakThatEndedLongAgoIsNotCurrentButStillTheBest() {
        val s = ViewingStatsLogic.compute(
            listOf(
                movie("a", true, 0, 3_600_000, today - 30 * dayMs),
                movie("b", true, 0, 3_600_000, today - 29 * dayMs),
            ),
            today, tz,
        )
        assertEquals(0, s.currentStreak)
        assertEquals(2, s.bestStreak)
    }

    @Test fun aTimezoneShiftMovesTheDayBoundary() {
        val lateUtc = today + dayMs - 3_600_000L // 23:00 UTC
        val shifted = TimeZone.getTimeZone("GMT+05:00")
        // The same instant is already the next local day at +05:00.
        assertEquals(ViewingStatsLogic.epochDay(lateUtc, tz) + 1, ViewingStatsLogic.epochDay(lateUtc + 5 * 3_600_000L, tz))
        val s = ViewingStatsLogic.compute(listOf(movie("a", true, 0, 3_600_000, lateUtc)), lateUtc, shifted)
        assertEquals(1, s.currentStreak)
    }
}
