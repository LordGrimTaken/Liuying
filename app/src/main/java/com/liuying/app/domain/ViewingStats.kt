package com.liuying.app.domain

import com.liuying.app.domain.model.ProgressEntry
import java.util.TimeZone

/**
 * What the account has actually watched, computed from the local progress table (L026).
 *
 * Deliberately arithmetic only: no genres, no titles, nothing that needs a network call or the meta cache,
 * so the numbers are the same offline and cost nothing to show.
 */
data class ViewingStats(
    /** Time in front of the screen: the watched runtime of finished rows plus where unfinished ones stopped. */
    val watchedMs: Long = 0,
    val episodesFinished: Int = 0,
    val filmsFinished: Int = 0,
    /** Consecutive local days with something watched, counting back from today (or ending yesterday). */
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
) {
    val isEmpty: Boolean get() = watchedMs == 0L && episodesFinished == 0 && filmsFinished == 0
    val hours: Int get() = (watchedMs / 3_600_000L).toInt()
    val minutes: Int get() = ((watchedMs % 3_600_000L) / 60_000L).toInt()

    /** "12h 05m", "48m", "0m". */
    fun duration(): String = if (hours > 0) "%dh %02dm".format(hours, minutes) else "${minutes}m"
}

object ViewingStatsLogic {
    /** The local calendar day of an instant, as an epoch day. */
    fun epochDay(epochMs: Long, tz: TimeZone): Long {
        val local = epochMs + tz.getOffset(epochMs)
        return Math.floorDiv(local, 86_400_000L)
    }

    /**
     * Rows that count as something watched: a finished row, or an unfinished one the user actually played past
     * the start. Cleared history (zeroed rows kept for sync) contributes nothing.
     */
    fun isActivity(e: ProgressEntry): Boolean = e.positionMs > 0 || e.watched

    fun compute(progress: List<ProgressEntry>, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): ViewingStats {
        var watchedMs = 0L
        var episodes = 0
        var films = 0
        val days = HashSet<Long>()
        for (e in progress) {
            if (e.updatedAt <= 0 || !isActivity(e)) continue
            watchedMs += if (e.watched) e.durationMs.coerceAtLeast(0) else e.positionMs.coerceAtLeast(0)
            if (e.watched) {
                if (e.isEpisode) episodes++ else if (e.type.equals("movie", true)) films++
            }
            days += epochDay(e.updatedAt, tz)
        }
        val today = epochDay(nowMs, tz)
        // A streak that ended yesterday is still alive today (you have not watched anything yet).
        var current = 0
        var day = if (today in days) today else today - 1
        while (day in days) { current++; day-- }

        var best = 0
        var run = 0
        var previous: Long? = null
        for (d in days.sorted()) {
            run = if (previous != null && d == previous + 1) run + 1 else 1
            if (run > best) best = run
            previous = d
        }
        return ViewingStats(watchedMs, episodes, films, current, best)
    }
}
