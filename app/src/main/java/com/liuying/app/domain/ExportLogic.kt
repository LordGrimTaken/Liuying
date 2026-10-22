package com.liuying.app.domain

import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.ProgressEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A history row together with the title it belongs to, for export. */
data class ExportHistoryRow(val entry: ProgressEntry, val title: String)

/** Library and history as CSV or JSON (L031). Pure; no sources, URLs or credentials are ever included. */
object ExportLogic {
    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** RFC 4180 quoting; also neutralises spreadsheet formulas (a cell starting with = + - @ gets a leading apostrophe). */
    fun field(raw: String): String {
        var s = raw
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun row(vararg cols: String) = cols.joinToString(",") { field(it) } + "\r\n"

    fun libraryCsv(items: List<LibraryItem>): String = buildString {
        append(row("type", "id", "title", "year", "genres", "added"))
        items.forEach { append(row(it.type, it.id, it.name, it.year.orEmpty(), it.genres.joinToString("; "), iso(it.addedAt))) }
    }

    fun historyCsv(rows: List<ExportHistoryRow>): String = buildString {
        append(row("type", "id", "title", "season", "episode", "position_seconds", "duration_seconds", "watched", "updated"))
        rows.forEach { (e, title) ->
            append(row(
                e.type, e.titleId, title, if (e.isEpisode) e.season.toString() else "", if (e.isEpisode) e.episode.toString() else "",
                (e.positionMs / 1000).toString(), (e.durationMs / 1000).toString(), if (e.watched) "yes" else "no", iso(e.updatedAt),
            ))
        }
    }

    private val pretty = Json { prettyPrint = true }

    fun json(items: List<LibraryItem>, rows: List<ExportHistoryRow>, exportedAtMs: Long, appVersion: String): String {
        val obj = buildJsonObject {
            put("app", "Liuying")
            put("version", appVersion)
            put("exportedAt", iso(exportedAtMs))
            put("library", JsonArray(items.map {
                buildJsonObject {
                    put("type", it.type); put("id", it.id); put("title", it.name)
                    put("year", it.year?.let(::JsonPrimitive) ?: JsonNull)
                    put("genres", JsonArray(it.genres.map(::JsonPrimitive)))
                    put("added", iso(it.addedAt))
                }
            }))
            put("history", JsonArray(rows.map { (e, title) ->
                buildJsonObject {
                    put("type", e.type); put("id", e.titleId); put("title", title)
                    if (e.isEpisode) { put("season", e.season); put("episode", e.episode) }
                    put("positionSeconds", e.positionMs / 1000); put("durationSeconds", e.durationMs / 1000)
                    put("watched", e.watched); put("updated", iso(e.updatedAt))
                }
            }))
        }
        return pretty.encodeToString(JsonObject.serializer(), obj)
    }
}
