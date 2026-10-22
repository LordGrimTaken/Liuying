package com.liuying.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.repo.RowState
import com.liuying.app.ui.common.CenterLoading
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.ErrorNote
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkChip
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.PosterCard
import com.liuying.app.ui.common.ScreenTitle
import com.liuying.app.ui.common.SectionHeader
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.nav.Tab
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted

@Composable
fun SearchScreen(navigator: Navigator) {
    val vm = containerViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val type by vm.typeFilter.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val trimmed = query.trim()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = DockClearance)) {
        item { ScreenTitle("Search") }
        item {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, singleLine = true,
                placeholder = { Text("Movies, series, anything", color = PaperMuted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.commitRecent(); focus.clearFocus() }),
                trailingIcon = { if (query.isNotEmpty()) InkTextButton("Clear", { vm.setQuery("") }) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Gold, unfocusedBorderColor = GoldHairline, cursorColor = Gold,
                    focusedTextColor = Paper, unfocusedTextColor = Paper,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
        val res = results
        when {
            trimmed.length < 2 -> {
                if (recent.isNotEmpty()) {
                    item { SectionHeader("Recent", trailing = { InkTextButton("Clear all", vm::clearRecent) }) }
                    items(recent, key = { it }) { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r, color = Paper, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp).clickable { vm.setQuery(r) })
                            InkTextButton("Remove", { vm.removeRecent(r) }, color = PaperMuted)
                        }
                    }
                } else {
                    item { EmptyBlock("Look for something", "Type at least two letters. Every enabled source that supports search is asked at once.") }
                }
            }
            res == null || (res.totalCatalogs == 0 && res.pending == 0 && !res.done) -> item { CenterLoading(label = "Searching") }
            res.totalCatalogs == 0 -> item {
                EmptyBlock("No source can search", "None of your enabled sources offers search. Add or enable one in You.",
                    action = { InkButton("Open You", { navigator.goTab(Tab.You) }) })
            }
            else -> {
                val types = res.groups.map { it.entry.catalog.type }.distinct()
                if (types.size > 1) {
                    item {
                        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { InkChip("All", type == null, { vm.setType(null) }) }
                            items(types) { t -> InkChip(t.replaceFirstChar { it.uppercase() }, type.equals(t, true), { vm.setType(t) }) }
                        }
                    }
                }
                val shown = res.groups.filter { type == null || it.entry.catalog.type.equals(type, true) }
                for (g in shown) {
                    val st = g.state
                    if (st is RowState.Loaded && st.items.isEmpty()) continue
                    item(key = g.entry.key) {
                        Column {
                            SectionHeader(g.entry.title + "  ·  " + g.entry.source.displayName)
                            when (st) {
                                RowState.Loading -> CenterLoading()
                                is RowState.Failed -> ErrorNote(st.message)
                                is RowState.Loaded -> LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(st.items, key = { it.id }) { m ->
                                        PosterCard(m.title, m.poster, subtitle = m.displayYear) {
                                            vm.commitRecent()
                                            navigator.openDetails(m, g.entry.catalog.type, g.entry.source.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (res.done && !res.hasAnyResults) {
                    val failed = res.groups.count { it.state is RowState.Failed }
                    item {
                        EmptyBlock(
                            if (failed == res.groups.size) "Could not search" else "No results",
                            if (failed == res.groups.size) "Every source failed to answer. Check your connection and try again."
                            else "Nothing matched \"$trimmed\". Try another spelling.",
                        )
                    }
                } else if (!res.done) item { CenterLoading(label = "Waiting for ${res.pending} more") }
            }
        }
    }
}
