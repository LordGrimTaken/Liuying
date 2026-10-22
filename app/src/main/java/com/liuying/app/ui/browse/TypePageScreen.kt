package com.liuying.app.ui.browse

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.repo.RowState
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.ErrorNote
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.PosterPlaceholder
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.common.motionMs
import com.liuying.app.ui.common.shimmer
import com.liuying.app.ui.home.CatalogRow
import com.liuying.app.ui.home.FeaturedHero
import com.liuying.app.ui.home.heroHeight
import com.liuying.app.ui.nav.ChromeSolid
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.nav.Tab
import com.liuying.app.ui.nav.topBarInset
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion
import com.liuying.app.ui.common.EaseOutSoft

/** Movies (type "movie") and Series (type "series"): hero, scrollable genre tabs, and rows of that type's first catalogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypePageScreen(type: String, navigator: Navigator) {
    val vm = containerViewModel(key = "type:$type") { TypePageViewModel(it, type) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val genre by vm.genre.collectAsStateWithLifecycle()
    val hero by vm.hero.collectAsStateWithLifecycle()
    val hasSources by vm.hasSources.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val label = if (type == "movie") "movies" else "series"
    val heroOn = settings.heroEnabled && hero.isNotEmpty()

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 } }
    ChromeSolid(!heroOn || scrolled)

    PullToRefreshBox(isRefreshing = false, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = if (heroOn) 0.dp else topBarInset(), bottom = DockClearance)) {
            if (heroOn) item(key = "hero") {
                val entry = vm.heroEntry
                FeaturedHero(
                    hero, type, saved = { (it.type.ifBlank { type } + "|" + it.id) in saved }, reduceMotion = settings.reduceMotion,
                    onPlay = { navigator.playTitle(it, type, entry?.source?.id) },
                    onInfo = { navigator.openDetails(it, type, entry?.source?.id) },
                    onToggleSave = { vm.toggleSave(it) },
                )
            }
            when {
                hasSources == false -> item(key = "nosrc") {
                    EmptyBlock("No sources yet", "Add a source and its $label will appear here.", modifier = Modifier.fillParentMaxHeight(0.7f),
                        action = { InkButton("Add a source", { navigator.goTab(Tab.You) }) })
                }
                else -> {
                    if (genres.isNotEmpty()) item(key = "tabs") { GenreTabs(genres, genre, vm::selectGenre) }
                    val list = rows
                    when {
                        list == null -> item(key = "loading") { SkeletonRow() }
                        list.isEmpty() -> item(key = "empty") {
                            EmptyBlock(
                                if (genre != null) "Nothing in $genre" else "No $label catalogs",
                                if (genre != null) "None of your sources list $label for this genre." else "Your sources have no $label catalogs to browse.",
                                action = if (genre != null) ({ InkButton("Show all", { vm.selectGenre(null) }) }) else null,
                            )
                        }
                        else -> {
                            items(list, key = { it.key }) { row ->
                                CatalogRow(row, navigator, saved, { vm.toggleSave(it) }, onRetry = vm::refresh, genre = genre)
                            }
                            if (list.all { it.state is RowState.Loaded && (it.state as RowState.Loaded).items.isEmpty() }) item(key = "allempty") {
                                EmptyBlock("Nothing to show", "These catalogs returned no titles${genre?.let { " for $it" }.orEmpty()}.")
                            }
                            if (list.isNotEmpty() && list.all { it.state is RowState.Failed }) item(key = "allfailed") {
                                ErrorNote("None of your sources answered. Check your connection and try again.", onRetry = vm::refresh)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    val hi = Paper.copy(alpha = 0.07f)
    Column(Modifier.padding(top = 16.dp)) {
        Box(Modifier.padding(horizontal = 16.dp).height(22.dp).fillMaxWidth(0.5f).background(InkSurface).shimmer(InkSurface, hi))
        LazyRow(Modifier.padding(top = 14.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(5) { PosterPlaceholder() } }
    }
}

/** Scrollable tab strip: "All" first, then genres. The active tab is bright and bold with a 3dp accent underline that scales in. */
@Composable
fun GenreTabs(genres: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
            item(key = "__all") { GenreTab("All", selected == null) { onSelect(null) } }
            items(genres, key = { it }) { g -> GenreTab(g, selected == g) { onSelect(g) } }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(GoldHairline.copy(alpha = 0.5f)))
    }
}

@Composable
private fun GenreTab(text: String, active: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (active) 1f else 0f, tween(motionMs(280), easing = EaseOutSoft), label = "underline")
    Column(
        Modifier.semantics { role = Role.Tab; this.selected = active }.clickable(onClick = onClick).padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text, color = if (active) Paper else PaperMuted, maxLines = 1,
            style = TextStyle(fontSize = 17.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Box(Modifier.height(3.dp).fillMaxWidth(0.55f).graphicsLayer { scaleX = scale }.background(Vermilion))
    }
}
