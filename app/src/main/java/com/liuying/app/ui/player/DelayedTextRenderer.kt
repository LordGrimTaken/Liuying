package com.liuying.app.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import java.util.concurrent.atomic.AtomicLong

/** Shared, thread-safe subtitle delay in microseconds. Positive = subtitles appear later. */
class SubtitleDelayHolder {
    private val us = AtomicLong(0)
    var delayMs: Long
        get() = us.get() / 1000
        set(v) { us.set(v * 1000) }
    internal fun delayUs(): Long = us.get()
}

/**
 * Wraps the engine's text renderer and feeds it a shifted playback position, which moves every cue
 * later (positive delay) or earlier (negative delay) without re-parsing the subtitle.
 */
@OptIn(UnstableApi::class)
internal class DelayedTextRenderer(delegate: Renderer, private val holder: SubtitleDelayHolder) : ForwardingRenderer(delegate) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - holder.delayUs(), elapsedRealtimeUs)
    }
}
