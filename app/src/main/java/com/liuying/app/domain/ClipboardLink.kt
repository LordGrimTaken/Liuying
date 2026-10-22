package com.liuying.app.domain

import com.liuying.app.data.remote.AddonUrls

/** Finds a source manifest link in clipboard text (L079). Only a lone link qualifies: prose that mentions one is ignored. */
object ClipboardLink {
    fun detect(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.length > 2000 || t.any { it.isWhitespace() }) return null
        val lower = t.lowercase()
        val looksLink = lower.startsWith("stremio:") || lower.startsWith("https://") || lower.startsWith("http://")
        if (!looksLink) return null
        return AddonUrls.normalizeManifestUrl(t).getOrNull()?.let { t }
    }

    /** True when [link] should be offered: detection is on and this exact link was not offered before. */
    fun shouldOffer(link: String?, enabled: Boolean, lastSeen: String, alreadyInstalled: Boolean): Boolean =
        enabled && link != null && link != lastSeen && !alreadyInstalled
}
