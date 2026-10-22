package com.liuying.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liuying.app.AppContainer
import com.liuying.app.domain.WatchedIndex
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.PlaybackRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shared by Today, Browse and Search view models: which movies are watched, and the poster long-press actions (L016). */
class TitleActions(private val c: AppContainer, private val scope: CoroutineScope) {
    val watchedKeys: StateFlow<Set<String>> =
        c.progress.observeAll().map { WatchedIndex.watchedKeys(it) }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleWatched(preview: MetaPreview, fallbackType: String) {
        val type = preview.type.ifBlank { fallbackType }
        if (type != "movie") return // a series is watched episode by episode
        scope.launch {
            val now = WatchedIndex.key(type, preview.id) in watchedKeys.value
            c.progress.setWatched(PlaybackRef.movie(type, preview.id), !now)
        }
    }
}

/** A poster with a long-press menu: Details, shelf, and Mark watched (movies). Talkback gets the same as custom actions. */
@Composable
fun ActionPoster(
    m: MetaPreview, type: String, saved: Boolean, watched: Boolean,
    onToggleSave: () -> Unit, onToggleWatched: (() -> Unit)?, onOpen: () -> Unit,
    modifier: Modifier = Modifier, width: Dp = 118.dp, fillWidth: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val canMark = onToggleWatched != null && m.type.ifBlank { type } == "movie"
    Box(modifier.semantics {
        customActions = buildList {
            add(CustomAccessibilityAction(if (saved) "Remove from shelf" else "Add to shelf") { onToggleSave(); true })
            if (canMark) add(CustomAccessibilityAction(if (watched) "Mark unwatched" else "Mark watched") { onToggleWatched?.invoke(); true })
        }
    }) {
        PosterCard(
            m.title, m.poster, width = width, fillWidth = fillWidth, subtitle = m.displayYear, watched = watched,
            saved = saved, onToggleSave = onToggleSave,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            onClick = onOpen,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text(if (saved) "Remove from shelf" else "Add to shelf") }, onClick = { menu = false; onToggleSave() })
            if (canMark) DropdownMenuItem(text = { Text(if (watched) "Mark unwatched" else "Mark watched") }, onClick = { menu = false; onToggleWatched?.invoke() })
        }
    }
}

/** L020: a title containing Han, kana or hangul is set in the system serif with the matching locale, so glyph variants are right. */
fun androidx.compose.ui.text.TextStyle.forTitle(text: String): androidx.compose.ui.text.TextStyle {
    val tag = com.liuying.app.domain.CjkText.localeTag(text) ?: return this
    return copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, localeList = androidx.compose.ui.text.intl.LocaleList(tag))
}
