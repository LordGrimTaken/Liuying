package com.liuying.app.ui.player.logic

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Finds the text encoding of a subtitle file and decodes it to a String (L069).
 * Media3's subtitle parsers read UTF-8 only, so the player decodes here and hands the parser UTF-8 bytes.
 *
 * Order: byte-order mark, UTF-16 without BOM (NUL byte pattern), strictly valid UTF-8, then the legacy code pages
 * (GBK, Big5, Shift-JIS, EUC-KR, windows-1252) scored by how plausible the decoded text is. [languageHint] (an ISO
 * language code from the subtitle's metadata) breaks ties and decides when the text is too short to score.
 */
object SubtitleCharset {
    data class Result(val charset: Charset, val text: String, val hadBom: Boolean)

    private val UTF8 = Charsets.UTF_8
    private val UTF16LE = Charsets.UTF_16LE
    private val UTF16BE = Charsets.UTF_16BE
    private val GBK = Charset.forName("GBK")
    private val BIG5 = Charset.forName("Big5")
    private val SJIS = Charset.forName("Shift_JIS")
    private val EUCKR = Charset.forName("EUC-KR")
    private val CP1252 = Charset.forName("windows-1252")

    fun decode(bytes: ByteArray, languageHint: String? = null): Result {
        if (bytes.isEmpty()) return Result(UTF8, "", false)
        // 1. byte-order marks
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Result(UTF8, String(bytes, 3, bytes.size - 3, UTF8), true)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return Result(UTF16LE, String(bytes, 2, bytes.size - 2, UTF16LE), true)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return Result(UTF16BE, String(bytes, 2, bytes.size - 2, UTF16BE), true)
        // 2. UTF-16 without a BOM: ASCII-heavy text has a NUL in every other byte
        guessUtf16(bytes)?.let { return Result(it, String(bytes, it), false) }
        // 3. valid UTF-8 (pure ASCII included)
        strictDecode(bytes, UTF8)?.let { return Result(UTF8, it, false) }
        // 4. legacy code pages
        val hint = languageHint?.lowercase()?.take(3).orEmpty()
        val high = bytes.count { it < 0 }.toDouble() / bytes.size
        // Mostly ASCII with a few accents is Western text; CJK files are mostly high bytes.
        val candidates = if (hint.isEmpty() && high < 0.15) candidatesFor("en") else candidatesFor(hint)
        var best: Charset = candidates.first()
        var bestScore = Double.NEGATIVE_INFINITY
        var bestText = ""
        for ((i, cs) in candidates.withIndex()) {
            val text = strictDecode(bytes, cs) ?: continue
            // a small bias for the first candidate (the one the language hint points at) decides ties
            val score = plausibility(text) + (if (i == 0) 0.02 else 0.0)
            if (score > bestScore) { bestScore = score; best = cs; bestText = text }
        }
        if (bestScore == Double.NEGATIVE_INFINITY) {
            // nothing decoded cleanly: windows-1252 maps every byte, so it is the safe lossy fallback
            return Result(CP1252, String(bytes, CP1252), false)
        }
        return Result(best, bestText, false)
    }

    /** Re-encodes to UTF-8 bytes for the parser (drops any BOM). */
    fun toUtf8(bytes: ByteArray, languageHint: String? = null): ByteArray {
        val r = decode(bytes, languageHint)
        return r.text.toByteArray(UTF8)
    }

    internal fun candidatesFor(hint: String): List<Charset> = when {
        hint.startsWith("zh") || hint == "chi" || hint == "zho" || hint == "cmn" || hint == "yue" -> listOf(GBK, BIG5, SJIS, EUCKR, CP1252)
        hint == "ja" || hint == "jpn" -> listOf(SJIS, GBK, BIG5, EUCKR, CP1252)
        hint == "ko" || hint == "kor" -> listOf(EUCKR, GBK, SJIS, BIG5, CP1252)
        hint.isNotEmpty() -> listOf(CP1252, GBK, BIG5, SJIS, EUCKR) // a Latin-script language: windows-1252 first
        else -> listOf(GBK, BIG5, SJIS, EUCKR, CP1252)
    }

    private fun guessUtf16(b: ByteArray): Charset? {
        val n = minOf(b.size, 400) and 1.inv()
        if (n < 8) return null
        var evenZero = 0; var oddZero = 0
        var i = 0
        while (i < n) { if (b[i] == 0.toByte()) evenZero++; if (b[i + 1] == 0.toByte()) oddZero++; i += 2 }
        val pairs = n / 2
        return when {
            oddZero > pairs * 0.3 && evenZero == 0 -> UTF16LE
            evenZero > pairs * 0.3 && oddZero == 0 -> UTF16BE
            else -> null
        }
    }

    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    // Very common characters of each script. A decode in the wrong code page produces valid-looking but rare characters,
    // so counting these separates GBK (simplified) from Big5 (traditional) and from Japanese.
    private const val SIMPLIFIED = "的一是不了人我在有他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可她里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长知民样现分将外但身些与高意进把法此实回二理美点月明其种声全工已话儿者向情部正名定女问力机给等几很业最间新什打便位因重被走电四第门相次东政海口使教西再平真听世气信北少关并内加化由却代军产入先山无总才应力体常明张"
    private const val TRADITIONAL = "的一是不了人我在有他這中大來上國個到說們為子和你地出道也時年得就那要下以生會自著去之過家學對可她裡後小麼心多天而能好都然沒日於起還發成事只作當想看文無開手十用主行方又如前所本見經頭面公同三已老從動兩長知民樣現分將外但身些與高意進把法此實回二理美點月明其種聲全工話兒者向情部正名定女問力機給等幾很業最間新什打便位因重被走電四第門相次東政海口使教西再平真聽世氣信北少關並內加化由卻代軍產入先山無總才應體常張"
    private val simplifiedSet = SIMPLIFIED.toSet() - TRADITIONAL.toSet()
    private val traditionalSet = TRADITIONAL.toSet() - SIMPLIFIED.toSet()
    private val sharedHan = SIMPLIFIED.toSet().intersect(TRADITIONAL.toSet())

    /** Higher is more plausible. Roughly: share of "expected" characters minus penalties for noise. */
    internal fun plausibility(text: String): Double {
        if (text.isEmpty()) return 0.0
        var good = 0.0
        var bad = 0.0
        var total = 0
        for (ch in text) {
            if (ch == '\n' || ch == '\r' || ch == '\t') continue
            total++
            val code = ch.code
            when {
                ch == '�' -> bad += 3
                code < 0x20 || code in 0x7F..0x9F -> bad += 3
                code < 0x80 -> good += 1.0
                ch in simplifiedSet || ch in traditionalSet -> good += 3.0 // a very common ideograph is strong evidence
                ch in sharedHan -> good += 2.0
                code in 0x3040..0x30FF -> good += 2.5 // hiragana / katakana
                code in 0xAC00..0xD7A3 -> good += 1.5 // hangul syllables
                code in 0x4E00..0x9FFF -> good += 0.6 // other ideographs: plausible but weaker
                code in 0xFF00..0xFFEF || code in 0x3000..0x303F -> good += 1.0 // full-width punctuation
                code in 0xA0..0x24F -> good += 0.7 // Latin-1 / Latin Extended
                code in 0x2010..0x203A -> good += 0.9 // dashes, quotes, ellipsis
                code in 0xE000..0xF8FF -> bad += 3 // private use: a mis-decode
                else -> bad += 1.2
            }
        }
        if (total == 0) return 0.0
        return (good - bad) / total
    }
}
