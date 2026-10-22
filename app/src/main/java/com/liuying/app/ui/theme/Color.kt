package com.liuying.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF14110F)
val InkSurface = Color(0xFF1D1916)
val Paper = Color(0xFFF2E9DA)
val VermilionBase = Color(0xFFC8372D)
val Gold = Color(0xFFC9A45C)

val PaperMuted = Paper.copy(alpha = 0.62f)
val GoldHairline = Gold.copy(alpha = 0.45f)

/** The four accent colours of the web player's Appearance setting. */
enum class AccentChoice(val key: String, val label: String, val color: Color, val onColor: Color) {
    Vermilion("vermilion", "Vermilion", VermilionBase, Paper),
    Gold("gold", "Gold", Color(0xFFC9A45C), Ink),
    Jade("jade", "Jade", Color(0xFF2F7D68), Paper),
    Indigo("indigo", "Indigo", Color(0xFF4A5FA8), Paper);

    companion object {
        fun of(key: String?): AccentChoice = entries.firstOrNull { it.key == key } ?: Vermilion
    }
}

/**
 * The live accent. Read inside composition (or drawing) so every user of [Vermilion] recolours when the setting changes,
 * without threading a parameter through each component.
 */
object Accent {
    var choice by mutableStateOf(AccentChoice.Vermilion)
}

/** The accent colour (named for the default; the Appearance setting can swap it). */
val Vermilion: Color get() = Accent.choice.color

/** Readable text/icon colour on top of [Vermilion]. */
val OnAccent: Color get() = Accent.choice.onColor
