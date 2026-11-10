package com.liuying.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.liuying.app.domain.SeriesCompletion
import com.liuying.app.domain.WatchedIndex
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.PlaybackRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shared by Today, Browse and Search view models: which movies are watched, and the poster long-press actions (L016). */
class TitleActions(private val c: AppContainer, private val scope: CoroutineScope) {
    /**
     * Titles that count as watched: movies with a watched row, and series the app knows are finished (every
     * episode of their cached episode list is watched). Only series that are already fully watched are looked
     * up, so a row of posters costs nothing.
     */
    val watchedKeys: StateFlow<Set<String>> = combine(c.progress.observeAll(), c.metas.observeChanges()) { rows, _ ->
        val finished = HashSet<String>()
        for ((key, group) in rows.filter { !it.type.equals("movie", true) }.groupBy { it.type to it.titleId }) {
            if (!group.all { it.watched }) continue
            val known = runCatching { c.metas.cached(key.first, key.second) }.getOrNull()
                ?.videos?.count { it.season != null && it.episode != null } ?: continue
            if (SeriesCompletion.isComplete(group, known)) finished += WatchedIndex.key(key.first, key.second)
        }
        WatchedIndex.watchedKeys(rows, finished)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Marks a movie directly; a series is marked through its episode list (cached, or fetched once), which is
     * also what makes its poster show "Seen" and lets hide-watched remove it.
     */
    fun toggleWatched(preview: MetaPreview, fallbackType: String) {
        val type = preview.type.ifBlank { fallbackType }
        val key = WatchedIndex.key(type, preview.id)
        val watched = key in watchedKeys.value
        scope.launch {
            if (type.equals("movie", true)) {
                c.progress.setWatched(PlaybackRef.movie(type, preview.id), !watched)
                return@launch
            }
            val meta = runCatching { c.metas.cached(type, preview.id) }.getOrNull()
                ?: runCatching { c.metas.getOrFetch(type, preview.id) }.getOrNull()
            val episodes = meta?.videos?.filter { it.season != null && it.episode != null }.orEmpty()
            if (episodes.isEmpty()) {
                // A silent no-op is the worst answer: say why nothing happened.
                message.value = "Could not load the episode list for this series. Open it once, then try again."
                return@launch
            }
            for (v in episodes) {
                c.progress.setWatched(PlaybackRef.episode(type, preview.id, v.id, v.season!!, v.episode!!), !watched)
            }
            message.value = if (watched) "Marked ${episodes.size} episodes unwatched" else "Marked ${episodes.size} episodes watched"
        }
    }

    /** A one-line answer for the poster action; the screens show it and call [clearMessage]. */
    val message = MutableStateFlow<String?>(null)
    fun clearMessage() { message.value = null }
}

/** A poster with a long-press menu: Details, shelf, and Mark watched (a series is marked through its episodes). */
@Composable
fun ActionPoster(
    m: MetaPreview, type: String, saved: Boolean, watched: Boolean,
    onToggleSave: () -> Unit, onToggleWatched: (() -> Unit)?, onOpen: () -> Unit,
    modifier: Modifier = Modifier, width: Dp = 118.dp, fillWidth: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val canMark = onToggleWatched != null
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

/**
 * Shows the poster action's one-line answer (e.g. "Marked 12 episodes watched", or why nothing happened)
 * as a toast, then clears it. Today, Browse and Search each call this once.
 */
@Composable
fun PosterActionToast(actions: TitleActions) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val message by actions.message.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            actions.clearMessage()
        }
    }
}
