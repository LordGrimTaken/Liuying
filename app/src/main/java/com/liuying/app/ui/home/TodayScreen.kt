package com.liuying.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.repo.HomeRow
import com.liuying.app.data.repo.RowState
import com.liuying.app.domain.ContinueItem
import com.liuying.app.domain.ContinueKind
import com.liuying.app.domain.EpisodeLabels
import com.liuying.app.domain.Ux
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.supportsSkip
import com.liuying.app.ui.common.ArtImage
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.ErrorNote
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.IconCircleButton
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.PosterCard
import com.liuying.app.ui.common.PosterPlaceholder
import com.liuying.app.ui.common.ProgressBar
import com.liuying.app.ui.common.SectionHeader
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.common.shimmer
import com.liuying.app.ui.nav.topBarInset
import com.liuying.app.ui.nav.ChromeSolid
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.nav.Tab
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(navigator: Navigator) {
    val vm = containerViewModel { TodayViewModel(it) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val hasSources by vm.hasSources.collectAsStateWithLifecycle()
    val cw by vm.continueItems.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val watched by vm.actions.watchedKeys.collectAsStateWithLifecycle()

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = rows?.any { it.refreshing } == true, onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    // the hero comes from the first catalog on Today
    val first = rows?.firstOrNull()
    val firstItems = (first?.state as? RowState.Loaded)?.items.orEmpty()
    val hasHero = settings.heroEnabled && first != null && (Ux.pickFeatured(firstItems, 5).isNotEmpty() || first.state == RowState.Loading)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 } }
    // over the hero the top bar is a bare scrim; once scrolled (or without a hero) it is solid
    ChromeSolid(!hasHero || scrolled)
    LazyColumn(
        Modifier.fillMaxSize(), state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = if (hasHero) 0.dp else topBarInset(), bottom = DockClearance),
    ) {
        if (settings.heroEnabled && first != null) {
            val featured = Ux.pickFeatured(firstItems, 5)
            if (featured.isNotEmpty()) {
                item(key = "hero") {
                    FeaturedHero(
                        featured, first.entry.catalog.type, saved = { (it.type.ifBlank { first.entry.catalog.type } + "|" + it.id) in saved },
                        reduceMotion = settings.reduceMotion,
                        onPlay = { navigator.playTitle(it, first.entry.catalog.type, first.entry.source.id) },
                        onInfo = { navigator.openDetails(it, first.entry.catalog.type, first.entry.source.id) },
                        onToggleSave = { vm.toggleSave(it, first.entry.catalog.type) },
                    )
                }
            } else if (first.state == RowState.Loading) item(key = "hero-skeleton") {
                Box(Modifier.fillMaxWidth().height(heroHeight()).background(com.liuying.app.ui.theme.InkSurface).shimmer(com.liuying.app.ui.theme.InkSurface, Paper.copy(alpha = 0.05f)))
            }
        }
        if (hasSources == false && !settings.firstRunGuideDismissed) {
            item(key = "first-run") {
                com.liuying.app.ui.onboarding.FirstRunGuide(onAddSource = { navigator.goTab(Tab.You) }, onSkip = vm::dismissGuide)
            }
        } else if (hasSources == false) {
            item {
                EmptyBlock(
                    "Your scroll is blank",
                    "Liuying shows what your own sources provide. Add a source with its manifest link and your catalogs will appear here.",
                    modifier = Modifier.fillParentMaxHeight(0.8f),
                    action = { InkButton("Add a source", { navigator.goTab(Tab.You) }) },
                )
            }
        }
        val allFailed = hasSources == true && !rows.isNullOrEmpty() && rows.orEmpty().all { it.state is RowState.Failed }
        if (allFailed) {
            item(key = "all-failed") {
                ErrorNote("None of your sources answered. Check your connection, then pull down or tap retry.", onRetry = vm::refresh)
            }
        }
        if (cw.isNotEmpty()) {
            item { SectionHeader("Continue watching") }
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cw, key = { it.key }) { item ->
                        ContinueCard(item, onOpen = { navigator.openTitle(item.type, item.titleId, item.videoId) },
                            onDetails = { navigator.openTitle(item.type, item.titleId) }, onDismiss = { vm.dismiss(item) })
                    }
                }
            }
        }
        if (hasSources == true && rows == null) {
            item { SectionHeader("Loading") ; PlaceholderRow() }
        }
        rows?.let { list ->
            items(list, key = { it.key }) { row -> CatalogRow(row, navigator, saved, { vm.toggleSave(it, row.entry.catalog.type) }, onRetry = { vm.retry(row.key) }, watched = watched, onToggleWatched = { vm.toggleWatched(it, row.entry.catalog.type) }) }
            if (hasSources == true && list.isEmpty()) {
                item { EmptyBlock("No rows to show", "Your sources have no catalogs turned on for Today. They may still be searchable.") }
            }
        }
        if (hasSources == true) item(key = "refresh") {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                InkButton("Surprise me", { vm.surprise()?.let { navigator.openDetails(it.preview, it.type, it.sourceId) } })
                InkButton("Refresh", vm::refresh, filled = false)
            }
        }
    }
    }
}

private val PLURAL = mapOf("movie" to "Movies", "series" to "Series", "tv" to "TV", "channel" to "Channels")

/** "Movies from Some Addon". */
private fun rowSubtitle(row: HomeRow): String {
    val t = row.entry.catalog.type
    return "${PLURAL[t] ?: t.replaceFirstChar { it.uppercase() }} from ${row.entry.source.displayName}"
}

@Composable
private fun PlaceholderRow() {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(5) { PosterPlaceholder() }
    }
}

@Composable
internal fun CatalogRow(
    row: HomeRow, navigator: Navigator, saved: Set<String>, onToggleSave: (MetaPreview) -> Unit, onRetry: () -> Unit, genre: String? = null,
    watched: Set<String> = emptySet(), onToggleWatched: ((MetaPreview) -> Unit)? = null,
) {
    val state = row.state
    if (state is RowState.Loaded && state.items.isEmpty()) return
    SectionHeader(row.entry.title, subtitle = rowSubtitle(row), trailing = {
        if (state is RowState.Loaded && (row.entry.catalog.supportsSkip || state.items.size >= 10)) {
            InkTextButton("See all", { navigator.openBrowse(row.entry.key, genre) })
        }
    })
    Column(Modifier.padding(bottom = 22.dp)) {
        when (state) {
            RowState.Loading -> PlaceholderRow()
            is RowState.Failed -> ErrorNote("${row.entry.source.displayName}: ${state.message}", onRetry = onRetry)
            is RowState.Loaded -> LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.items, key = { it.id }) { m ->
                    val type = m.type.ifBlank { row.entry.catalog.type }
                    com.liuying.app.ui.common.ActionPoster(
                        m, row.entry.catalog.type, saved = (type + "|" + m.id) in saved, watched = (type + "|" + m.id) in watched,
                        onToggleSave = { onToggleSave(m) }, onToggleWatched = onToggleWatched?.let { f -> { f(m) } },
                        onOpen = { navigator.openDetails(m, row.entry.catalog.type, row.entry.source.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(item: ContinueItem, onOpen: () -> Unit, onDetails: () -> Unit, onDismiss: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.width(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.let { (it * 0.72f).coerceAtMost(270f) }.dp).combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).border(BorderStroke(0.5.dp, GoldHairline), shape)) {
            ArtImage(item.thumbnail ?: item.poster, item.title ?: "?", Modifier.fillMaxSize())
            if (item.kind == ContinueKind.UpNext) {
                Text("Up next", style = MaterialTheme.typography.labelMedium, color = com.liuying.app.ui.theme.OnAccent,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Vermilion, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            } else {
                ProgressBar(item.fraction.toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        Text(item.title ?: item.titleId, style = MaterialTheme.typography.labelLarge, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val line = buildString {
            if (item.season != null) append(EpisodeLabels.line(item.season, item.episode, item.episodeTitle))
            if (item.kind == ContinueKind.InProgress) { if (isNotEmpty()) append(" - "); append(EpisodeLabels.remaining(item.remainingMs)) }
        }
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelMedium, color = PaperMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open details") }, onClick = { menu = false; onDetails() })
            DropdownMenuItem(text = { Text("Remove from continue watching") }, onClick = { menu = false; onDismiss() })
        }
    }
}
