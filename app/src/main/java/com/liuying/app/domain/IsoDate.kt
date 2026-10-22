package com.liuying.app.domain

import java.util.TimeZone

/** Wall clock abstraction so time-dependent logic is testable. */
interface Clock {
    fun nowMs(): Long
    /** Local calendar day as days since 1970-01-01. */
    fun todayEpochDay(): Long
}

object SystemClock : Clock {
    override fun nowMs() = System.currentTimeMillis()
    override fun todayEpochDay(): Long = IsoDate.epochDayOf(nowMs(), TimeZone.getDefault())
}

/** java.time-free date helpers (minSdk 24 has no java.time without desugaring). */
object IsoDate {
    private val datePrefix = Regex("^\\s*(\\d{4})-(\\d{1,2})-(\\d{1,2})")

    /** Parses the date part of "2021-03-04", "2021-03-04T00:00:00.000Z"; null if absent/invalid. */
    fun epochDay(s: String?): Long? {
        val m = datePrefix.find(s ?: return null) ?: return null
        val (y, mo, d) = m.destructured
        val year = y.toInt(); val month = mo.toInt(); val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return daysFromCivil(year, month, day)
    }

    fun epochDayOf(nowMs: Long, tz: TimeZone): Long =
        Math.floorDiv(nowMs + tz.getOffset(nowMs), 86_400_000L)

    /** Howard Hinnant's days_from_civil. */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Episodes without a date count as released. */
    fun isReleased(released: String?, todayEpochDay: Long): Boolean {
        val d = epochDay(released) ?: return true
        return d <= todayEpochDay
    }
}
