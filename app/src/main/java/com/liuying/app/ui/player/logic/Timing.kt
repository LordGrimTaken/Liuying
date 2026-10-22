package com.liuying.app.ui.player.logic

/** Decides when the next-episode overlay shows and when its countdown runs. */
object NextEpisodeTiming {
    const val OFFER_WINDOW_MS = 45_000L
    const val COUNTDOWN_MS = 10_000L
    private const val MIN_DURATION_MS = 3 * 60_000L

    sealed interface Phase {
        data object Hidden : Phase
        /** Overlay with "Play now" is visible. */
        data object Offer : Phase
        /** Auto-advance countdown is running. */
        data class Countdown(val secondsLeft: Int) : Phase
    }

    /**
     * @param dismissed the user pressed Cancel; the overlay stays hidden for this playback.
     * @param autoPlay auto-play next setting; without it the overlay is only an offer.
     */
    fun phase(positionMs: Long, durationMs: Long, hasNext: Boolean, dismissed: Boolean, autoPlay: Boolean, countdownMs: Long = COUNTDOWN_MS): Phase {
        val countdown = countdownMs.coerceIn(1_000L, OFFER_WINDOW_MS)
        if (!hasNext || dismissed || durationMs < MIN_DURATION_MS || positionMs < 0) return Phase.Hidden
        val remaining = durationMs - positionMs
        if (remaining > OFFER_WINDOW_MS || positionMs < durationMs / 2) return Phase.Hidden
        if (autoPlay && remaining <= countdown) {
            val secs = ((remaining + 999) / 1000).toInt().coerceIn(0, (countdown / 1000).toInt())
            return Phase.Countdown(secs)
        }
        return Phase.Offer
    }
}

/** Throttles progress writes: every [intervalMs] while playing, plus forced reports (pause, seek, stop). */
class ProgressThrottle(private val intervalMs: Long = 10_000L) {
    private var lastAtMs = Long.MIN_VALUE
    private var lastPositionMs = -1L

    /** A new media item starts: the next report is always sent. */
    fun reset() { lastAtMs = Long.MIN_VALUE; lastPositionMs = -1L }

    /** @return true if a report should be sent now (and records it). */
    fun shouldReport(nowMs: Long, positionMs: Long, force: Boolean): Boolean {
        if (positionMs < 0) return false
        val first = lastAtMs == Long.MIN_VALUE
        val due = first || nowMs - lastAtMs >= intervalMs
        if (force) {
            if (!first && positionMs == lastPositionMs && nowMs - lastAtMs < 500) return false
        } else if (!due) return false
        lastAtMs = nowMs
        lastPositionMs = positionMs
        return true
    }
}

object TimeFormat {
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    /** Delay label: "+0.5 s", "-1.2 s", "0.0 s". */
    fun delay(ms: Long): String {
        val tenths = Math.round(ms / 100.0)
        val sign = if (tenths > 0) "+" else if (tenths < 0) "-" else ""
        val abs = Math.abs(tenths)
        return "$sign${abs / 10}.${abs % 10} s"
    }
}
