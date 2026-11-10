package com.liuying.app.domain

import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.ProgressEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLogicTest {
    private fun item(id: String, name: String, genres: List<String> = listOf("Drama"), year: String? = "2020") =
        LibraryItem("movie", id, name, null, null, year, genres, 0L)

    private fun movie(id: String, watched: Boolean = true) =
        ProgressEntry("movie", id, ProgressEntry.NO_EPISODE, ProgressEntry.NO_EPISODE, id, 30_000, 60_000, watched, 0L)

    private fun episode(id: String, s: Int, e: Int) =
        ProgressEntry("series", id, s, e, "$id:$s:$e", 0, 60_000, true, 0L)

    @Test fun isoIsUtcAndFixedWidth() {
        assertEquals("2026-01-02T03:04:05Z", ExportLogic.iso(1_767_323_045_000L))
    }

    @Test fun fieldsAreQuotedOnlyWhenTheyNeedToBe() {
        assertEquals("plain", ExportLogic.field("plain"))
        assertEquals("\"a,b\"", ExportLogic.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ExportLogic.field("say \"hi\""))
        assertEquals("\"two\nlines\"", ExportLogic.field("two\nlines"))
    }

    @Test fun formulalikeCellsAreNeutralised() {
        assertEquals("'=1+1", ExportLogic.field("=1+1"))
        assertEquals("'+SUM(A1)", ExportLogic.field("+SUM(A1)"))
        assertEquals("'-looks like a title", ExportLogic.field("-looks like a title"))
        assertEquals("'@user", ExportLogic.field("@user"))
        // Quoting still applies after the apostrophe.
        assertEquals("\"'=1,2\"", ExportLogic.field("=1,2"))
    }

    @Test fun libraryCsvHasAHeaderAndOneRowPerTitle() {
        val csv = ExportLogic.libraryCsv(listOf(item("tt1", "A Film"), item("tt2", "B Film", genres = listOf("Drama", "Crime"))))
        val lines = csv.split("\r\n").filter { it.isNotEmpty() }
        assertEquals("type,id,title,year,genres,added", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[2].contains("Drama; Crime"))
    }

    @Test fun historyCsvLeavesEpisodeColumnsEmptyForAMovie() {
        val csv = ExportLogic.historyCsv(
            listOf(
                ExportHistoryRow(movie("tt1"), "A Film"),
                ExportHistoryRow(episode("ttS1", 1, 2), "A Show"),
            ),
        )
        val lines = csv.split("\r\n").filter { it.isNotEmpty() }
        assertEquals("type,id,title,season,episode,position_seconds,duration_seconds,watched,updated", lines[0])
        assertEquals("movie,tt1,A Film,,,30,60,yes,1970-01-01T00:00:00Z", lines[1])
        // the movie row has no season/episode, the episode row does
        assertTrue(lines[1].startsWith("movie,tt1,A Film,,,"))
        assertTrue(lines[2].startsWith("series,ttS1,A Show,1,2,"))
    }

    @Test fun jsonRoundTripsThroughTheParser() {
        val json = ExportLogic.json(
            listOf(item("tt1", "A Film"), item("tt2", "No Year", year = null)),
            listOf(ExportHistoryRow(movie("tt1"), "A Film"), ExportHistoryRow(episode("ttS1", 2, 3), "A Show")),
            exportedAtMs = 1_767_323_045_000L,
            appVersion = "0.6.1",
        )
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("Liuying", root["app"]!!.jsonPrimitive.content)
        assertEquals("0.6.1", root["version"]!!.jsonPrimitive.content)
        assertEquals(1, root["schema"]!!.jsonPrimitive.content.toInt())
        assertEquals("2026-01-02T03:04:05Z", root["exportedAt"]!!.jsonPrimitive.content)
        val library = root["library"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, library.size)
        assertEquals("A Film", library[0]["title"]!!.jsonPrimitive.content)
        assertTrue(library[1]["year"] is kotlinx.serialization.json.JsonNull)
        val history = root["history"]!!.jsonArray.map { it.jsonObject }
        assertNull(history[0]["season"])                       // a movie has no season
        assertEquals(2, history[1]["season"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, history[1]["episode"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun aTitleWithQuotesAndNewlinesStaysValidJson() {
        val json = ExportLogic.json(listOf(item("tt1", "He said \"hi\"\nand left")), emptyList(), 0L, "1")
        val title = Json.parseToJsonElement(json).jsonObject["library"]!!.jsonArray[0].jsonObject["title"]!!
        assertEquals("He said \"hi\"\nand left", title.jsonPrimitive.content)
    }
}
