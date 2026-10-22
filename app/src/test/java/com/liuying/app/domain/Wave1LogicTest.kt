package com.liuying.app.domain

import com.liuying.app.data.repo.RowState
import com.liuying.app.data.repo.SearchGroup
import com.liuying.app.data.repo.StreamRepository
import com.liuying.app.data.settings.StreamSort
import com.liuying.app.domain.model.CatalogEntry
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.Manifest
import com.liuying.app.domain.model.ManifestCatalog
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.ProgressEntry
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.Stream
import com.liuying.app.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random

class Wave1LogicTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun src(id: Long) = Source(id, "https://s$id.example/manifest.json", Manifest(id = "s$id", name = "S$id"), true, id.toInt(), 0, 0)
    private fun item(name: String, desc: String? = null, i: Int = 0) =
        StreamRepository.toItem(src(1), Stream(url = "https://x/$i.mp4", name = name, description = desc), i)

    // ---- L006 / L007 / L009 search -------------------------------------------------------------------

    @Test fun exactTitleBeatsSequelsAndPartials() {
        assertEquals(0, SearchRanking.score("Alien", "alien"))
        assertEquals(1, SearchRanking.score("The Alien", "Alien"))
        assertEquals(2, SearchRanking.score("Alien: Covenant", "Alien"))
        assertEquals(3, SearchRanking.score("Return of the Alien", "alien"))
        assertEquals(4, SearchRanking.score("Aliens", "lien"))
        assertEquals(5, SearchRanking.score("Predator", "alien"))
        assertEquals(0, SearchRanking.score("Amélie", "amelie"))
        val items = listOf("Aliens", "Alien 3", "Alien", "Prometheus").map { MetaPreview(id = it, name = it) }
        assertEquals(listOf("Alien", "Aliens", "Alien 3", "Prometheus"), SearchRanking.rank(items, "Alien").map { it.id })
    }

    private fun group(id: Long, vararg names: String): SearchGroup {
        val s = src(id)
        val cat = ManifestCatalog(type = "movie", id = "top$id", name = "Top")
        return SearchGroup(CatalogEntry(s, cat), RowState.Loaded(names.map { MetaPreview(id = "$id$it", name = it) }))
    }

    @Test fun groupsWithTheExactMatchComeFirstAndBestMatchesAreDeduped() {
        val g1 = group(1, "Alien 3", "Aliens"); val g2 = group(2, "Alien", "Alien: Covenant")
        val ordered = SearchRanking.orderGroups(listOf(g1, g2), "Alien")
        assertEquals(listOf(2L, 1L), ordered.map { it.entry.source.id })
        assertEquals(listOf("Alien", "Alien: Covenant"), (ordered[0].state as RowState.Loaded).items.map { it.name })
        assertEquals(listOf("Alien"), SearchRanking.bestMatches(ordered, "Alien").map { it.first.name })
        assertEquals(listOf("S1", "S2"), SearchRanking.sourceNames(listOf(g1, g2)))
        assertEquals(2, SearchRanking.suggestions("zzz qqq www").size)
    }

    // ---- L064 / L065 streams ---------------------------------------------------------------------------

    @Test fun combinedSortOrdersByResolutionThenSize() {
        val a = item("A 1080p 2.0 GB", i = 0).let { it.copy(quality = it.quality.copy(sizeBytes = 2_000_000_000)) }
        val b = item("B 1080p 4.0 GB", i = 1).let { it.copy(quality = it.quality.copy(sizeBytes = 4_000_000_000)) }
        val c = item("C 4K 1.0 GB", i = 2).let { it.copy(quality = it.quality.copy(sizeBytes = 1_000_000_000)) }
        val d = item("D 720p 9.0 GB", i = 3).let { it.copy(quality = it.quality.copy(sizeBytes = 9_000_000_000)) }
        assertEquals(listOf("C", "B", "A", "D"), StreamRepository.sort(listOf(a, b, c, d), StreamSort.ResolutionThenSize).map { it.displayName.take(1) })
        val sorted = Ux.sortStreams(listOf(a, b, c, d), Ux.Sort(Ux.SortKey.QualityThenSize, true), { 0 }, { it.quality.resolution?.height ?: 0 }, { it.quality.sizeBytes ?: 0 }, { it.displayName })
        assertEquals(listOf("C", "B", "A", "D"), sorted.map { it.displayName.take(1) })
    }

    @Test fun qualityAndLanguageChips() {
        val a = item("Movie 1080p", "English DD5.1", 0); val b = item("Movie 720p", "VOSTFR french", 1); val c = item("Movie 4K Multi", null, 2)
        val all = listOf(a, b, c)
        assertEquals(listOf("4K", "1080p", "720p"), StreamFilters.qualityOptions(all))
        assertEquals(listOf(a), StreamFilters.apply(all, "1080p", null))
        assertEquals(listOf(b), StreamFilters.apply(all, null, "French"))
        assertEquals(listOf(a), StreamFilters.apply(all, "1080p", "English"))
        assertEquals(all, StreamFilters.apply(all, null, null))
        assertTrue("Multi" in StreamLanguages.detect("Movie 4K Multi"))
        assertTrue("Chinese" in StreamLanguages.detect("电影 国语"))
        assertTrue("Spanish" in StreamLanguages.detect("Movie", listOf("MX")))
    }

    // ---- L079 clipboard ----------------------------------------------------------------------------------

    @Test fun clipboardDetection() {
        assertEquals("https://a.example/x/manifest.json", ClipboardLink.detect(" https://a.example/x/manifest.json \n"))
        assertEquals("stremio://a.example/manifest.json", ClipboardLink.detect("stremio://a.example/manifest.json"))
        assertNull(ClipboardLink.detect("check https://a.example/manifest.json please"))
        assertNull(ClipboardLink.detect("https://a.example/page.html"))
        assertNull(ClipboardLink.detect("hello")); assertNull(ClipboardLink.detect(null)); assertNull(ClipboardLink.detect(""))
        assertTrue(ClipboardLink.shouldOffer("x", true, "", false))
        assertFalse(ClipboardLink.shouldOffer("x", true, "x", false))
        assertFalse(ClipboardLink.shouldOffer("x", false, "", false))
        assertFalse(ClipboardLink.shouldOffer("x", true, "", true))
        assertFalse(ClipboardLink.shouldOffer(null, true, "", false))
    }

    // ---- L020 -------------------------------------------------------------------------------------------

    @Test fun cjkDetection() {
        assertTrue(CjkText.containsHan("流影")); assertFalse(CjkText.containsHan("Liuying"))
        assertEquals("zh-Hans", CjkText.localeTag("你好 Movie")); assertEquals("ja", CjkText.localeTag("君の名は ひらがな")); assertEquals("ko", CjkText.localeTag("기생충"))
        assertNull(CjkText.localeTag("Parasite"))
    }

    // ---- L032 ---------------------------------------------------------------------------------------------

    @Test fun relativeTimeAndSyncPanel() {
        val now = 10_000_000_000L
        assertEquals("just now", RelativeTime.ago(now - 10_000, now))
        assertEquals("5 minutes ago", RelativeTime.ago(now - 5 * 60_000, now))
        assertEquals("1 hour ago", RelativeTime.ago(now - 60 * 60_000, now))
        assertEquals("3 hours ago", RelativeTime.ago(now - 3 * 3600_000, now))
        assertEquals("yesterday", RelativeTime.ago(now - 30 * 3600_000, now))
        assertEquals("4 days ago", RelativeTime.ago(now - 4 * 86_400_000, now))
        assertEquals("just now", RelativeTime.ago(now + 5000, now)) // clock skew never shows the future
        val ok = SyncPanelText.lines(false, null, now - 120_000, now, false, 0)
        assertEquals("Synced 2 minutes ago", ok.headline); assertFalse(ok.problem)
        val pend = SyncPanelText.lines(false, null, now - 120_000, now, true, 2)
        assertTrue(pend.detail.contains("waiting to upload")); assertTrue(pend.detail.contains("2 settings"))
        val bad = SyncPanelText.lines(false, "Sync failed: offline", null, now, false, 0)
        assertTrue(bad.problem); assertEquals("Last sync failed", bad.headline)
        assertEquals("Syncing now", SyncPanelText.lines(true, null, null, now, false, 0).headline)
        assertTrue(SyncPanelText.lines(false, null, null, now, false, 0).headline.startsWith("Not synced"))
    }

    // ---- L023 history ----------------------------------------------------------------------------------------

    private fun pe(id: String, season: Int = -1, episode: Int = -1, pos: Long = 0, dur: Long = 100_000, watched: Boolean = false, at: Long) =
        ProgressEntry("movie", id, season, episode, id, pos, dur, watched, at)

    @Test fun historyGroupsNewestFirstAndSkipsZeroedRows() {
        val day = 86_400_000L
        val today = IsoDate.epochDayOf(20 * day + 5000, utc)
        val rows = listOf(
            pe("a", pos = 50_000, at = 20 * day + 1000), pe("b", watched = true, at = 19 * day + 2000),
            pe("c", at = 20 * day + 3000), // zeroed: not history
            pe("d", watched = true, at = 20 * day + 4000), pe("e", watched = true, at = 10 * day),
        )
        val days = HistoryLogic.group(rows, today, utc) { e -> Triple("T-" + e.titleId, null, null) }
        assertEquals(listOf("Today", "Yesterday"), days.take(2).map { it.label })
        assertEquals(listOf("d", "a"), days[0].items.map { it.entry.titleId })
        assertEquals(3, days.size)
        assertEquals("Watched", HistoryLogic.statusLine(rows[1])); assertEquals("50% watched", HistoryLogic.statusLine(rows[0]))
        assertTrue(HistoryLogic.group(rows, today, utc) { null }.isEmpty())
    }

    // ---- L031 export -------------------------------------------------------------------------------------------

    @Test fun csvQuotingAndFormulaGuard() {
        assertEquals("plain", ExportLogic.field("plain"))
        assertEquals("\"a,b\"", ExportLogic.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ExportLogic.field("say \"hi\""))
        assertEquals("'=SUM(A1)", ExportLogic.field("=SUM(A1)"))
        assertEquals("\"line1\nline2\"", ExportLogic.field("line1\nline2"))
        assertEquals("1970-01-01T00:00:00Z", ExportLogic.iso(0))
    }

    @Test fun exportsContainLibraryAndHistory() {
        val lib = listOf(LibraryItem("movie", "tt1", "Heat, 1995", null, null, "1995", listOf("Crime", "Drama"), 0))
        val hist = listOf(ExportHistoryRow(pe("tt1", pos = 90_000, at = 1000), "Heat, 1995"), ExportHistoryRow(pe("tt2", 1, 3, watched = true, at = 2000), "Show"))
        val lcsv = ExportLogic.libraryCsv(lib).trim().lines()
        assertEquals("type,id,title,year,genres,added", lcsv[0]); assertEquals("movie,tt1,\"Heat, 1995\",1995,Crime; Drama,1970-01-01T00:00:00Z", lcsv[1])
        val hcsv = ExportLogic.historyCsv(hist).trim().lines()
        assertEquals(3, hcsv.size); assertTrue(hcsv[2].startsWith("movie,tt2,Show,1,3,0,100,yes,"))
        val json = ExportLogic.json(lib, hist, 0, "1.2.3")
        assertTrue(json.contains("\"library\"") && json.contains("\"history\"") && json.contains("\"season\": 1") && json.contains("Heat, 1995"))
        assertFalse(json.contains("http")) // no urls ever
        assertNotNull(kotlinx.serialization.json.Json.parseToJsonElement(json))
    }

    // ---- L004 / L010 / L115 --------------------------------------------------------------------------------------

    @Test fun hideWatchedAndSurprise() {
        val w = WatchedIndex.watchedKeys(listOf(pe("tt1", watched = true, at = 1), pe("tt2", pos = 5, at = 1), ProgressEntry("series", "s", 1, 1, "s:1:1", 0, 1, true, 1)))
        assertEquals(setOf("movie|tt1"), w)
        val items = listOf("tt1", "tt2", "tt3").map { MetaPreview(id = it, name = it) }
        assertEquals(listOf("tt2", "tt3"), WatchedIndex.hideWatched(items, "movie", w).map { it.id })
        val cands = items.map { PickCandidate(it, "movie", 1) } + PickCandidate(items[1], "movie", 2) // duplicate
        val picks = (0 until 40).map { SurprisePick.pick(cands, w, Random(it))!!.preview.id }.toSet()
        assertEquals(setOf("tt2", "tt3"), picks)
        assertNull(SurprisePick.pick(cands, setOf("movie|tt1", "movie|tt2", "movie|tt3")))
        assertEquals("tt3", SurprisePick.pick(cands, w, Random(1), avoid = "movie|tt2")!!.preview.id)
    }

    @Test fun calendarFilterAndWeekBadge() {
        val today = IsoDate.daysFromCivil(2026, 10, 22)
        fun ep(media: String, ep: Int, diff: Int) = Ux.AgendaItem(media, "series", media, null, Video("$media:1:$ep"), 1, ep, diff)
        val agenda = Ux.Agenda(
            upcoming = listOf(Ux.AgendaDay(today, 0, "Today", listOf(ep("a", 1, 0), ep("b", 1, 0))), Ux.AgendaDay(today + 3, 3, "x", listOf(ep("a", 2, 3))), Ux.AgendaDay(today + 20, 20, "y", listOf(ep("a", 3, 20)))),
            recent = listOf(Ux.AgendaDay(today - 1, -1, "Yesterday", listOf(ep("b", 0, -1)))), series = 2, tracked = 2,
        )
        val watched = CalendarFilters.watchedEpisodeKeys(listOf(ProgressEntry("series", "a", 1, 1, "a:1:1", 0, 1, true, 1)))
        assertEquals(3, CalendarFilters.weekCount(agenda, emptySet()))
        assertEquals(2, CalendarFilters.weekCount(agenda, watched))
        val f = CalendarFilters.filter(agenda, true, watched)
        assertEquals(1, f.upcoming[0].items.size)
        assertEquals(agenda, CalendarFilters.filter(agenda, false, watched))
    }
}
