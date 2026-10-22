package com.liuying.app.domain

import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Mirrors the web player's ux.test.mjs so both clients agree. */
class UxTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val today = IsoDate.daysFromCivil(2026, 10, 22)

    private fun mp(id: String, poster: String? = null, bg: String? = null, desc: String? = null) =
        MetaPreview(id = id, poster = poster, background = bg, description = desc)

    @Test fun pickFeaturedNeedsArtPrefersBackgroundAndDescriptionDedupesAndCaps() {
        val items = listOf(mp("a"), mp("b", poster = "p"), mp("c", bg = "x", desc = "d"), mp("b", poster = "p"), mp("d", bg = "x"), mp("e", poster = "p", desc = "d"))
        assertEquals(listOf("c", "d", "e"), Ux.pickFeatured(items, 3).map { it.id })
        assertEquals(emptyList<String>(), Ux.pickFeatured(emptyList()).map { it.id })
        assertEquals(4, Ux.pickFeatured(items, 10).size)
    }

    @Test fun typeFilters() {
        assertTrue(Ux.matchesType("movie", "all")); assertTrue(Ux.matchesType("series", "series")); assertFalse(Ux.matchesType("movie", "series"))
        assertEquals(listOf("all", "movie", "series", "tv"), Ux.typeFilters(listOf("movie", "tv", "tv")))
        assertEquals("Movies", Ux.typeFilterLabel("movie")); assertEquals("Tv", Ux.typeFilterLabel("tv")); assertEquals("All", Ux.typeFilterLabel("all"))
    }

    @Test fun releaseDayReadsMidnightUtcAsTheCalendarDate() {
        val expected = IsoDate.daysFromCivil(2026, 10, 24)
        // a viewer west of UTC must still see the 24th
        val la = TimeZone.getTimeZone("America/Los_Angeles")
        assertEquals(expected, Ux.releaseDay("2026-10-24T00:00:00.000Z", la))
        assertEquals(expected, Ux.releaseDay("2026-10-24", la))
        assertNull(Ux.releaseDay("nope", utc)); assertNull(Ux.releaseDay("", utc)); assertNull(Ux.releaseDay(null, utc))
        // a real timestamp is converted to the local day
        assertEquals(IsoDate.daysFromCivil(2026, 10, 23), Ux.releaseDay("2026-10-24T03:00:00Z", la))
    }

    @Test fun dayLabelAndAirStatus() {
        fun at(o: Int) = today + o
        assertEquals("Today", Ux.dayLabel(at(0), today)); assertEquals("Tomorrow", Ux.dayLabel(at(1), today)); assertEquals("Yesterday", Ux.dayLabel(at(-1), today))
        assertEquals("Tue, Oct 27", Ux.dayLabel(at(5), today))
        assertEquals("Thu", Ux.weekdayShort(today))
        assertEquals("Airs today", Ux.airStatus(0)); assertEquals("In 3 days", Ux.airStatus(3)); assertEquals("Aired 4 days ago", Ux.airStatus(-4))
        assertEquals("Airs tomorrow", Ux.airStatus(1)); assertEquals("Aired yesterday", Ux.airStatus(-1))
        assertEquals(Triple(2026, 10, 22), Ux.civil(today))
    }

    private fun iso(offset: Int): String {
        val (y, m, d) = Ux.civil(today + offset)
        return "%04d-%02d-%02dT00:00:00.000Z".format(y, m, d)
    }
    private fun videosFor(id: String, offs: List<Int>) = offs.mapIndexed { i, o -> Video(id = "$id:1:${i + 1}", season = 1, episode = i + 1, title = "E${i + 1}", released = iso(o)) }

    @Test fun buildAgendaGroupsWindowsAndOrders() {
        val lib = listOf(
            Ux.SeriesRef("s1", "series", "Alpha", null), Ux.SeriesRef("s2", "series", "Beta", null),
            Ux.SeriesRef("m1", "movie", "Film", null), Ux.SeriesRef("s3", "series", "NoMeta", null),
        )
        val metas = mapOf(
            "s1" to Ux.AgendaMeta("Alpha", null, videosFor("s1", listOf(-30, -2, 0, 4, 200))),
            "s2" to Ux.AgendaMeta("Beta", null, videosFor("s2", listOf(0, 4)) + Video(id = "s2:0:1", season = 0, episode = 1, title = "Special")),
        )
        val a = Ux.buildAgenda(lib, { metas[it] }, today, tz = utc)
        assertEquals(listOf("Today", "Mon, Oct 26"), a.upcoming.map { it.label })
        assertEquals("Alpha,Beta", a.upcoming[0].items.joinToString(",") { it.name })
        assertEquals(2, a.upcoming[1].items.size)
        assertEquals(1, a.recent.size)
        assertEquals("E2", a.recent[0].items[0].video.title)
        assertEquals(3, a.series); assertEquals(2, a.tracked)
        // 200 days out and 30 days back are outside the windows; the undated special is ignored
        assertEquals(5, (a.upcoming + a.recent).sumOf { it.items.size })
    }

    private data class S(val title: String, val res: Int, val size: Long, val src: String, val use: Int = 0)
    private fun sort(l: List<S>, key: Ux.SortKey, desc: Boolean) =
        Ux.sortStreams(l, Ux.Sort(key, desc), { it.use }, { it.res }, { it.size }, { it.src }).map { it.title }

    @Test fun nextSortTogglesAndSortStreamsOrders() {
        assertEquals(Ux.Sort(Ux.SortKey.Quality, true), Ux.nextSort(null, Ux.SortKey.Quality))
        assertEquals(Ux.Sort(Ux.SortKey.Quality, false), Ux.nextSort(Ux.Sort(Ux.SortKey.Quality, true), Ux.SortKey.Quality))
        assertEquals(Ux.Sort(Ux.SortKey.Source, false), Ux.nextSort(Ux.Sort(Ux.SortKey.Quality, false), Ux.SortKey.Source))
        val l = listOf(S("a", 720, 2, "Zed"), S("b", 2160, 9, "Alpha"), S("c", 1080, 4, "Mid"), S("t", 2160, 20, "Alpha", use = 2))
        assertEquals(listOf("b", "c", "a", "t"), sort(l, Ux.SortKey.Quality, true))
        assertEquals(listOf("a", "c", "b", "t"), sort(l, Ux.SortKey.Quality, false))
        assertEquals(listOf("b", "c", "a", "t"), sort(l, Ux.SortKey.Size, true))
        assertEquals(listOf("b", "t", "c", "a"), sort(l, Ux.SortKey.Source, false))
        assertEquals(listOf("a", "c", "b", "t"), sort(l, Ux.SortKey.Source, true))
    }

    @Test fun sortIsStableForTies() {
        val l = listOf(S("x", 1080, 1, "A"), S("y", 1080, 1, "A"), S("z", 1080, 1, "A"))
        assertEquals(listOf("x", "y", "z"), sort(l, Ux.SortKey.Quality, true))
        assertEquals(listOf("x", "y", "z"), sort(l, Ux.SortKey.Quality, false))
    }

    @Test fun splitTopNeverHidesALoneRow() {
        val a = listOf(1, 2, 3, 4, 5, 6, 7)
        assertEquals(Ux.Split(listOf(1, 2, 3, 4, 5), listOf(6, 7)), Ux.splitTop(a, 5))
        assertEquals(Ux.Split(listOf(1, 2, 3, 4, 5, 6), emptyList()), Ux.splitTop(listOf(1, 2, 3, 4, 5, 6), 5))
        assertEquals(Ux.Split(emptyList<Int>(), emptyList()), Ux.splitTop(emptyList<Int>(), 5))
    }

    @Test fun shareUrlEncodesLikeTheWeb() {
        assertEquals("https://x.example/watch#/title/series/tt1%3Aa%20b", Ux.shareUrl("https://x.example/", "series", "tt1:a b"))
        assertEquals("https://www.liuyingapp.com/watch#/title/movie/tt0", Ux.shareUrl("https://www.liuyingapp.com", "", "tt0"))
        assertEquals("%C3%A9", Ux.enc("é"))
    }

    @Test fun orderRowsAndMoveKey() {
        val rows = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), Ux.orderRows(rows, listOf("c", "a")) { it })
        assertEquals(listOf("a", "b", "c", "d"), Ux.orderRows(rows, emptyList()) { it })
        assertEquals(listOf("b", "a", "c"), Ux.moveKey(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "b", "c"), Ux.moveKey(listOf("a", "b", "c"), "a", -1))
        assertEquals(listOf("a", "b", "c"), Ux.moveKey(listOf("a", "b", "c"), "c", 1))
    }

    @Test fun webCatalogKeyMatchesTheWebFormat() {
        assertEquals("https://x.example/manifest.json#movie/top", Ux.webCatalogKey("https://x.example/manifest.json", "movie", "top"))
    }

    @Test fun suggestRanksPrefixBeforeWordStartBeforeSubstring() {
        val out = Ux.suggest("mo", listOf(listOf("The Moon Gate", "Mock Movie", "Mo Money", "Almost"), listOf("mock movie", "Moana")), 10)
        assertEquals(listOf("Mock Movie", "Mo Money", "Moana", "The Moon Gate", "Almost"), out)
        assertEquals(emptyList<String>(), Ux.suggest("", listOf(listOf("a"))))
        assertEquals(emptyList<String>(), Ux.suggest("mock movie", listOf(listOf("Mock Movie"))))
        assertEquals(2, Ux.suggest("a", listOf(listOf("a1", "a2", "a3")), 2).size)
    }
}
