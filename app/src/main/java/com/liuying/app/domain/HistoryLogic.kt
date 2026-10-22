package com.liuying.app.domain

import com.liuying.app.domain.model.ProgressEntry
import java.util.TimeZone

/** One row of the watch history screen (L023). */
data class HistoryItem(val entry: ProgressEntry, val title: String, val subtitle: String?, val poster: String?) {
    val key: String get() = "${entry.type}|${entry.titleId}|${entry.season}|${entry.episode}"
}

data class HistoryDay(val epochDay: Long, val label: String, val items: List<HistoryItem>)

object HistoryLogic {
    /** Zeroed rows (cleared or marked unwatched) are kept for sync but are not history. */
    fun isVisible(e: ProgressEntry): Boolean = e.updatedAt > 0 && (e.watched || e.positionMs > 0)

    /** "Watched", "Stopped at 12:30" or "42% watched". */
    fun statusLine(e: ProgressEntry): String = when {
        e.watched -> "Watched"
        e.durationMs > 0 -> "${(e.fraction * 100).toInt()}% watched"
        else -> "Stopped at " + clock(e.positionMs)
    }

    private fun clock(ms: Long): String {
        val t = ms.coerceAtLeast(0) / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    /**
     * Newest first, grouped by local calendar day ("Today", "Yesterday", "Mon, Oct 19").
     * [describe] maps a progress row to its display title, episode line and poster; null drops the row.
     */
    fun group(
        entries: List<ProgressEntry>,
        todayEpochDay: Long,
        tz: TimeZone = TimeZone.getDefault(),
        describe: (ProgressEntry) -> Triple<String, String?, String?>?,
    ): List<HistoryDay> {
        val items = entries.filter(::isVisible).sortedByDescending { it.updatedAt }.mapNotNull { e ->
            describe(e)?.let { (t, sub, poster) -> HistoryItem(e, t, sub, poster) }
        }
        val byDay = LinkedHashMap<Long, MutableList<HistoryItem>>()
        for (i in items) byDay.getOrPut(IsoDate.epochDayOf(i.entry.updatedAt, tz)) { ArrayList() } += i
        return byDay.map { (d, l) -> HistoryDay(d, Ux.dayLabel(d, todayEpochDay), l) }
    }
}
