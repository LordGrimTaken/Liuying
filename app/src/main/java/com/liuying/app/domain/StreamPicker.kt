package com.liuying.app.domain

import com.liuying.app.data.repo.StreamItem
import com.liuying.app.data.settings.AppSettings

/** Chooses a stream automatically when the user prefers "Auto select" over the list. */
object StreamPicker {
    private const val GB = 1_073_741_824.0

    fun pick(items: List<StreamItem>, s: AppSettings): StreamItem? {
        var c = items.filter { it.enabled && it.kind == StreamKind.Direct && !it.url.isNullOrBlank() }
        if (c.isEmpty()) return null
        val excluded = s.excludedWords.map { it.lowercase() }.filter { it.isNotBlank() }
        c.filter { i -> excluded.none { text(i).contains(it) } }.takeIf { it.isNotEmpty() }?.let { c = it }
        s.maxSizeGb?.let { max ->
            c.filter { (it.quality.sizeBytes ?: 0L) <= max * GB }.takeIf { it.isNotEmpty() }?.let { c = it }
        }
        return c.sortedWith(
            compareByDescending<StreamItem> { score(it, s) }.thenBy { it.source.order }.thenBy { it.index },
        ).first()
    }

    private fun text(i: StreamItem) = (i.displayName + " " + (i.detail ?: "")).lowercase()

    internal fun score(i: StreamItem, s: AppSettings): Int {
        val h = i.quality.resolution?.height ?: 0
        val pref = s.preferredResolution.height
        var sc = when {
            pref == null -> h / 10
            h == pref -> 1000
            h in 1 until pref -> 500 - (pref - h) / 10
            h > pref -> 400 - (h - pref) / 10
            else -> 0
        }
        if (s.preferredSourceId != null && i.source.id == s.preferredSourceId) sc += 300
        val t = text(i)
        sc += s.preferredLanguageWords.map { it.lowercase() }.filter { it.isNotBlank() }.count { t.contains(it) } * 60
        if (s.preferHdr && (i.quality.hdr || i.quality.dolbyVision || i.quality.hdr10Plus)) sc += 40
        return sc
    }
}
