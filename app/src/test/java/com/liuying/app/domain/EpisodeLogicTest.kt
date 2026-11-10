package com.liuying.app.domain

import com.liuying.app.domain.model.ProgressEntry
import com.liuying.app.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeLogicTest {
    private fun video(id: String, s: Int?, e: Int?, released: String? = "2020-01-01T00:00:00.000Z", title: String? = null) =
        Video(id = id, season = s, episode = e, released = released, title = title)

    private fun state(
        s: Int, e: Int, watched: Boolean = false, released: Boolean = true,
        pos: Long = 0, dur: Long = 0, at: Long = 0,
    ) = EpisodeState(
        video = video("v$s-$e", s, e),
        season = s, episode = e, released = released, watched = watched,
        positionMs = pos, durationMs = dur, inProgress = ProgressRules.isInProgress(
            ProgressEntry("series", "tt1", s, e, "v$s-$e", pos, dur, watched, at),
        ),
        lastPlayedAt = at,
    )

    @Test fun numberingDropsDuplicatesKeepingTheRicherEntryAndSorts() {
        val videos = listOf(
            video("b", 1, 2),
            video("a", 1, 1, title = "Pilot"),
            video("a2", 1, 1), // poorer duplicate of a
            video("c", 2, 1),
        )
        val numbered = EpisodeLogic.numbered(videos)
        assertEquals(listOf("a", "b", "c"), numbered.map { it.id })
    }

    @Test fun unnumberedVideosBecomeOneSeasonOnlyWhenThereAreSeveral() {
        assertEquals(emptyList<Video>(), EpisodeLogic.numbered(emptyList()))
        assertEquals(emptyList<Video>(), EpisodeLogic.numbered(listOf(video("x", null, null))))
        val channels = EpisodeLogic.numbered(listOf(video("x", null, null), video("y", null, null)))
        assertEquals(listOf(1 to 1, 1 to 2), channels.map { it.season to it.episode })
    }

    @Test fun statesCarryProgressMatchedByVideoIdOrBySeasonAndEpisode() {
        val videos = listOf(video("v1-1", 1, 1), video("v1-2", 1, 2))
        val progress = listOf(
            ProgressEntry("series", "tt1", 1, 1, "v1-1", 0, 60_000, watched = true, 5L),         // by id, watched
            ProgressEntry("series", "tt1", 1, 2, "other", 90_000, 600_000, watched = false, 7L),   // by season/episode, in progress
        )
        val states = EpisodeLogic.buildStates(videos, progress, todayEpochDay = IsoDate.daysFromCivil(2026, 1, 1))
        assertEquals(listOf(true, false), states.map { it.watched })
        assertEquals(90_000L, states[1].positionMs)
        assertEquals(true, states[1].inProgress)
        assertTrue(states.all { it.released })
    }

    @Test fun seasonsListSpecialsLast() {
        val states = listOf(state(0, 1), state(1, 1), state(2, 1), state(1, 2))
        assertEquals(listOf(1, 2, 0), EpisodeLogic.seasons(states))
    }

    @Test fun theSequenceIncludesSpecialsOnlyWhenTheyWerePlayed() {
        val unplayedSpecial = state(0, 1)
        val playedSpecial = state(0, 2, watched = true)
        val regular = state(1, 1)
        // The played special takes part (and sorts before season 1); the unplayed one is ignored.
        assertEquals(
            listOf(0 to 2, 1 to 1),
            EpisodeLogic.sequence(listOf(unplayedSpecial, playedSpecial, regular)).map { it.season to it.episode },
        )
        assertEquals(listOf(1 to 1), EpisodeLogic.sequence(listOf(unplayedSpecial, regular)).map { it.season to it.episode })
    }

    @Test fun nextSkipsUnairedEpisodes() {
        val states = listOf(
            state(1, 1, watched = true),
            state(1, 2, released = false),
            state(1, 3),
        )
        assertEquals(1 to 3, EpisodeLogic.nextAfterHighestWatched(states)?.let { it.season to it.episode })
        // Nothing released left: no Up Next card at all.
        assertNull(EpisodeLogic.nextAfterHighestWatched(listOf(state(1, 1, watched = true), state(1, 2, released = false))))
        assertNull(EpisodeLogic.nextAfterHighestWatched(listOf(state(1, 1))))
    }

    @Test fun nextFollowsTheHighestWatchedEpisodeNotTheFirstGap() {
        val states = listOf(state(1, 1), state(1, 2), state(1, 3, watched = true), state(1, 4))
        assertEquals(1 to 4, EpisodeLogic.nextAfterHighestWatched(states)?.let { it.season to it.episode })
    }

    @Test fun playResumesAnEpisodeThatIsInProgress() {
        val target = EpisodeLogic.playTarget(listOf(state(1, 1, watched = true), state(1, 2, pos = 90_000, dur = 600_000, at = 9L)))
        assertEquals(PlayKind.Resume, target.kind)
        assertEquals(1 to 2, target.episode?.let { it.season to it.episode })
    }

    @Test fun playContinuesAfterTheHighestWatchedAndStartsFromTheFirstEpisodeOtherwise() {
        val fresh = EpisodeLogic.playTarget(listOf(state(1, 1), state(1, 2)))
        assertEquals(PlayKind.Start, fresh.kind)
        assertEquals(1 to 1, fresh.episode?.let { it.season to it.episode })

        val continued = EpisodeLogic.playTarget(listOf(state(1, 1, watched = true), state(1, 2)))
        assertEquals(PlayKind.Next, continued.kind)
        assertEquals(1 to 2, continued.episode?.let { it.season to it.episode })
    }

    @Test fun playWatchesAgainWhenEverythingReleasedIsWatched() {
        val target = EpisodeLogic.playTarget(listOf(state(1, 1, watched = true), state(1, 2, watched = true)))
        assertEquals(PlayKind.WatchAgain, target.kind)
        assertEquals(1 to 1, target.episode?.let { it.season to it.episode })
    }

    @Test fun theFirstEpisodeIgnoresSpecials() {
        val target = EpisodeLogic.playTarget(listOf(state(0, 1), state(1, 1)))
        assertEquals(1 to 1, target.episode?.let { it.season to it.episode })
    }

    @Test fun theDefaultSeasonIsTheEpisodeThatWouldPlay() {
        assertEquals(2, EpisodeLogic.defaultSeason(listOf(state(1, 1, watched = true), state(2, 1))))
        assertNull(EpisodeLogic.defaultSeason(emptyList()))
        // With nothing watched the first regular season is chosen, not the specials tab.
        assertEquals(1, EpisodeLogic.defaultSeason(listOf(state(0, 1), state(1, 1))))
    }
}
