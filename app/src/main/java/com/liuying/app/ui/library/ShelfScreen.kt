package com.liuying.app.ui.library

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.liuying.app.data.settings.LibrarySort
import com.liuying.app.data.settings.LibraryView
import com.liuying.app.domain.TypeFilter
import com.liuying.app.ui.common.ArtImage
import com.liuying.app.ui.common.Badge
import com.liuying.app.ui.common.CenterLoading
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkChip
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.PosterCard
import com.liuying.app.ui.common.ProgressBar
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.nav.Tab
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.domain.model.LibraryItem
import kotlinx.coroutines.launch

private fun LibrarySort.label() = when (this) {
    LibrarySort.RecentlyAdded -> "Recently added"
    LibrarySort.TitleAZ -> "Title A-Z"
    LibrarySort.Year -> "Year"
    LibrarySort.LastWatched -> "Last watched"
    LibrarySort.UnwatchedFirst -> "Unwatched first"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(navigator: Navigator) {
    val vm = containerViewModel { ShelfViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(com.liuying.app.domain.ShelfStatus.All) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val entries = state.entries.filter { status == com.liuying.app.domain.ShelfStatus.All || it.status == status }
    val selecting = selected.isNotEmpty()
    fun keyOf(e: ShelfEntry) = e.item.type + "|" + e.item.id
    val open = { e: ShelfEntry -> if (selecting) selected = if (keyOf(e) in selected) selected - keyOf(e) else selected + keyOf(e) else navigator.openTitle(e.item.type, e.item.id) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val removeWithUndo = { item: LibraryItem ->
        vm.remove(item)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Removed ${item.name} from your shelf", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.restore(item)
        }
        Unit
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = com.liuying.app.ui.nav.topBarInset())) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shelf", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp))
            Box {
                InkTextButton("Sort: " + settings.librarySort.label(), { sortMenu = true })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    LibrarySort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label()) }, onClick = { sortMenu = false; vm.setSort(s) }) }
                }
            }
            InkTextButton(if (settings.libraryView == LibraryView.Grid) "List" else "Grid", vm::toggleView)
        }
        if (!state.loaded) { CenterLoading(); return@Column }
        if (state.totalCount == 0) {
            EmptyBlock("Your shelf is empty", "Add movies and series to your shelf with the + button. It stays on this device, and syncs if you sign in.",
                modifier = Modifier.weight(1f), action = { InkButton("Browse titles", { navigator.goTab(Tab.Today) }) })
            return@Column
        }
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it }, singleLine = true,
            placeholder = { Text("Filter your shelf", color = PaperMuted) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = GoldHairline, cursorColor = Gold, focusedTextColor = Paper, unfocusedTextColor = Paper),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TypeFilter.entries.toList()) { f -> InkChip(f.label, filter == f, { vm.filter.value = f }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(com.liuying.app.domain.ShelfStatus.entries.toList()) { st -> InkChip(st.label, status == st, { status = st }) }
            item { InkTextButton("Surprise me", { vm.surprise()?.let { navigator.openTitle(it.type, it.id) } }) }
        }
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", color = Paper, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                InkTextButton("Mark watched", { vm.markWatched(entries.filter { keyOf(it) in selected }.map { it.item }); selected = emptySet() })
                InkTextButton("Remove", { vm.removeAll(entries.filter { keyOf(it) in selected }.map { it.item }); selected = emptySet() })
                InkTextButton("Cancel", { selected = emptySet() }, color = PaperMuted)
            }
        }
        if (entries.isEmpty()) {
            EmptyBlock("Nothing here", "No saved titles match this filter.")
            return@Column
        }
        if (settings.libraryView == LibraryView.Grid) {
            LazyVerticalGrid(
                GridCells.Adaptive(90.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = DockClearance),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(entries, key = { it.item.type + it.item.id }) { e ->
                    ShelfCell(e, navigator, onRemove = { removeWithUndo(e.item) }, onSelect = { selected = selected + keyOf(e) }) { menu, openMenu ->
                        PosterCard(e.item.name, e.item.poster, fillWidth = true, subtitle = e.item.year, progress = e.progress, watched = e.watched || keyOf(e) in selected,
                            onLongClick = openMenu) { open(e) }
                        menu()
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = DockClearance)) {
                items(entries, key = { it.item.type + it.item.id }) { e ->
                    ShelfCell(e, navigator, onRemove = { removeWithUndo(e.item) }, onSelect = { selected = selected + keyOf(e) }) { menu, openMenu ->
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClick = { open(e) }, onLongClick = openMenu)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtImage(e.item.poster, e.item.name, Modifier.width(54.dp).height(80.dp).clip(RoundedCornerShape(5.dp)))
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(e.item.name, color = Paper, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(e.item.type.replaceFirstChar { it.uppercase() }, e.item.year).joinToString("  ·  "), color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
                                if (e.progress != null) ProgressBar(e.progress, Modifier.padding(top = 6.dp).width(120.dp))
                            }
                            if (keyOf(e) in selected) Badge("Selected") else if (e.watched) Badge("Seen")
                        }
                        menu()
                    }
                }
            }
        }
    }
    androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = DockClearance))
    }
}

@Composable
private fun ShelfCell(
    e: ShelfEntry, navigator: Navigator, onRemove: () -> Unit, onSelect: () -> Unit,
    content: @Composable (menu: @Composable () -> Unit, openMenu: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        content({
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("Open details") }, onClick = { open = false; navigator.openTitle(e.item.type, e.item.id) })
                DropdownMenuItem(text = { Text("Select") }, onClick = { open = false; onSelect() })
                DropdownMenuItem(text = { Text("Remove from shelf") }, onClick = { open = false; onRemove() })
            }
        }, { open = true })
    }
}
