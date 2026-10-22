package com.liuying.app.domain

import com.liuying.app.data.repo.StreamItem

/** Quality and language chips for the stream list (L065). Pure over [StreamItem]. */
object StreamFilters {
    /** Chip labels in descending order, only those present in [items]. */
    fun qualityOptions(items: List<StreamItem>): List<String> {
        val present = items.mapNotNull { it.quality.resolution }.toSet()
        val labels = Resolution.entries.filter { it in present }.map { it.label }
        return labels
    }

    fun languageOptions(items: List<StreamItem>): List<String> =
        items.flatMap { languagesOf(it) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(6)

    fun languagesOf(item: StreamItem): List<String> =
        StreamLanguages.detect(item.displayName + "\n" + (item.detail ?: ""), item.quality.languageFlags)

    /** @param quality a [Resolution.label] or null for any; @param language a name from [StreamLanguages] or null for any. */
    fun apply(items: List<StreamItem>, quality: String?, language: String?): List<StreamItem> = items.filter { i ->
        (quality == null || i.quality.resolution?.label == quality) &&
            (language == null || language in languagesOf(i))
    }
}

object StreamLanguages {
    private val o = RegexOption.IGNORE_CASE
    private const val B = "(?<![\\p{L}\\d])"
    private const val E = "(?![\\p{L}\\d])"
    private val patterns: List<Pair<String, Regex>> = listOf(
        "English" to Regex("${B}(?:english|eng|en)${E}", o),
        "Spanish" to Regex("${B}(?:spanish|espa[nñ]ol|latino|castellano|esp|spa)${E}", o),
        "French" to Regex("${B}(?:french|fran[cç]ais|vf|vff|vostfr|fra|fre|truefrench)${E}", o),
        "German" to Regex("${B}(?:german|deutsch|ger|deu)${E}", o),
        "Italian" to Regex("${B}(?:italian|italiano|ita)${E}", o),
        "Portuguese" to Regex("${B}(?:portuguese|portugu[eê]s|dublado|por|pt-br)${E}", o),
        "Russian" to Regex("${B}(?:russian|rus)${E}", o),
        "Japanese" to Regex("${B}(?:japanese|jap|jpn)${E}", o),
        "Korean" to Regex("${B}(?:korean|kor)${E}", o),
        "Chinese" to Regex("${B}(?:chinese|mandarin|cantonese|chi|zho|chs|cht)${E}|[\\u4e00-\\u9fff]", o),
        "Hindi" to Regex("${B}(?:hindi|hin)${E}", o),
        "Arabic" to Regex("${B}(?:arabic|ara)${E}", o),
        "Turkish" to Regex("${B}(?:turkish|tur)${E}", o),
        "Polish" to Regex("${B}(?:polish|pol)${E}", o),
    )
    private val flagLanguage = mapOf(
        "US" to "English", "GB" to "English", "ES" to "Spanish", "MX" to "Spanish", "FR" to "French", "DE" to "German", "IT" to "Italian",
        "PT" to "Portuguese", "BR" to "Portuguese", "RU" to "Russian", "JP" to "Japanese", "KR" to "Korean", "CN" to "Chinese",
        "TW" to "Chinese", "IN" to "Hindi", "SA" to "Arabic", "TR" to "Turkish", "PL" to "Polish",
    )

    /** Languages named in the text (and flags), in a stable order. Empty when nothing is recognisable. */
    fun detect(text: String, flags: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        for ((name, re) in patterns) if (re.containsMatchIn(text)) out += name
        for (f in flags) flagLanguage[f.uppercase()]?.let { out += it }
        if (Regex("${B}(?:multi|dual[ .\\-]?audio|multi[ .\\-]?audio)${E}", o).containsMatchIn(text)) out += "Multi"
        return out.toList()
    }
}
