package com.liuying.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.domain.HistoryDay
import com.liuying.app.domain.HistoryItem
import com.liuying.app.domain.HistoryLogic
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.model.PlaybackRef
import com.liuying.app.ui.common.ArtImage
import com.liuying.app.ui.common.Badge
import com.liuying.app.ui.common.CenterLoading
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.IconCircleButton
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.ProgressBar
import com.liuying.app.ui.common.SectionHeader
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

data class HistoryUi(val loaded: Boolean = false, val days: List<HistoryDay> = emptyList())

/** Titles for progress rows: the shelf first, then cached metadata. Used by the history screen and by export. */
suspend fun AppContainer.titleLookup(rows: List<com.liuying.app.domain.model.ProgressEntry>): Map<Pair<String, String>, com.liuying.app.domain.model.Meta?> {
    val out = HashMap<Pair<String, String>, com.liuying.app.domain.model.Meta?>()
    for (k in rows.map { it.type to it.titleId }.distinct()) out[k] = metas.cached(k.first, k.second)
    return out
}

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val ui: StateFlow<HistoryUi> = kotlinx.coroutines.flow.combine(c.progress.observeAll(), c.library.observeAll()) { p, l -> p to l }
        .transformLatest { (progress, library) ->
            val visible = progress.filter(HistoryLogic::isVisible)
            val metas = c.titleLookup(visible)
            val names = library.associateBy { it.type to it.id }
            val days = HistoryLogic.group(visible, SystemClock.todayEpochDay()) { e ->
                val meta = metas[e.type to e.titleId]
                val lib = names[e.type to e.titleId]
                val title = meta?.name?.takeIf { it.isNotBlank() } ?: lib?.name ?: e.titleId
                val ep = if (e.isEpisode) {
                    val v = meta?.videos?.firstOrNull { it.id == e.videoId }
                    "S${e.season} E${e.episode}" + (v?.displayTitle?.let { " · $it" } ?: "")
                } else null
                Triple(title, ep, meta?.poster ?: lib?.poster)
            }
            emit(HistoryUi(true, days))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    fun setWatched(item: HistoryItem, watched: Boolean) {
        val e = item.entry
        viewModelScope.launch { c.progress.setWatched(PlaybackRef(e.type, e.titleId, e.videoId, e.season, e.episode), watched) }
    }

    /** Takes the row out of history (it is zeroed, not deleted, so the removal syncs). */
    fun remove(item: HistoryItem) = setWatched(item, false)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(navigator: Navigator) {
    val vm = containerViewModel { HistoryViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(top = 8.dp, bottom = DockClearance)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.liuying.app.ui.common.InkTextButton("Back", navigator::back)
                    Text("Watch history", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp).semantics { heading() })
                }
            }
            when {
                !ui.loaded -> item { CenterLoading() }
                ui.days.isEmpty() -> item { EmptyBlock("Nothing watched yet", "Titles you play show up here, newest first. Turn history off in You if you would rather not keep it.") }
                else -> ui.days.forEach { day ->
                    item(key = "d${day.epochDay}") { SectionHeader(day.label) }
                    items(day.items, key = { it.key }) { h ->
                        HistoryRow(
                            h,
                            onOpen = { navigator.openTitle(h.entry.type, h.entry.titleId, if (h.entry.isEpisode) h.entry.videoId else null) },
                            onToggleWatched = { vm.setWatched(h, !h.entry.watched) },
                            onRemove = {
                                vm.remove(h)
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    val r = snackbar.showSnackbar("Removed ${h.title} from history", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                                    // undo puts the same watched flag back; the position was zeroed, which is what a removal means
                                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.setWatched(h, h.entry.watched)
                                }
                            },
                        )
                    }
                }
            }
        }
        androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = DockClearance))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(h: HistoryItem, onOpen: () -> Unit, onToggleWatched: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val e = h.entry
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(if (e.watched) "Mark unwatched" else "Mark watched") { onToggleWatched(); true },
                        CustomAccessibilityAction("Remove from history") { onRemove(); true },
                    )
                }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtImage(h.poster, h.title, Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(5.dp)))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(h.title, color = Paper, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                h.subtitle?.let { Text(it, color = PaperMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(HistoryLogic.statusLine(e), color = PaperMuted, style = MaterialTheme.typography.labelMedium)
                if (!e.watched && e.durationMs > 0) ProgressBar(e.fraction.toFloat(), Modifier.padding(top = 6.dp).width(120.dp))
            }
            if (e.watched) Badge("Seen")
            IconCircleButton(GlyphKind.ChevronDown, "More actions for ${h.title}", { menu = true }, diameter = 48.dp, tint = PaperMuted)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(if (e.watched) "Mark unwatched" else "Mark watched") }, onClick = { menu = false; onToggleWatched() })
            DropdownMenuItem(text = { Text("Remove from history") }, onClick = { menu = false; onRemove() })
        }
    }
}
