package com.liuying.app.ui.player.logic

/** Language code normalisation and display names. Pure Kotlin. */
object Languages {
    private data class Lang(val iso2: String, val name: String, val iso3: List<String>)

    private val table = listOf(
        Lang("en", "English", listOf("eng")),
        Lang("es", "Spanish", listOf("spa")),
        Lang("fr", "French", listOf("fra", "fre")),
        Lang("de", "German", listOf("deu", "ger")),
        Lang("it", "Italian", listOf("ita")),
        Lang("pt", "Portuguese", listOf("por")),
        Lang("ru", "Russian", listOf("rus")),
        Lang("ja", "Japanese", listOf("jpn")),
        Lang("ko", "Korean", listOf("kor")),
        Lang("zh", "Chinese", listOf("zho", "chi", "cmn", "yue")),
        Lang("ar", "Arabic", listOf("ara")),
        Lang("hi", "Hindi", listOf("hin")),
        Lang("bn", "Bengali", listOf("ben")),
        Lang("tr", "Turkish", listOf("tur")),
        Lang("pl", "Polish", listOf("pol")),
        Lang("nl", "Dutch", listOf("nld", "dut")),
        Lang("sv", "Swedish", listOf("swe")),
        Lang("da", "Danish", listOf("dan")),
        Lang("no", "Norwegian", listOf("nor", "nob", "nno")),
        Lang("fi", "Finnish", listOf("fin")),
        Lang("cs", "Czech", listOf("ces", "cze")),
        Lang("sk", "Slovak", listOf("slk", "slo")),
        Lang("hu", "Hungarian", listOf("hun")),
        Lang("ro", "Romanian", listOf("ron", "rum")),
        Lang("bg", "Bulgarian", listOf("bul")),
        Lang("el", "Greek", listOf("ell", "gre")),
        Lang("he", "Hebrew", listOf("heb")),
        Lang("th", "Thai", listOf("tha")),
        Lang("vi", "Vietnamese", listOf("vie")),
        Lang("id", "Indonesian", listOf("ind")),
        Lang("ms", "Malay", listOf("msa", "may")),
        Lang("uk", "Ukrainian", listOf("ukr")),
        Lang("hr", "Croatian", listOf("hrv")),
        Lang("sr", "Serbian", listOf("srp")),
        Lang("sl", "Slovenian", listOf("slv")),
        Lang("fa", "Persian", listOf("fas", "per")),
        Lang("ta", "Tamil", listOf("tam")),
        Lang("te", "Telugu", listOf("tel")),
        Lang("ca", "Catalan", listOf("cat")),
        Lang("lt", "Lithuanian", listOf("lit")),
        Lang("lv", "Latvian", listOf("lav")),
        Lang("et", "Estonian", listOf("est")),
        Lang("is", "Icelandic", listOf("isl", "ice")),
        Lang("tl", "Filipino", listOf("fil", "tgl")),
    )

    private val byCode: Map<String, Lang> = buildMap {
        table.forEach { l ->
            put(l.iso2, l)
            l.iso3.forEach { put(it, l) }
        }
    }

    /** Strips region/script ("pt-BR", "zh_Hant" -> "pt", "zh") and lower-cases. Null for blank/undetermined. */
    fun base(code: String?): String? {
        val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val b = c.split('-', '_').first()
        if (b == "und" || b == "zxx" || b == "mis" || b == "mul") return null
        return b
    }

    /** Canonical key for comparisons: the 2-letter code when known, else the lower-cased base code. */
    fun canonical(code: String?): String? {
        val b = base(code) ?: return null
        return byCode[b]?.iso2 ?: b
    }

    fun same(a: String?, b: String?): Boolean {
        val ca = canonical(a) ?: return false
        val cb = canonical(b) ?: return false
        return ca == cb
    }

    /** Index of [language] within [preferences] (first match), or -1. */
    fun preferenceIndex(language: String?, preferences: List<String>): Int {
        val c = canonical(language) ?: return -1
        return preferences.indexOfFirst { canonical(it) == c }
    }

    /** Display name, e.g. "eng" -> "English"; unknown codes are shown raw (upper-cased). Null for undetermined. */
    fun displayName(code: String?): String? {
        val b = base(code) ?: return null
        return byCode[b]?.name ?: b.uppercase()
    }

    /** Name used as a sort key for grouping lists alphabetically. */
    fun sortKey(code: String?): String = displayName(code) ?: "~"
}
