package com.liuying.app.ui.player.logic

enum class TrackKind { AUDIO, TEXT }

/** Engine-independent description of a selectable track. [key] is opaque to the logic layer. */
data class TrackInfo(
    val key: String,
    val kind: TrackKind,
    val language: String? = null,
    /** Raw label from the container/source (may be null). */
    val rawLabel: String? = null,
    val channelCount: Int = 0,
    val codec: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isSdh: Boolean = false,
    val isExternal: Boolean = false,
    val sourceName: String? = null,
    val sourceOrder: Int = 0,
    val hashMatched: Boolean = false,
)

sealed interface SubtitleChoice {
    data object Off : SubtitleChoice
    data class Track(val key: String) : SubtitleChoice
}
