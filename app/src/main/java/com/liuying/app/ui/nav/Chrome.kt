package com.liuying.app.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val TopBarHeight = 58.dp

/** Space a page without full-bleed artwork must leave at the top for the (solid) top bar. */
@Composable
fun topBarInset(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight

/** Whether the top bar is a blurred solid bar (true) or a bare scrim over artwork (false). */
class ChromeState {
    var solid by mutableStateOf(true)
}

val LocalChrome = compositionLocalOf { ChromeState() }

/** Pages that start with artwork call this with "has the user scrolled past the top"; others never need to. */
@Composable
fun ChromeSolid(solid: Boolean) {
    val chrome = LocalChrome.current
    LaunchedEffect(solid) { chrome.solid = solid }
    DisposableEffect(Unit) { onDispose { chrome.solid = true } }
}
