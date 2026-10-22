package com.liuying.app.domain

import com.liuying.app.domain.model.PlaybackRef
import com.liuying.app.domain.model.ProgressEntry

object ProgressRules {
    const val DEFAULT_THRESHOLD = 0.90
    const val MIN_THRESHOLD = 0.80
    const val MAX_THRESHOLD = 0.98

    /** Below this fraction or this many ms an entry counts as "not started". */
    const val NOT_STARTED_FRACTION = 0.03
    const val NOT_STARTED_MS = 60_000L

    /** Player resume window (04): resume only when between 3 % and 92 %. */
    const val RESUME_MAX_FRACTION = 0.92

    fun clampThreshold(t: Double) = t.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    /** The completion rule: position has reached [threshold] of a known duration. */
    fun isCompleted(positionMs: Long, durationMs: Long, threshold: Double = DEFAULT_THRESHOLD): Boolean =
        durationMs > 0 && positionMs >= durationMs * clampThreshold(threshold)

    fun isStarted(e: ProgressEntry): Boolean =
        e.positionMs >= NOT_STARTED_MS && e.fraction >= NOT_STARTED_FRACTION

    /** Started and not yet completed: eligible for Continue Watching. */
    fun isInProgress(e: ProgressEntry, threshold: Double = DEFAULT_THRESHOLD): Boolean =
        isStarted(e) && !isCompleted(e.positionMs, e.durationMs, threshold)

    /**
     * Where playback should resume, or null to start from 0. If the stream's duration differs from the
     * stored one by more than ~5 % the stored fraction is used instead of the absolute position.
     */
    fun resumePositionMs(e: ProgressEntry, newDurationMs: Long? = null): Long? {
        if (e.positionMs <= 0) return null
        if (e.durationMs <= 0) return e.positionMs
        if (e.fraction < NOT_STARTED_FRACTION || e.fraction > RESUME_MAX_FRACTION) return null
        val nd = newDurationMs ?: return e.positionMs
        if (nd <= 0) return e.positionMs
        val diff = kotlin.math.abs(nd - e.durationMs).toDouble() / e.durationMs
        val pos = if (diff <= 0.05) e.positionMs else (e.fraction * nd).toLong()
        return pos.coerceIn(0, nd)
    }

    /**
     * Computes the record to store for a progress report. Position is clamped to the duration. Crossing the
     * threshold sets watched and clears the resume position; a previously-watched flag is never cleared by playback.
     */
    fun applyUpdate(
        existing: ProgressEntry?,
        ref: PlaybackRef,
        positionMs: Long,
        durationMs: Long,
        now: Long,
        threshold: Double = DEFAULT_THRESHOLD,
        streamHint: String? = existing?.streamHint,
    ): ProgressEntry {
        val dur = durationMs.coerceAtLeast(0)
        val pos = if (dur > 0) positionMs.coerceIn(0, dur) else positionMs.coerceAtLeast(0)
        val completed = isCompleted(pos, dur, threshold)
        return ProgressEntry(
            type = ref.type,
            titleId = ref.titleId,
            season = ref.season,
            episode = ref.episode,
            videoId = ref.videoId,
            positionMs = if (completed) 0 else pos,
            durationMs = if (dur > 0) dur else existing?.durationMs ?: 0,
            watched = completed || existing?.watched == true,
            updatedAt = now,
            streamHint = streamHint,
        )
    }
}
