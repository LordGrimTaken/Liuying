package com.liuying.app.ui.player

/** One-tap subtitle looks (L070). Each preset sets the whole style; the sliders can still tweak it after. */
enum class SubtitlePreset(val label: String) {
    CLASSIC("Classic"),
    HIGH_CONTRAST("High contrast"),
    YELLOW_ON_BLACK("Yellow on black"),
    LARGE_PRINT("Large print");

    fun apply(s: PlayerSettings): PlayerSettings = when (this) {
        CLASSIC -> s.copy(
            subtitleSizePercent = 100, subtitleBold = false, subtitleColor = 0xFFFFFFFF.toInt(),
            subtitleBackground = SubtitleBackground.NONE, subtitleBackgroundOpacityPercent = 60,
            subtitleEdge = SubtitleEdge.OUTLINE, subtitleEdgeColor = 0xFF000000.toInt(),
        )
        HIGH_CONTRAST -> s.copy(
            subtitleSizePercent = 120, subtitleBold = true, subtitleColor = 0xFFFFFFFF.toInt(),
            subtitleBackground = SubtitleBackground.BOX, subtitleBackgroundOpacityPercent = 100,
            subtitleEdge = SubtitleEdge.NONE, subtitleEdgeColor = 0xFF000000.toInt(),
        )
        YELLOW_ON_BLACK -> s.copy(
            subtitleSizePercent = 120, subtitleBold = true, subtitleColor = 0xFFFFE600.toInt(),
            subtitleBackground = SubtitleBackground.BOX, subtitleBackgroundOpacityPercent = 100,
            subtitleEdge = SubtitleEdge.NONE, subtitleEdgeColor = 0xFF000000.toInt(),
        )
        LARGE_PRINT -> s.copy(
            subtitleSizePercent = 160, subtitleBold = true, subtitleColor = 0xFFFFFFFF.toInt(),
            subtitleBackground = SubtitleBackground.BOX, subtitleBackgroundOpacityPercent = 80,
            subtitleEdge = SubtitleEdge.NONE, subtitleEdgeColor = 0xFF000000.toInt(),
        )
    }

    /** True when [s] has exactly this preset's look (ignoring vertical position). */
    fun matches(s: PlayerSettings): Boolean = apply(s) == s

    companion object {
        fun current(s: PlayerSettings): SubtitlePreset? = entries.firstOrNull { it.matches(s) }
    }
}
