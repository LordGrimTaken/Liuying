package com.liuying.app.ui.player.logic

import java.net.URI

object GestureMath {
    /** Horizontal scrub spans at most [SCRUB_SPAN_MS] across the full width (less for short media). */
    const val SCRUB_SPAN_MS = 120_000L

    fun scrubTarget(startMs: Long, dxPx: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f) return startMs
        val span = if (durationMs > 0) minOf(SCRUB_SPAN_MS, durationMs) else SCRUB_SPAN_MS
        val target = startMs + (dxPx / widthPx * span).toLong()
        return if (durationMs > 0) target.coerceIn(0, durationMs) else target.coerceAtLeast(0)
    }

    /** Dragging up (negative dy) raises the level; a full-height drag changes it by ~1.0. */
    fun levelAfterDrag(startLevel: Float, dyPx: Float, heightPx: Float): Float {
        if (heightPx <= 0f) return startLevel
        return (startLevel - dyPx / heightPx * 1.1f).coerceIn(0f, 1f)
    }

    enum class Zone { LEFT, CENTER, RIGHT }

    fun zoneOf(x: Float, width: Float): Zone = when {
        width <= 0f -> Zone.CENTER
        x < width / 3f -> Zone.LEFT
        x > width * 2f / 3f -> Zone.RIGHT
        else -> Zone.CENTER
    }

    fun isLeftHalf(x: Float, width: Float) = x < width / 2f

    /** Seek bar: fraction -> position. */
    fun positionForFraction(fraction: Float, durationMs: Long): Long =
        (fraction.coerceIn(0f, 1f) * durationMs).toLong()
}

/** Decides which hosts get the stream's custom request headers. */
object HeaderPolicy {
    fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /**
     * Headers go to every http(s) request (manifests and segments may live on other hosts) except to hosts
     * that only serve user-selected subtitle files, so stream credentials are never sent to subtitle servers.
     */
    fun shouldApply(requestHost: String?, scheme: String?, streamHost: String?, subtitleOnlyHosts: Set<String>): Boolean {
        if (scheme != "http" && scheme != "https") return false
        val h = requestHost?.lowercase() ?: return false
        if (h == streamHost?.lowercase()) return true
        return h !in subtitleOnlyHosts
    }
}

/** Mime sniffing for side-loaded subtitle files. */
object SubtitleFormat {
    const val SUBRIP = "application/x-subrip"
    const val VTT = "text/vtt"
    const val SSA = "text/x-ssa"
    const val TTML = "application/ttml+xml"

    fun fromExtension(urlOrName: String?): String? {
        val path = urlOrName?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return null
        return when {
            path.endsWith(".srt") -> SUBRIP
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> SSA
            path.endsWith(".ttml") || path.endsWith(".dfxp") || path.endsWith(".xml") -> TTML
            else -> null
        }
    }

    fun sniff(head: String): String {
        val t = head.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            t.startsWith("WEBVTT") -> VTT
            t.startsWith("[Script Info]", ignoreCase = true) || t.startsWith("[V4", ignoreCase = true) -> SSA
            t.startsWith("<?xml") || t.startsWith("<tt", ignoreCase = true) -> TTML
            else -> SUBRIP
        }
    }
}
