package com.liuying.app.ui.player.logic

import android.view.KeyEvent

/** What a hardware media key or remote button should do in the player (L055). */
enum class MediaKeyAction { TogglePlay, Play, Pause, SeekForward, SeekBack, Next, Previous, Stop, None }

object MediaKeys {
    fun actionFor(keyCode: Int): MediaKeyAction = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> MediaKeyAction.TogglePlay
        KeyEvent.KEYCODE_MEDIA_PLAY -> MediaKeyAction.Play
        KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaKeyAction.Pause
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> MediaKeyAction.SeekForward
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> MediaKeyAction.SeekBack
        KeyEvent.KEYCODE_MEDIA_NEXT -> MediaKeyAction.Next
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaKeyAction.Previous
        KeyEvent.KEYCODE_MEDIA_STOP -> MediaKeyAction.Stop
        else -> MediaKeyAction.None
    }
}

/** Sleep timer choices (L052). */
sealed interface SleepChoice {
    data class Minutes(val minutes: Int) : SleepChoice
    data object EndOfEpisode : SleepChoice
}

/**
 * Pure sleep-timer state. The controller calls [tick] about every 250 ms with the wall clock; a [SleepChoice.Minutes]
 * timer counts real time (so a pause does not extend it), [SleepChoice.EndOfEpisode] fires when the video's last second is reached.
 */
class SleepTimer {
    private var choice: SleepChoice? = null
    private var deadlineMs = 0L

    val active: SleepChoice? get() = choice

    fun start(c: SleepChoice, nowMs: Long) {
        choice = c
        deadlineMs = if (c is SleepChoice.Minutes) nowMs + c.minutes * 60_000L else 0L
    }

    fun cancel() { choice = null }

    /** @return true exactly once, when playback should stop now. */
    fun tick(nowMs: Long, positionMs: Long, durationMs: Long): Boolean {
        val c = choice ?: return false
        val due = when (c) {
            is SleepChoice.Minutes -> nowMs >= deadlineMs
            SleepChoice.EndOfEpisode -> durationMs > 0 && durationMs - positionMs <= 1_000
        }
        if (due) choice = null
        return due
    }

    /** Remaining time for display; null for end-of-episode or when no timer runs. */
    fun remainingMs(nowMs: Long): Long? = (choice as? SleepChoice.Minutes)?.let { (deadlineMs - nowMs).coerceAtLeast(0) }

    companion object {
        val Choices: List<SleepChoice> = listOf(SleepChoice.Minutes(15), SleepChoice.Minutes(30), SleepChoice.Minutes(60), SleepChoice.EndOfEpisode)

        fun label(c: SleepChoice): String = when (c) {
            is SleepChoice.Minutes -> "${c.minutes} min"
            SleepChoice.EndOfEpisode -> "End of episode"
        }

        /** "Sleep in 12:30" or "Sleep at end of episode". */
        fun status(c: SleepChoice?, remainingMs: Long?): String? = when {
            c == null -> null
            c is SleepChoice.EndOfEpisode -> "Sleep at end of episode"
            remainingMs != null -> "Sleep in " + TimeFormat.clock(remainingMs)
            else -> null
        }
    }
}

/**
 * Notices a stall (L048): buffering, or still loading, for longer than [thresholdMs] with the user wanting playback.
 * [tick] returns true once per stall; the user dismissing the offer silences it until the stall ends and a new one starts.
 */
class StallDetector(private val thresholdMs: Long = 15_000L) {
    private var since = -1L
    private var offered = false

    fun tick(nowMs: Long, waiting: Boolean): Boolean {
        if (!waiting) { since = -1L; offered = false; return false }
        if (since < 0) since = nowMs
        if (!offered && nowMs - since >= thresholdMs) { offered = true; return true }
        return false
    }

    fun reset() { since = -1L; offered = false }
}

/** Live numbers for the stats overlay (L049). */
data class PlaybackStats(
    val width: Int = 0,
    val height: Int = 0,
    val videoBitrate: Int = 0,
    val frameRate: Float = 0f,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val droppedFrames: Int = 0,
    val bufferAheadMs: Long = 0,
    val bandwidthBps: Long = 0,
) {
    fun lines(): List<Pair<String, String>> = buildList {
        add("Resolution" to if (width > 0 && height > 0) "${width}x$height" + (if (frameRate > 0) " @ ${formatFps(frameRate)} fps" else "") else "unknown")
        add("Video" to (videoCodec ?: "unknown") + (if (videoBitrate > 0) " · ${formatBitrate(videoBitrate.toLong())}" else ""))
        add("Audio" to (audioCodec ?: "unknown"))
        add("Dropped frames" to droppedFrames.toString())
        add("Buffer ahead" to "%.1f s".format(java.util.Locale.US, bufferAheadMs / 1000.0))
        add("Network" to if (bandwidthBps > 0) formatBitrate(bandwidthBps) else "measuring")
    }

    companion object {
        fun formatBitrate(bps: Long): String = when {
            bps >= 1_000_000 -> "%.1f Mbps".format(java.util.Locale.US, bps / 1_000_000.0)
            bps >= 1_000 -> "${bps / 1_000} kbps"
            else -> "$bps bps"
        }
        fun formatFps(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(java.util.Locale.US, f)
    }
}

/** Remembered audio and subtitle language per series (L047). */
data class TrackChoice(val audioLanguage: String? = null, val subtitleLanguage: String? = null, val subtitleOff: Boolean = false) {
    val isEmpty: Boolean get() = audioLanguage == null && subtitleLanguage == null && !subtitleOff

    fun encode(): String = listOf(audioLanguage.orEmpty(), subtitleLanguage.orEmpty(), if (subtitleOff) "1" else "0").joinToString("|")

    companion object {
        fun decode(s: String?): TrackChoice? {
            val p = s?.split("|") ?: return null
            if (p.size < 3) return null
            return TrackChoice(p[0].ifBlank { null }, p[1].ifBlank { null }, p[2] == "1").takeUnless { it.isEmpty }
        }
    }
}

/** Merge a new manual choice into what was remembered: only the touched field changes. */
fun TrackChoice?.withAudio(lang: String?): TrackChoice = (this ?: TrackChoice()).copy(audioLanguage = lang)
fun TrackChoice?.withSubtitle(lang: String?, off: Boolean): TrackChoice = (this ?: TrackChoice()).copy(subtitleLanguage = if (off) null else lang, subtitleOff = off)

/** Pure helper for the audio side of the per-series memory: the track whose language matches the remembered one. */
object RememberedAudio {
    fun pick(audio: List<TrackInfo>, remembered: String?): String? {
        if (remembered.isNullOrBlank()) return null
        return audio.firstOrNull { Languages.same(it.language, remembered) }?.key
    }
}
