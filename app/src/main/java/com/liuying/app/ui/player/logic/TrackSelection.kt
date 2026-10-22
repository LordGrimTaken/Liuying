package com.liuying.app.ui.player.logic

/** Subtitle mode mirrors com.liuying.app.ui.player.SubtitleMode but is kept independent for testing. */
enum class AutoSubtitleMode { OFF_UNLESS_NEEDED, ALWAYS_ON, FORCED_ONLY }

data class SelectionPrefs(
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>,
    val mode: AutoSubtitleMode = AutoSubtitleMode.OFF_UNLESS_NEEDED,
    val preferSdh: Boolean = false,
    val autoUseExternal: Boolean = true,
)

object TrackSelection {

    /** Best audio track: preferred-language order, then default flag, then container order. Null = leave engine default. */
    fun chooseAudio(audio: List<TrackInfo>, audioLanguages: List<String>): String? {
        if (audio.isEmpty()) return null
        if (audioLanguages.isEmpty()) return null
        val ranked = audio.withIndex()
            .map { (i, t) -> Triple(i, t, Languages.preferenceIndex(t.language, audioLanguages)) }
            .filter { it.third >= 0 }
            .sortedWith(compareBy({ it.third }, { if (it.second.isDefault) 0 else 1 }, { it.first }))
        return ranked.firstOrNull()?.second?.key
    }

    /** Language of the audio that will play (the chosen key, else the default/first track). */
    fun audioLanguage(audio: List<TrackInfo>, selectedKey: String?): String? =
        (audio.firstOrNull { it.key == selectedKey } ?: audio.firstOrNull { it.isDefault } ?: audio.firstOrNull())?.language

    /**
     * Automatic subtitle choice at playback start.
     *
     * - Mode OFF_UNLESS_NEEDED: Off when the audio language is already a preferred subtitle language.
     * - Otherwise rank candidates in a preferred language: full (non-forced) tracks beat forced-only ones,
     *   embedded beat external, non-SDH beat SDH (unless [SelectionPrefs.preferSdh]), hash-matched beat
     *   name-matched, lower source order wins. A forced-only track is chosen only when no full track exists.
     * - FORCED_ONLY: only a forced track in a preferred language (or the audio language) is eligible.
     */
    fun chooseSubtitle(subs: List<TrackInfo>, audioLanguage: String?, prefs: SelectionPrefs): SubtitleChoice {
        val langs = prefs.subtitleLanguages
        if (langs.isEmpty() || subs.isEmpty()) return SubtitleChoice.Off
        val audioIsPreferred = Languages.preferenceIndex(audioLanguage, langs) >= 0

        val candidates = subs.filter { !it.isExternal || prefs.autoUseExternal }
        if (prefs.mode == AutoSubtitleMode.FORCED_ONLY) {
            val forced = candidates.filter {
                it.isForced && (Languages.preferenceIndex(it.language, langs) >= 0 || Languages.same(it.language, audioLanguage))
            }
            return rank(forced, langs, prefs, audioLanguage).firstOrNull()?.let { SubtitleChoice.Track(it.key) } ?: SubtitleChoice.Off
        }
        if (prefs.mode == AutoSubtitleMode.OFF_UNLESS_NEEDED && audioIsPreferred) return SubtitleChoice.Off

        val inPreferred = candidates.filter { Languages.preferenceIndex(it.language, langs) >= 0 }
        val best = rank(inPreferred, langs, prefs, audioLanguage).firstOrNull()
        return best?.let { SubtitleChoice.Track(it.key) } ?: SubtitleChoice.Off
    }

    /** Sorted best-first. Language preference order is the primary key. */
    fun rank(cands: List<TrackInfo>, langs: List<String>, prefs: SelectionPrefs, audioLanguage: String?): List<TrackInfo> =
        cands.withIndex().sortedWith(
            compareBy<IndexedValue<TrackInfo>>(
                { Languages.preferenceIndex(it.value.language, langs).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                { if (it.value.isForced) 1 else 0 },
                { if (it.value.isExternal) 1 else 0 },
                { if (it.value.isSdh == prefs.preferSdh) 0 else 1 },
                { if (it.value.hashMatched) 0 else 1 },
                { it.value.sourceOrder },
                { it.index },
            ),
        ).map { it.value }

    /**
     * After a manual choice the language is remembered for the rest of the series. Returns the subtitle
     * to apply on a new media (episode or stream switch) given a remembered language / off.
     */
    fun applyRemembered(subs: List<TrackInfo>, rememberedLanguage: String?, rememberedOff: Boolean, prefs: SelectionPrefs): SubtitleChoice? {
        if (rememberedOff) return SubtitleChoice.Off
        val lang = rememberedLanguage ?: return null
        val match = rank(subs.filter { Languages.same(it.language, lang) }, listOf(lang), prefs, null).firstOrNull()
        return match?.let { SubtitleChoice.Track(it.key) }
    }
}
