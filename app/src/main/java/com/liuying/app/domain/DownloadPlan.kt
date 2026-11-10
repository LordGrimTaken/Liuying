package com.liuying.app.domain

/**
 * Offline download queue rules (L122). Pure: no files, no network, no Android.
 *
 * The storage layer owns the bytes; this owns the decisions — what may start, what progress to show, and what to
 * evict when the cap is reached. See docs/DESIGN-downloads.md.
 */
enum class DownloadState { Queued, Running, Paused, Done, Failed, Cancelled }

data class DownloadItem(
    /** Stable key: "type|titleId|videoId". */
    val key: String,
    val title: String,
    val sizeBytes: Long?,
    val state: DownloadState = DownloadState.Queued,
    val downloadedBytes: Long = 0,
    /** When it was queued: older items start first and are evicted first. */
    val queuedAt: Long = 0,
    val error: String? = null,
) {
    val isActive: Boolean get() = state == DownloadState.Running
    val isPending: Boolean get() = state == DownloadState.Queued || state == DownloadState.Paused

    /** 0..1, or null when the total size is unknown (the UI shows an indeterminate bar). */
    val fraction: Float?
        get() {
            val total = sizeBytes ?: return null
            if (total <= 0) return null
            return (downloadedBytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        }

    companion object {
        fun key(type: String, titleId: String, videoId: String) = "$type|$titleId|$videoId"
    }
}

data class DownloadPlan(
    /** Items that may start now, oldest first. */
    val toStart: List<DownloadItem> = emptyList(),
    /** Finished downloads that must be deleted to make room, oldest first. */
    val toEvict: List<DownloadItem> = emptyList(),
    /** The request is refused: it cannot fit even in an empty cache. */
    val refusedReason: String? = null,
) {
    val changes: Boolean get() = toStart.isNotEmpty() || toEvict.isNotEmpty() || refusedReason != null
}

object DownloadLogic {
    /** Two at a time: enough to make progress, few enough not to starve playback. */
    const val MAX_CONCURRENT = 2

    fun runningCount(items: List<DownloadItem>): Int = items.count { it.isActive }

    /** Byte total of what is on disk or reserved. Unknown sizes count as 0. */
    fun totalBytes(items: List<DownloadItem>): Long =
        items.filter { it.state != DownloadState.Cancelled }.sumOf { it.sizeBytes ?: it.downloadedBytes }

    /**
     * The plan the storage layer should apply after a change (an item added, one finished, one failed).
     *
     * @param capBytes the configured cache cap; null = unlimited.
     */
    fun plan(items: List<DownloadItem>, capBytes: Long? = null): DownloadPlan {
        val ordered = items.sortedWith(compareBy({ it.queuedAt }, { it.key }))
        val toStart = ArrayList<DownloadItem>()
        var slots = MAX_CONCURRENT - runningCount(items)
        for (i in ordered) {
            if (slots <= 0) break
            if (i.state == DownloadState.Queued) { toStart += i; slots-- }
        }

        if (capBytes == null) return DownloadPlan(toStart)

        val evict = ArrayList<DownloadItem>()
        var used = totalBytes(items)
        // Only finished downloads are worth reclaiming; a running one would lose work for nothing.
        val reclaimable = ordered.filter { it.state == DownloadState.Done }
        var idx = 0
        while (used > capBytes && idx < reclaimable.size) {
            val victim = reclaimable[idx++]
            evict += victim
            used -= victim.sizeBytes ?: victim.downloadedBytes
        }
        if (used > capBytes) {
            val newest = ordered.lastOrNull { it.state == DownloadState.Queued }
            if (newest != null) {
                return DownloadPlan(
                    toStart = toStart.filter { it.key != newest.key },
                    toEvict = evict,
                    refusedReason = "Not enough space even after clearing finished downloads.",
                )
            }
        }
        return DownloadPlan(toStart, evict)
    }

    /**
     * What the queue should look like after a transfer ends: the next queued item starts, and a finished item
     * counts toward the cache cap.
     */
    fun afterFinished(items: List<DownloadItem>, key: String, atMs: Long): List<DownloadItem> =
        items.map { if (it.key == key) it.copy(state = DownloadState.Done, downloadedBytes = it.sizeBytes ?: it.downloadedBytes) else it }

    /** Pause keeps the partial file; the item stays resumable. */
    fun pause(items: List<DownloadItem>, key: String): List<DownloadItem> =
        items.map { if (it.key == key && it.state == DownloadState.Running) it.copy(state = DownloadState.Paused) else it }

    /** Resume re-queues a paused or failed item without losing the bytes already on disk. */
    fun resume(items: List<DownloadItem>, key: String, nowMs: Long): List<DownloadItem> =
        items.map {
            if (it.key == key && (it.state == DownloadState.Paused || it.state == DownloadState.Failed)) {
                it.copy(state = DownloadState.Queued, error = null, queuedAt = nowMs)
            } else it
        }

    /** A failure keeps the partial file and the reason; a retry re-queues it. */
    fun failed(items: List<DownloadItem>, key: String, reason: String): List<DownloadItem> =
        items.map { if (it.key == key) it.copy(state = DownloadState.Failed, error = reason) else it }

    /** Human summary for the Downloads section. */
    fun summary(items: List<DownloadItem>): String {
        if (items.isEmpty()) return "No downloads"
        val done = items.count { it.state == DownloadState.Done }
        val running = runningCount(items)
        val queued = items.count { it.state == DownloadState.Queued }
        val failed = items.count { it.state == DownloadState.Failed }
        return buildList {
            if (running > 0) add("$running downloading")
            if (queued > 0) add("$queued queued")
            if (done > 0) add("$done finished")
            if (failed > 0) add("$failed failed")
            if (isEmpty()) add("Nothing active")
        }.joinToString(" · ")
    }
}
