package com.liuying.app.domain

import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaMergeTest {
    private fun meta(
        name: String = "",
        genres: List<String> = emptyList(),
        cast: List<String> = emptyList(),
        videos: List<Video> = emptyList(),
        poster: String? = null,
        description: String? = null,
        hasScheduled: Boolean = false,
        defaultVideoId: String? = null,
    ) = Meta(
        id = "tt1", type = "series", name = name, poster = poster, description = description,
        genres = genres, cast = cast, videos = videos,
        behaviorHints = com.liuying.app.domain.model.MetaBehaviorHints(defaultVideoId = defaultVideoId, hasScheduledVideos = hasScheduled),
    )

    private fun ep(s: Int, e: Int) = Video(id = "tt1:$s:$e", season = s, episode = e)

    @Test fun nullOtherChangesNothing() {
        val a = meta(name = "Show")
        assertSame(a, a.mergedWith(null))
    }

    @Test fun blanksAreFilledButRealValuesWin() {
        val a = meta(name = "Show", poster = "mine", description = null)
        val b = meta(name = "Other name", poster = "theirs", description = "from B")
        val m = a.mergedWith(b)
        assertEquals("Show", m.name)
        assertEquals("mine", m.poster)
        assertEquals("from B", m.description)
    }

    @Test fun listFieldsAreUnionedWithoutCaseDuplicates() {
        val m = meta(genres = listOf("Drama", "Crime"), cast = listOf("A B"))
            .mergedWith(meta(genres = listOf("drama", "Thriller"), cast = listOf("A B", "C D")))
        assertEquals(listOf("Drama", "Crime", "Thriller"), m.genres)
        assertEquals(listOf("A B", "C D"), m.cast)
    }

    @Test fun aPartialEpisodeListFromOneSourceIsCompletedByAnother() {
        val mine = meta(videos = listOf(ep(1, 1), ep(1, 2)))
        val theirs = meta(videos = listOf(ep(1, 2), ep(1, 3), ep(2, 1)))
        val m = mine.mergedWith(theirs)
        assertEquals(
            listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1),
            m.videos.map { it.season to it.episode },
        )
    }

    @Test fun unnumberedVideosAreKeyedByIdSoChannelsDoNotCollapse() {
        val a = meta(videos = listOf(Video(id = "chan-a")))
        val b = meta(videos = listOf(Video(id = "chan-b")))
        val m = a.mergedWith(b)
        assertEquals(listOf("chan-a", "chan-b"), m.videos.map { it.id })
    }

    @Test fun behaviourHintsAreKeptFromEitherSide() {
        val m = meta(defaultVideoId = "v1").mergedWith(meta(hasScheduled = true, defaultVideoId = "v2"))
        assertEquals("v1", m.behaviorHints.defaultVideoId)
        assertTrue(m.behaviorHints.hasScheduledVideos)
    }

    @Test fun mergingIsStableWhenOneSideIsEmpty() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        assertEquals(videos, meta(videos = videos).mergedWith(meta()).videos)
        assertEquals(videos, meta().mergedWith(meta(videos = videos)).videos)
        assertEquals(emptyList<Video>(), meta().mergedWith(meta()).videos)
    }
}
