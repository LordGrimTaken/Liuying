package com.liuying.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private fun inkScheme(accent: AccentChoice) = darkColorScheme(
    primary = accent.color,
    onPrimary = accent.onColor,
    secondary = Gold,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = InkSurface,
    onSurface = Paper,
    surfaceVariant = InkSurface,
    onSurfaceVariant = PaperMuted,
    outline = GoldHairline,
    outlineVariant = GoldHairline,
)

/** Text size multipliers for the Appearance setting (small / medium / large). */
fun fontScaleFactor(key: String): Float = when (key) { "s" -> 0.9f; "l" -> 1.18f; else -> 1f }

/** Dark-only "Ink & Seal" theme with a switchable accent and text size. */
@Composable
fun LiuyingTheme(accentKey: String = "vermilion", fontScale: String = "m", content: @Composable () -> Unit) {
    val accent = AccentChoice.of(accentKey)
    Accent.choice = accent
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * fontScaleFactor(fontScale))) {
        MaterialTheme(colorScheme = inkScheme(accent), typography = LiuyingTypography, content = content)
    }
}
