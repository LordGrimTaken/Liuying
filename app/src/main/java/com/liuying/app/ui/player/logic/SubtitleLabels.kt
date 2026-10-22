package com.liuying.app.ui.player.logic

/** Builds the human labels shown in the track sheet. */
object TrackLabels {
    private val sdhRegex = Regex("""\b(sdh|hi|cc|hearing[ -]impaired|closed captions?)\b""", RegexOption.IGNORE_CASE)
    private val forcedRegex = Regex("""\bforced\b""", RegexOption.IGNORE_CASE)

    fun looksSdh(label: String?): Boolean = label?.let { sdhRegex.containsMatchIn(it) } == true
    fun looksForced(label: String?): Boolean = label?.let { forcedRegex.containsMatchIn(it) } == true

    /** "5.1", "Stereo", "Mono", "7.1" or null when unknown. */
    fun channelLayout(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "Mono"
        count == 2 -> "Stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "${count}ch"
    }

    fun codecName(mimeOrCodec: String?): String? {
        val m = mimeOrCodec?.lowercase() ?: return null
        return when {
            "eac3" in m || "ec-3" in m || "ec3" in m -> "Dolby Digital+"
            "ac3" in m || "ac-3" in m -> "Dolby Digital"
            "truehd" in m -> "TrueHD"
            "dts" in m -> "DTS"
            "opus" in m -> "Opus"
            "mp4a" in m || "aac" in m -> "AAC"
            "mpeg" in m || "mp3" in m -> "MP3"
            "vorbis" in m -> "Vorbis"
            "flac" in m -> "FLAC"
            else -> null
        }
    }

    /** Audio: "English - 5.1 - Dolby Digital (Default)". Duplicates of the same description get " 2", " 3" suffixes. */
    fun audioLabels(tracks: List<TrackInfo>): Map<String, String> {
        val base = tracks.associate { t ->
            val parts = buildList {
                add(Languages.displayName(t.language) ?: t.rawLabel?.takeIf { it.isNotBlank() } ?: "Unknown")
                channelLayout(t.channelCount)?.let { add(it) }
                codecName(t.codec)?.let { add(it) }
            }
            var s = parts.joinToString(" · ")
            val raw = t.rawLabel?.takeIf { it.isNotBlank() && !s.contains(it, ignoreCase = true) }
            if (raw != null && Languages.displayName(t.language) != null) s += " ($raw)"
            t.key to s
        }
        return numberDuplicates(tracks, base)
    }

    /**
     * Subtitles: "English", "English - SDH", "English - Forced"; external ones add " - Source".
     * Duplicate descriptions in the same language get a numeric suffix ("English 2").
     */
    fun subtitleLabels(tracks: List<TrackInfo>): Map<String, String> {
        val base = tracks.associate { t ->
            val lang = Languages.displayName(t.language)
                ?: t.rawLabel?.takeIf { it.isNotBlank() && !looksSdh(it) && !looksForced(it) }
                ?: "Unknown"
            val parts = buildList {
                add(lang)
                if (t.isSdh) add("SDH")
                if (t.isForced) add("Forced")
            }
            var s = parts.joinToString(" · ")
            if (t.isExternal && !t.sourceName.isNullOrBlank()) s += " · ${t.sourceName}"
            t.key to s
        }
        return numberDuplicates(tracks, base)
    }

    private fun numberDuplicates(tracks: List<TrackInfo>, base: Map<String, String>): Map<String, String> {
        val seen = HashMap<String, Int>()
        val total = base.values.groupingBy { it }.eachCount()
        val out = LinkedHashMap<String, String>()
        for (t in tracks) {
            val b = base.getValue(t.key)
            if ((total[b] ?: 0) > 1) {
                val n = (seen[b] ?: 0) + 1
                seen[b] = n
                out[t.key] = "$b $n"
            } else out[t.key] = b
        }
        return out
    }

    /** Groups text tracks for the sheet: embedded first, then external grouped by language name. */
    fun groupSubtitles(tracks: List<TrackInfo>, preferred: List<String>): List<Pair<String, List<TrackInfo>>> {
        val embedded = tracks.filter { !it.isExternal }
        val external = tracks.filter { it.isExternal }
        val out = ArrayList<Pair<String, List<TrackInfo>>>()
        if (embedded.isNotEmpty()) out += "Embedded" to embedded
        external.groupBy { Languages.canonical(it.language) ?: "?" }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<TrackInfo>>>(
                    { val i = Languages.preferenceIndex(it.key, preferred); if (i < 0) Int.MAX_VALUE else i },
                    { Languages.sortKey(it.key) },
                ),
            )
            .forEach { (k, v) -> out += (Languages.displayName(k) ?: "Unknown") to v }
        return out
    }
}
