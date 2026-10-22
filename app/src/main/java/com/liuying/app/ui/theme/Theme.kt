package com.liuying.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val InkScheme = darkColorScheme(
    primary = Vermilion,
    onPrimary = Paper,
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

/** Dark-only "Ink & Seal" theme. */
@Composable
fun LiuyingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InkScheme, typography = LiuyingTypography, content = content)
}
