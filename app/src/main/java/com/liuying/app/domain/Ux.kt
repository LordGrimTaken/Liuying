package com.liuying.app.domain

import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.Video
import com.liuying.app.domain.sync.SyncLogic
import java.util.TimeZone

/**
 * Pure helpers behind the UX pass; a port of the web player's ux.js so both clients behave the same.
 * No Android or I/O here, everything is unit tested.
 */
object Ux {
    // ---------------------------------------------------------------------------- featured / search filter

    /** Items worth featuring: need art; prefer ones with a background and a synopsis. Stable, de-duplicated, max [n]. */
    fun pickFeatured(items: List<MetaPreview>, n: Int = 5): List<MetaPreview> {
        val seen = HashSet<String>()
        data class Scored(val item: MetaPreview, val index: Int, val score: Int)
        val scored = ArrayList<Scored>()
        items.forEachIndexed { i, it ->
            if (it.id.isEmpty() || it.id in seen || (it.background.isNullOrBlank() && it.poster.isNullOrBlank())) return@forEachIndexed
            seen += it.id
            scored += Scored(it, i, (if (!it.background.isNullOrBlank()) 2 else 0) + (if (!it.description.isNullOrBlank()) 1 else 0))
        }
        return scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.index }).take(n).map { it.item }
    }

    /** "all" | "movie" | "series" | other catalog type. */
    fun matchesType(type: String, filter: String?): Boolean = filter.isNullOrEmpty() || filter == "all" || type == filter

    /** Distinct filter chips for the search overlay: always All, Movies, Series; extra types appended. */
    fun typeFilters(types: Collection<String>): List<String> {
        val base = listOf("all", "movie", "series")
        return base + types.filter { it.isNotEmpty() && it !in base }.distinct()
    }

    fun typeFilterLabel(t: String): String = when (t) {
        "all" -> "All"; "movie" -> "Movies"; "series" -> "Series"
        "" -> "Other"
        else -> t.replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------------------------- calendar

    /**
     * Local calendar day (epoch day) of an episode release string. Date-only and midnight-UTC stamps (what addons send
     * for date-only releases) are read as that calendar date, so viewers west of UTC do not see the previous day.
     */
    fun releaseDay(released: String?, tz: TimeZone = TimeZone.getDefault()): Long? {
        val s = released?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (dateOnly.matches(s)) return IsoDate.epochDay(s)
        val ms = SyncLogic.parseIsoMs(s)
        if (ms != null) return IsoDate.epochDayOf(ms, tz)
        return IsoDate.epochDay(s)
    }

    private val dateOnly = Regex("^\\d{4}-\\d{2}-\\d{2}(T00:00:00(\\.0+)?(Z|\\+00:00))?$")

    private val weekdays = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed") // epoch day 0 was a Thursday
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** (year, month 1-12, day) of an epoch day (Howard Hinnant's civil_from_days). */
    fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }

    fun weekdayShort(epochDay: Long): String = weekdays[Math.floorMod(epochDay, 7L).toInt()]
    fun monthShort(epochDay: Long): String = months[civil(epochDay).second - 1]
    fun dayOfMonth(epochDay: Long): Int = civil(epochDay).third

    fun dayDiff(day: Long, today: Long): Int = (day - today).toInt()

    /** "Today", "Tomorrow", "Yesterday" or "Thu, Oct 22". */
    fun dayLabel(day: Long, today: Long): String = when (dayDiff(day, today)) {
        0 -> "Today"; 1 -> "Tomorrow"; -1 -> "Yesterday"
        else -> "${weekdayShort(day)}, ${monthShort(day)} ${dayOfMonth(day)}"
    }

    data class SeriesRef(val id: String, val type: String, val name: String, val poster: String?)
    data class AgendaMeta(val name: String?, val poster: String?, val videos: List<Video>)
    data class AgendaItem(val mediaId: String, val type: String, val name: String, val poster: String?, val video: Video, val season: Int, val episode: Int, val diff: Int)
    data class AgendaDay(val epochDay: Long, val diff: Int, val label: String, val items: List<AgendaItem>)
    data class Agenda(val upcoming: List<AgendaDay>, val recent: List<AgendaDay>, val series: Int, val tracked: Int)

    /**
     * Agenda of episodes for shelf series. [metaFor] returns the (cached) meta for a series id, or null when unknown.
     * upcoming: today..[futureDays] ascending. recent: previous [pastDays], newest first.
     */
    fun buildAgenda(
        series: List<SeriesRef>,
        metaFor: (String) -> AgendaMeta?,
        today: Long,
        pastDays: Int = 7,
        futureDays: Int = 120,
        tz: TimeZone = TimeZone.getDefault(),
    ): Agenda {
        val days = LinkedHashMap<Long, MutableList<AgendaItem>>()
        var tracked = 0
        for (s in series) {
            if (s.type.isNotEmpty() && s.type != "series") continue
            val meta = metaFor(s.id) ?: continue
            tracked++
            for (v in EpisodeLogic.numbered(meta.videos)) {
                val season = v.season ?: continue
                val episode = v.episode ?: continue
                if (season == 0 && v.released.isNullOrBlank()) continue
                val day = releaseDay(v.released, tz) ?: continue
                val diff = dayDiff(day, today)
                if (diff < -pastDays || diff > futureDays) continue
                days.getOrPut(day) { ArrayList() } += AgendaItem(
                    s.id, s.type, meta.name?.takeIf { it.isNotBlank() } ?: s.name.ifBlank { s.id }, meta.poster ?: s.poster, v, season, episode, diff,
                )
            }
        }
        val byName = compareBy<AgendaItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.season }.thenBy { it.episode }
        val all = days.map { (d, items) -> AgendaDay(d, dayDiff(d, today), dayLabel(d, today), items.sortedWith(byName)) }
        return Agenda(
            upcoming = all.filter { it.diff >= 0 }.sortedBy { it.diff },
            recent = all.filter { it.diff < 0 }.sortedByDescending { it.diff },
            series = series.count { it.type.isEmpty() || it.type == "series" },
            tracked = tracked,
        )
    }

    /** "In 3 days" style status for an agenda item. */
    fun airStatus(diff: Int): String = when {
        diff == 0 -> "Airs today"
        diff == 1 -> "Airs tomorrow"
        diff > 1 -> "In $diff days"
        diff == -1 -> "Aired yesterday"
        else -> "Aired ${-diff} days ago"
    }

    // ---------------------------------------------------------------------------- streams

    enum class SortKey(val label: String, val defaultDesc: Boolean) {
        Quality("Quality", true), QualityThenSize("Quality + size", true), Size("Size", true), Source("Source", false)
    }

    data class Sort(val key: SortKey, val desc: Boolean)

    /** Tap on a sort chip: the same key flips direction, a new key starts at its natural direction. */
    fun nextSort(cur: Sort?, key: SortKey): Sort =
        if (cur != null && cur.key == key) Sort(key, !cur.desc) else Sort(key, key.defaultDesc)

    /**
     * Stable sort by quality, size or source. Playable streams rank first for quality and size ([usability]: 0 playable,
     * 1 opens elsewhere, 2 unavailable), matching the web player.
     */
    fun <T> sortStreams(
        list: List<T>, sort: Sort,
        usability: (T) -> Int, quality: (T) -> Int, size: (T) -> Long, source: (T) -> String,
    ): List<T> {
        val sign = if (sort.desc) -1 else 1
        val indexed = list.withIndex().toList()
        val cmp = Comparator<IndexedValue<T>> { a, b ->
            if (sort.key != SortKey.Source) {
                val u = usability(a.value).compareTo(usability(b.value))
                if (u != 0) return@Comparator u
            }
            val c = when (sort.key) {
                SortKey.Quality -> quality(a.value).compareTo(quality(b.value))
                SortKey.QualityThenSize -> quality(a.value).compareTo(quality(b.value)).let { if (it != 0) it else size(a.value).compareTo(size(b.value)) }
                SortKey.Size -> size(a.value).compareTo(size(b.value))
                SortKey.Source -> source(a.value).lowercase().compareTo(source(b.value).lowercase())
            }
            if (c != 0) c * sign else a.index.compareTo(b.index)
        }
        return indexed.sortedWith(cmp).map { it.value }
    }

    data class Split<T>(val top: List<T>, val rest: List<T>)

    /** Show the first [n]; collapse the rest, unless collapsing would hide a single row. */
    fun <T> splitTop(list: List<T>, n: Int = 5): Split<T> =
        if (list.size <= n + 1) Split(list, emptyList()) else Split(list.take(n), list.drop(n))

    // ---------------------------------------------------------------------------- sharing

    fun shareUrl(origin: String, type: String?, id: String, base: String = "/watch"): String =
        origin.trimEnd('/') + base + "#/title/" + enc(type?.ifEmpty { null } ?: "movie") + "/" + enc(id)

    /** encodeURIComponent: everything except A-Z a-z 0-9 - _ . ! ~ * ' ( ). */
    fun enc(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch in "-_.!~*'()") sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------------- home layout

    /** Sort rows by a saved order of keys; unknown keys keep their relative order after the known ones. */
    fun <T> orderRows(rows: List<T>, order: List<String>, key: (T) -> String): List<T> {
        val pos = order.withIndex().associate { it.value to it.index }
        return rows.withIndex().sortedWith(
            compareBy<IndexedValue<T>> { pos[key(it.value)] ?: Int.MAX_VALUE }.thenBy { it.index },
        ).map { it.value }
    }

    /** New saved order after moving [key] by [delta] within the currently displayed keys. */
    fun moveKey(displayKeys: List<String>, key: String, delta: Int): List<String> {
        val a = displayKeys.toMutableList()
        val i = a.indexOf(key)
        val j = i + delta
        if (i < 0 || j < 0 || j >= a.size) return a
        val t = a[i]; a[i] = a[j]; a[j] = t
        return a
    }

    /** The key the web player uses for a catalog, so layout settings sync between clients. */
    fun webCatalogKey(manifestUrl: String, type: String, catalogId: String) = "$manifestUrl#$type/$catalogId"

    // ---------------------------------------------------------------------------- search suggestions

    /** Quick suggestions for a partial query: prefix, then word-start, then substring. Case-insensitive, deduped. */
    fun suggest(q: String, pools: List<List<String>>, limit: Int = 6): List<String> {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        data class Hit(val s: String, val rank: Int, val order: Int)
        val seen = HashSet<String>()
        val hits = ArrayList<Hit>()
        var order = 0
        for (pool in pools) for (raw in pool) {
            val s = raw.trim()
            val l = s.lowercase()
            if (s.isEmpty() || l == needle || l in seen) continue
            val idx = l.indexOf(needle)
            if (idx < 0) continue
            seen += l
            val rank = if (idx == 0) 0 else if (l[idx - 1].let { it.isWhitespace() || it == '-' || it == ':' }) 1 else 2
            hits += Hit(s, rank, order++)
        }
        return hits.sortedWith(compareBy<Hit> { it.rank }.thenBy { it.order }).take(limit).map { it.s }
    }
}
