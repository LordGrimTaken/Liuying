package com.liuying.app.domain

/**
 * Small pure guards found by the Android QA pass (docs/qa-android-report.md). No Android types, unit tested in QaGuardsTest.
 */
object QaGuards {
    /** One Han, kana or Hangul character is already a meaningful query ("龙", "鬼"); Latin text needs two letters. */
    fun minSearchLength(query: String): Int {
        val first = query.trim().firstOrNull() ?: return 2
        return if (isCjk(first)) 1 else 2
    }

    fun isSearchable(query: String): Boolean {
        val q = query.trim()
        return q.isNotEmpty() && q.length >= minSearchLength(q)
    }

    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.HANGUL_JAMO
    }

    /**
     * An addon-supplied "open this link" target (stream `externalUrl`) may only open a browser-like or install link. Anything else
     * (file:, content:, intent:, tel:, sms:, market: ...) is refused so a source cannot launch arbitrary apps.
     */
    fun isSafeExternalUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        val scheme = u.substringBefore(':', "").lowercase()
        if (scheme.isEmpty() || u.length <= scheme.length + 1) return false
        return scheme in setOf("http", "https", "stremio", "magnet")
    }

    /** Option lists in sloppy manifests repeat values; LazyRow keys must be unique. */
    fun distinctOptions(options: List<String>): List<String> = options.filter { it.isNotBlank() }.distinct()
}
