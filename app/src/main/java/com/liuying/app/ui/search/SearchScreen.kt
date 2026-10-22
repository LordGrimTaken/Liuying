package com.liuying.app.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.repo.RowState
import com.liuying.app.domain.Ux
import com.liuying.app.ui.common.CenterLoading
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.ErrorNote
import com.liuying.app.ui.common.Glyph
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.IconCircleButton
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkChip
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.PosterCard
import com.liuying.app.ui.common.SectionHeader
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.nav.Tab
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted

private val Plural = mapOf("movie" to "Movies", "series" to "Series", "tv" to "TV", "channel" to "Channels")

/** Full-screen search overlay: chips for All / Movies / Series, recent searches when empty, suggestions while typing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(navigator: Navigator) {
    val vm = containerViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val type by vm.typeFilter.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val trimmed = query.trim()
    val close = { vm.commitRecent(); focus.clearFocus(); navigator.back() }

    BackHandler(onBack = close)
    LaunchedEffect(Unit) { requester.requestFocus(); keyboard?.show() }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, singleLine = true,
                placeholder = { Text("Search titles across your sources", color = PaperMuted) },
                leadingIcon = { Glyph(GlyphKind.Search, tint = Gold) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.commitRecent(); keyboard?.hide() }),
                trailingIcon = {
                    if (query.isNotEmpty()) IconCircleButton(GlyphKind.Close, "Clear search", { vm.setQuery("") }, diameter = 40.dp, tint = PaperMuted)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Gold, unfocusedBorderColor = GoldHairline, cursorColor = Gold,
                    focusedTextColor = Paper, unfocusedTextColor = Paper,
                ),
                modifier = Modifier.weight(1f).focusRequester(requester),
            )
            IconCircleButton(GlyphKind.Close, "Close search", close)
        }

        val res = results
        val types = res?.groups?.map { it.entry.catalog.type }.orEmpty()
        val filters = Ux.typeFilters(types)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filters) { t -> InkChip(Ux.typeFilterLabel(t), type == t, { vm.setType(t) }) }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (suggestions.isNotEmpty()) {
                items(suggestions, key = { "s$it" }) { s ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.setQuery(s); vm.commitRecent(s); keyboard?.hide() }.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Glyph(GlyphKind.Search, tint = PaperMuted, size = 18.dp)
                        Text(s, color = Paper, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                }
            }
            when {
                trimmed.length < 2 -> {
                    if (suggestions.isEmpty()) {
                        if (recent.isNotEmpty()) {
                            item { SectionHeader("Recent searches", trailing = { InkTextButton("Clear all", vm::clearRecent) }) }
                            item {
                                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    recent.forEach { r -> RecentChip(r, onPick = { vm.setQuery(r); vm.commitRecent(r) }, onRemove = { vm.removeRecent(r) }) }
                                }
                            }
                        } else {
                            item { EmptyBlock("Look for something", "Type at least two letters. Results come from every enabled source that supports search.") }
                        }
                    }
                }
                res == null || (res.totalCatalogs == 0 && res.pending == 0 && !res.done) -> item { CenterLoading(label = "Searching") }
                res.totalCatalogs == 0 -> item {
                    EmptyBlock("No source can search", "None of your enabled sources offers search. Add or enable one in You.",
                        action = { InkButton("Open You", { navigator.goTab(Tab.You) }) })
                }
                else -> {
                    val shown = res.groups.filter { Ux.matchesType(it.entry.catalog.type, type) }
                    var any = false
                    for (g in shown) {
                        val st = g.state
                        if (st is RowState.Loaded && st.items.isEmpty()) continue
                        any = true
                        item(key = g.entry.key) {
                            val gt = g.entry.catalog.type
                            Column {
                                SectionHeader(g.entry.title, subtitle = "${Plural[gt] ?: gt.replaceFirstChar { it.uppercase() }} from ${g.entry.source.displayName}")
                                when (st) {
                                    RowState.Loading -> CenterLoading()
                                    is RowState.Failed -> ErrorNote(st.message)
                                    is RowState.Loaded -> LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(st.items, key = { it.id }) { m ->
                                            val mt = m.type.ifBlank { gt }
                                            PosterCard(m.title, m.poster, subtitle = m.displayYear, saved = (mt + "|" + m.id) in saved, onToggleSave = { vm.toggleSave(m, gt) }) {
                                                vm.commitRecent()
                                                navigator.openDetails(m, gt, g.entry.source.id)
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
                                if (failed == res.groups.size) "Could not search" else "No results for \"$trimmed\"",
                                if (failed == res.groups.size) "Every source failed to answer. Check your connection and try again."
                                else "Check the spelling, or add a source that supports search.",
                            )
                        }
                    } else if (res.done && !any && type != "all") {
                        item { Text("No ${Ux.typeFilterLabel(type).lowercase()} results. Try All.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
                    } else if (!res.done) item { CenterLoading(label = "Searching ${res.pending} catalog${if (res.pending == 1) "" else "s"}") }
                }
            }
        }
    }
}

@Composable
private fun RecentChip(text: String, onPick: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(InkSurface, shape).border(BorderStroke(0.5.dp, GoldHairline), shape), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.heightIn(min = 48.dp).clickable(onClick = onPick).padding(start = 14.dp, end = 6.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, color = Paper, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Box(Modifier.size(48.dp).clickable(onClick = onRemove).semantics { contentDescription = "Remove $text from recent searches" }, contentAlignment = Alignment.Center) {
            Glyph(GlyphKind.Close, tint = PaperMuted, size = 14.dp)
        }
    }
}
