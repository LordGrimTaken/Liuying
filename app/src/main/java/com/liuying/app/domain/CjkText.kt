package com.liuying.app.domain

/** Detects Han / kana / hangul so titles can be set in a CJK-capable serif face with the right locale (L020). */
object CjkText {
    fun containsHan(s: String): Boolean = s.any { isHan(it) }
    fun containsKana(s: String): Boolean = s.any { it.code in 0x3040..0x30FF }
    fun containsHangul(s: String): Boolean = s.any { it.code in 0xAC00..0xD7A3 || it.code in 0x1100..0x11FF }
    fun containsCjk(s: String): Boolean = containsHan(s) || containsKana(s) || containsHangul(s)

    private fun isHan(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF

    /** BCP 47 tag whose glyph variants fit the text: Han with kana is Japanese, hangul is Korean, otherwise Simplified Chinese. */
    fun localeTag(s: String): String? = when {
        containsKana(s) -> "ja"
        containsHangul(s) -> "ko"
        containsHan(s) -> "zh-Hans"
        else -> null
    }
}
