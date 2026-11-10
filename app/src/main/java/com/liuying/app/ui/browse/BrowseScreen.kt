package com.liuying.app.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.domain.model.Extras
import com.liuying.app.domain.model.genreOptions
import com.liuying.app.domain.model.genreRequired
import com.liuying.app.domain.model.optionsFor
import com.liuying.app.domain.model.requiredExtras
import com.liuying.app.domain.model.supportsGenre
import com.liuying.app.ui.common.CenterLoading
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.ErrorNote
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.IconCircleButton
import com.liuying.app.ui.common.InkChip
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.PosterCard
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted

@Composable
fun BrowseScreen(catalogKey: String, initialGenre: String?, navigator: Navigator) {
    val vm = containerViewModel(key = "browse:$catalogKey:$initialGenre") { BrowseViewModel(it, catalogKey, initialGenre) }
    com.liuying.app.ui.common.PosterActionToast(vm.actions)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val hideWatched by vm.hideWatched.collectAsStateWithLifecycle()
    val watchedKeys by vm.actions.watchedKeys.collectAsStateWithLifecycle()
    BackHandler { navigator.back() }
    val entry = ui.entry
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.liuying.app.ui.common.BackTextButton(navigator::back, modifier = Modifier.padding(start = 4.dp))
            Text(
                entry?.title ?: "Browse", style = MaterialTheme.typography.titleLarge, color = Paper, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
            )
            if (entry != null) InkTextButton("Refresh", vm::refresh)
        }
        when {
            !ui.resolved -> CenterLoading(Modifier.fillMaxSize())
            entry == null -> EmptyBlock("This catalog is gone", "Its source was removed or turned off.", action = { com.liuying.app.ui.common.InkButton("Go back", navigator::back) })
            else -> {
                Filters(ui, vm)
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    com.liuying.app.ui.common.InkChip("Hide watched", hideWatched, { vm.setHideWatched(!hideWatched) })
                }
                val p = ui.pager
                when {
                    p.items.isEmpty() && (p.loading || !p.firstPageLoaded && p.error == null) -> CenterLoading(Modifier.fillMaxSize(), "Loading")
                    p.items.isEmpty() && p.error != null -> ErrorNote("${entry.source.displayName}: ${p.error}", onRetry = vm::retry)
                    p.items.isEmpty() && p.firstPageLoaded -> EmptyBlock("Nothing here", "This catalog returned no titles${ui.genre?.let { " for $it" }.orEmpty()}.")
                    else -> {
                        val state = rememberLazyGridState()
                        val shownItems = if (hideWatched) com.liuying.app.domain.WatchedIndex.hideWatched(p.items, entry.catalog.type, watchedKeys) else p.items
                        // near the end of what is loaded: ask for the next page (not while an error is showing; the user retries)
                        LaunchedEffect(state, p.items.size, p.error, p.endReached) {
                            snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= p.items.size - 8 }.collect { near ->
                                if (near && p.error == null) vm.loadMore()
                            }
                        }
                        LazyVerticalGrid(
                            state = state, columns = GridCells.Adaptive(104.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = com.liuying.app.ui.nav.DockClearance),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(shownItems, key = { it.id }) { m ->
                                val type = m.type.ifBlank { entry.catalog.type }
                                com.liuying.app.ui.common.ActionPoster(
                                    m, entry.catalog.type, saved = (type + "|" + m.id) in saved, watched = (type + "|" + m.id) in watchedKeys,
                                    onToggleSave = { vm.toggleSave(m) }, onToggleWatched = { vm.toggleWatched(m) },
                                    onOpen = { navigator.openDetails(m, entry.catalog.type, entry.source.id) },
                                    fillWidth = true,
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                                when {
                                    p.error != null -> ErrorNote(p.error, onRetry = vm::retry)
                                    p.loading -> CenterLoading(Modifier.fillMaxWidth().padding(16.dp))
                                    p.endReached -> Text("That is everything this catalog has.", color = PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().padding(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(ui: BrowseUi, vm: BrowseViewModel) {
    val entry = ui.entry ?: return
    val cat = entry.catalog
    if (cat.supportsGenre && cat.genreOptions.isNotEmpty()) {
        val required = cat.genreRequired
        val selected = ui.genre ?: if (required) cat.genreOptions.first() else null
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!required) item(key = "all") { InkChip("All", selected == null, onClick = { vm.selectGenre(null) }) }
            items(com.liuying.app.domain.QaGuards.distinctOptions(cat.genreOptions), key = { it }) { g -> InkChip(g, selected == g, onClick = { vm.selectGenre(g) }) }
        }
    }
    // other extras with a fixed list of options (e.g. "sort": top, new)
    val others = cat.extra.filter { it.name !in setOf(Extras.GENRE, Extras.SEARCH, Extras.SKIP) && it.options.isNotEmpty() }
    for (ex in others) {
        val required = ex.name in cat.requiredExtras
        val selected = ui.more[ex.name] ?: if (required) ex.options.first() else null
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(com.liuying.app.domain.QaGuards.distinctOptions(cat.optionsFor(ex.name)), key = { it }) { o -> InkChip(o, selected == o, onClick = { vm.selectExtra(ex.name, o) }) }
        }
    }
}
