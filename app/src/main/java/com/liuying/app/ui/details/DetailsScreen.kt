package com.liuying.app.ui.details

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.liuying.app.domain.Ux
import com.liuying.app.ui.common.LocalContainer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.liuying.app.ui.common.Glyph
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.IconCircleButton
import com.liuying.app.ui.theme.OnAccent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.repo.SourceStreamsState
import com.liuying.app.data.repo.StreamItem
import com.liuying.app.data.repo.StreamRepository
import com.liuying.app.data.settings.StreamGrouping
import com.liuying.app.domain.EpisodeLabels
import com.liuying.app.domain.EpisodeState
import com.liuying.app.domain.PlayKind
import com.liuying.app.ui.common.ArtImage
import com.liuying.app.ui.common.Badge
import com.liuying.app.ui.common.forTitle
import com.liuying.app.ui.common.CenterLoading
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.ErrorNote
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkChip
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.ProgressBar
import com.liuying.app.ui.common.SectionHeader
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.player.PlayerLauncher
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(type: String, id: String, playVideoId: String?, navigator: Navigator) {
    val container = LocalContainer.current
    val vm = androidx.lifecycle.viewmodel.compose.viewModel<DetailsViewModel>(
        key = "details:$type:$id",
        factory = viewModelFactory {
            initializer { DetailsViewModel(container, type, id, playVideoId, createSavedStateHandle()) }
        },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val ask by vm.ask.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.eventFlow.collect { e ->
            when (e) {
                is DetailsEvent.Launch -> PlayerLauncher.start(context, e.request)
                is DetailsEvent.OpenUrl -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show() }
                is DetailsEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val meta = ui.meta
    val load = ui.load
    Box(Modifier.fillMaxSize()) {
        when {
            load == null || (meta == null && load.loading) -> {
                Column(Modifier.fillMaxSize().statusBarsPadding()) { BackBar(navigator); CenterLoading(label = "Opening") }
            }
            meta == null -> {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    BackBar(navigator)
                    EmptyBlock("Could not open this title", "No source had details for it. Check your connection or your sources.",
                        action = { InkButton("Try again", vm::reload) })
                }
            }
            else -> DetailsBody(vm, ui, navigator)
        }
    }

    sheet?.let { sh ->
        ModalBottomSheet(onDismissRequest = vm::dismissSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink, contentColor = Paper) {
            StreamsSheetContent(sh, ui, vm)
        }
    }
    ask?.let { a ->
        AlertDialog(
            onDismissRequest = vm::cancelAsk, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
            title = { Text("Resume?") },
            text = { Text("You stopped at ${com.liuying.app.ui.player.logic.TimeFormat.clock(a.positionMs)}.") },
            confirmButton = { InkTextButton("Resume", { vm.resumeChoice(true) }) },
            dismissButton = { InkTextButton("Start over", { vm.resumeChoice(false) }, color = PaperMuted) },
        )
    }
}

@Composable
private fun BackBar(navigator: Navigator, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        com.liuying.app.ui.common.BackTextButton(navigator::back)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DetailsBody(vm: DetailsViewModel, ui: DetailsUi, navigator: Navigator) {
    val meta = ui.meta!!
    val load = ui.load!!
    val watchedMovie = ui.movieProgress?.watched == true
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                ArtImage(meta.background ?: meta.poster, meta.title, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.55f), Color.Transparent, Ink))))
                Column(Modifier.statusBarsPadding()) { BackBar(navigator) }
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                    ArtImage(meta.poster, meta.title, Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).border(BorderStroke(0.5.dp, GoldHairline), RoundedCornerShape(6.dp)))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(meta.title, style = MaterialTheme.typography.headlineSmall.forTitle(meta.title), color = Paper, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val line = listOfNotNull(meta.displayYear, meta.runtime, meta.imdbRating?.let { "★ $it" }).joinToString("   ")
                        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium, color = PaperMuted)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                if (load.loading) Text("Checking sources…", color = PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
                if (load.allFailed) ErrorNote("Your sources could not be reached. Showing saved details.", Modifier.padding(bottom = 8.dp).then(Modifier), onRetry = vm::reload)
                val context = LocalContext.current
                val releasedEps = ui.states.filter { it.released && !it.isSpecial }
                val allWatched = if (ui.isSeries) releasedEps.isNotEmpty() && releasedEps.all { it.watched } else watchedMovie
                var confirmAll by remember { mutableStateOf(false) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InkButton(primaryLabel(ui), vm::playPrimary)
                    InkButton(if (ui.inLibrary) "Saved" else "Save", vm::toggleLibrary, filled = false)
                    if (!ui.isSeries || releasedEps.isNotEmpty()) {
                        InkButton(
                            if (allWatched) "Watched" else "Mark watched",
                            {
                                if (!ui.isSeries) vm.setMovieWatched(!allWatched)
                                else if (!allWatched && releasedEps.size > 3) confirmAll = true
                                else vm.setEpisodesWatched(releasedEps, !allWatched)
                            },
                            filled = false,
                            modifier = Modifier.semantics { stateDescription = if (allWatched) "Watched, tap to mark unwatched" else "Not watched" },
                        )
                    }
                    IconCircleButton(GlyphKind.Share, "Share link to ${meta.title}", {
                        val url = Ux.shareUrl(SHARE_ORIGIN, meta.type.ifBlank { vm.type }, vm.id)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, meta.title).putExtra(Intent.EXTRA_TEXT, "${meta.title}\n$url")
                        runCatching { context.startActivity(Intent.createChooser(send, "Share ${meta.title}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { Toast.makeText(context, "Nothing to share with.", Toast.LENGTH_SHORT).show() }
                    })
                }
                if (confirmAll) AlertDialog(
                    onDismissRequest = { confirmAll = false }, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
                    title = { Text("Mark as watched?") }, text = { Text("Mark all ${releasedEps.size} released episodes as watched?") },
                    confirmButton = { InkTextButton("Mark watched", { confirmAll = false; vm.setEpisodesWatched(releasedEps, true) }) },
                    dismissButton = { InkTextButton("Cancel", { confirmAll = false }, color = PaperMuted) },
                )
                val mp = ui.movieProgress
                if (!ui.isSeries && mp != null && mp.fraction > 0 && !mp.watched) {
                    ProgressBar(mp.fraction.toFloat(), Modifier.padding(top = 10.dp).fillMaxWidth())
                    Text(EpisodeLabels.remaining(mp.remainingMs), color = PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
                if (meta.allGenres.isNotEmpty()) {
                    Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        meta.allGenres.take(8).forEach { Badge(it) }
                    }
                }
                meta.description?.takeIf { it.isNotBlank() }?.let { d ->
                    var expanded by remember(d) { mutableStateOf(false) }
                    Text(d, style = MaterialTheme.typography.bodyLarge, color = Paper.copy(alpha = 0.88f), maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp).clickable { expanded = !expanded })
                }
                PeopleLine("Cast", meta.cast.take(8))
                PeopleLine("Director", meta.allDirectors.take(3))
                PeopleLine("Writer", meta.allWriters.take(3))
                if (load.errors.isNotEmpty() && !load.loading) {
                    Text(load.errors.joinToString("  ") { "${it.sourceName}: ${it.message}" }, style = MaterialTheme.typography.labelMedium, color = PaperMuted, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        if (ui.isSeries) {
            item { SectionHeader("Episodes") }
            item {
                val seasonStates = ui.states.filter { it.season == ui.season }
                val releasedSeason = seasonStates.filter { it.released }
                val seasonAllWatched = releasedSeason.isNotEmpty() && releasedSeason.all { it.watched }
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        val shape = RoundedCornerShape(10.dp)
                        Row(
                            Modifier.heightIn(min = 48.dp).clip(shape).border(BorderStroke(1.dp, GoldHairline), shape)
                                .semantics { contentDescription = "Season, ${seasonLabel(ui.season)}. Tap to choose another season."; role = Role.DropdownList }
                                .clickable(enabled = ui.seasons.size > 1) { menu = true }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(seasonLabel(ui.season), color = Paper, style = MaterialTheme.typography.labelLarge)
                            if (ui.seasons.size > 1) { Spacer(Modifier.width(6.dp)); Glyph(GlyphKind.ChevronDown, tint = Gold, size = 18.dp) }
                        }
                        DropdownMenu(menu, { menu = false }) {
                            ui.seasons.forEach { sn ->
                                val st = ui.states.filter { it.season == sn }
                                DropdownMenuItem(
                                    text = { Text("${seasonLabel(sn)}   ${st.count { it.watched }}/${st.size}") },
                                    onClick = { menu = false; vm.selectSeason(sn) },
                                )
                            }
                        }
                    }
                    Text("${seasonStates.count { it.watched }}/${seasonStates.size} watched", color = PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    if (releasedSeason.isNotEmpty()) InkTextButton(if (seasonAllWatched) "Unmark season" else "Mark season", { vm.setEpisodesWatched(releasedSeason, !seasonAllWatched) })
                }
            }
            val nextId = ui.playTarget?.episode?.takeIf { !it.watched }?.videoId
            items(ui.episodes, key = { "${it.season}:${it.episode}:${it.videoId}" }) { e -> EpisodeRow(e, isNext = e.videoId == nextId, onPlay = { vm.playEpisode(e) }, onToggleWatched = { vm.setEpisodeWatched(e, !e.watched) }) }
        }
    }
}

@Composable
private fun PeopleLine(label: String, names: List<String>) {
    if (names.isEmpty()) return
    Row(Modifier.padding(top = 10.dp)) {
        Text(label, color = Gold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(68.dp))
        Text(names.joinToString(", "), color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private const val SHARE_ORIGIN = "https://www.liuyingapp.com"

private fun seasonLabel(s: Int?): String = when (s) { null -> "Season"; 0 -> "Specials"; else -> "Season $s" }

private fun primaryLabel(ui: DetailsUi): String {
    val t = ui.playTarget
    if (t?.episode != null) {
        val code = EpisodeLabels.code(t.episode.season, t.episode.episode)
        return when (t.kind) {
            PlayKind.Resume -> "Resume $code"
            PlayKind.Next, PlayKind.Start -> "Play $code"
            PlayKind.WatchAgain -> "Watch again"
        }
    }
    val mp = ui.movieProgress
    return when {
        mp != null && mp.watched && mp.positionMs == 0L -> "Watch again"
        mp != null && mp.positionMs > 0 -> "Resume"
        else -> "Play"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(e: EpisodeState, isNext: Boolean, onPlay: () -> Unit, onToggleWatched: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                // QA #42: one readable description per episode row
                contentDescription = listOfNotNull("Episode ${e.episode}", e.video.displayTitle, when { !e.released -> "not released yet"; e.watched -> "watched"; e.inProgress -> "in progress"; else -> null }).joinToString(", ")
            }.combinedClickable(onClick = onPlay, onLongClick = { menu = true }, onLongClickLabel = if (e.watched) "Mark as unwatched" else "Mark as watched").padding(horizontal = 20.dp, vertical = 8.dp).alpha(if (e.released) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).alpha(if (e.watched) 0.5f else 1f)) {
                ArtImage(e.video.thumbnail, "${e.episode}", Modifier.fillMaxSize())
                if (e.fraction > 0 && !e.watched) ProgressBar(e.fraction.toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                if (isNext) Text("Up next", style = MaterialTheme.typography.labelMedium, color = OnAccent, modifier = Modifier.padding(bottom = 3.dp).background(Vermilion, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                Text("${e.episode}. ${e.video.displayTitle ?: "Episode ${e.episode}"}", color = Paper, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    !e.released -> "Not released yet"
                    e.watched -> "Watched"
                    e.inProgress -> EpisodeLabels.remaining((e.durationMs - e.positionMs).coerceAtLeast(0))
                    else -> e.video.released?.take(10)
                }
                if (sub != null) Text(sub, color = if (e.watched) Gold else PaperMuted, style = MaterialTheme.typography.labelMedium)
                e.video.displayOverview?.let { Text(it, color = PaperMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(if (e.watched) "Mark unwatched" else "Mark watched") }, onClick = { menu = false; onToggleWatched() })
        }
    }
}

// ---------------------------------------------------------------------------------------- streams

private const val TOP_STREAMS = 5

private fun usability(i: StreamItem): Int = when {
    !i.enabled -> 2
    i.kind == com.liuying.app.domain.StreamKind.Direct -> 0
    else -> 1
}

@Composable
private fun StreamsSheetContent(sh: StreamsSheet, ui: DetailsUi, vm: DetailsViewModel) {
    val s = ui.settings
    val r = sh.result
    var sort by remember { mutableStateOf<Ux.Sort?>(null) }
    // which lists are expanded past the first five: "all" for the flat list, "g<sourceId>" for a source group
    var expanded by remember { mutableStateOf(setOf<String>()) }
    fun toggle(k: String) { expanded = if (k in expanded) expanded - k else expanded + k }
    // L065: quality and language chips
    var qFilter by remember { mutableStateOf<String?>(null) }
    var lFilter by remember { mutableStateOf<String?>(null) }
    fun sorted0(l: List<StreamItem>): List<StreamItem> = sort?.let { so ->
        Ux.sortStreams(l, so, ::usability, { it.quality.resolution?.height ?: 0 }, { it.quality.sizeBytes ?: 0L }, { it.source.displayName })
    } ?: StreamRepository.sort(l, s.streamSort)
    fun sorted(l0: List<StreamItem>): List<StreamItem> = com.liuying.app.domain.StreamFilters.apply(l0, qFilter, lFilter).let { l -> sorted0(l) }

    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Choose a stream", style = MaterialTheme.typography.titleLarge, color = Paper)
                val sub = listOfNotNull(ui.meta?.title, sh.target.label.takeIf { it.isNotBlank() }).joinToString("  ·  ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = PaperMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (sh.autoPending) item { CenterLoading(label = "Picking the best stream…") }
        if (r == null) { item { CenterLoading(label = "Asking your sources") }; return@LazyColumn }
        if (r.groups.isEmpty()) {
            item { EmptyBlock("No sources for this", "None of your enabled sources offers streams for this title. Add or enable one in You.") }
            return@LazyColumn
        }
        if (r.isEmpty) {
            item {
                EmptyBlock(
                    if (r.allFailed) "Sources did not answer" else "No streams found",
                    if (r.allFailed) "Every source failed. Check your connection and try again." else "Your sources have nothing for this yet.",
                    action = { InkButton("Try again", vm::refreshStreams) },
                )
            }
        }
        if (r.items.size > 1) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SORT BY", style = MaterialTheme.typography.labelMedium, color = PaperMuted)
                    Ux.SortKey.entries.forEach { k ->
                        val on = sort?.key == k
                        val arrow = if (on) (if (sort!!.desc) " ↓" else " ↑") else ""
                        Box(Modifier.semantics {
                            contentDescription = "Sort by ${k.label.lowercase()}" + if (on) (if (sort!!.desc) ", descending" else ", ascending") else ""
                        }) { InkChip(k.label + arrow, on, { sort = Ux.nextSort(sort, k); expanded = emptySet() }) }
                    }
                    if (sort != null) InkTextButton("Reset", { sort = null; expanded = emptySet() })
                }
            }
        }
        val qOpts = com.liuying.app.domain.StreamFilters.qualityOptions(r.items)
        val lOpts = com.liuying.app.domain.StreamFilters.languageOptions(r.items)
        if (r.items.size > 1 && (qOpts.size > 1 || lOpts.size > 1)) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SHOW", style = MaterialTheme.typography.labelMedium, color = PaperMuted)
                    if (qOpts.size > 1) qOpts.forEach { q -> InkChip(q, qFilter == q, { qFilter = if (qFilter == q) null else q; expanded = emptySet() }) }
                    if (lOpts.size > 1) lOpts.forEach { l -> InkChip(l, lFilter == l, { lFilter = if (lFilter == l) null else l; expanded = emptySet() }) }
                    if (qFilter != null || lFilter != null) InkTextButton("Clear", { qFilter = null; lFilter = null; expanded = emptySet() })
                }
            }
        }
        val flat = s.streamGrouping == StreamGrouping.FlatSorted || sort != null
        if (flat) {
            val all = sorted(r.items.filter { !s.hideNonPlayableStreams || it.enabled })
            val split = Ux.splitTop(all, TOP_STREAMS)
            val open = "all" in expanded
            val shown = if (open) all else split.top
            items(shown, key = { it.key }) { StreamRow(it, s.showRawStreamDescription, showSource = true) { vm.onStreamClick(it) } }
            if (split.rest.isNotEmpty()) item(key = "more-all") { ShowMoreRow(open, split.rest.size) { toggle("all") } }
            for (g in r.groups) {
                val st = g.state
                if (st is SourceStreamsState.Loading) item(key = "l${g.source.id}") { SourceLoadingRow(g.source.displayName) }
                if (st is SourceStreamsState.Failed) item(key = "f${g.source.id}") { SourceFailedRow(g.source.displayName, st.message, vm::retryStreams, g.source.isPlugin) }
            }
        } else {
            for (g in r.groups) {
                item(key = "h${g.source.id}") { SectionHeader(g.source.displayName) }
                when (val st = g.state) {
                    SourceStreamsState.Loading -> item(key = "l${g.source.id}") { SourceLoadingRow(g.source.displayName) }
                    is SourceStreamsState.Failed -> item(key = "f${g.source.id}") { SourceFailedRow(g.source.displayName, st.message, vm::retryStreams, g.source.isPlugin) }
                    is SourceStreamsState.Loaded -> {
                        val list = sorted(st.items.filter { !s.hideNonPlayableStreams || it.enabled })
                        if (list.isEmpty()) item(key = "e${g.source.id}") { Text("Nothing from this source.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                        val split = Ux.splitTop(list, TOP_STREAMS)
                        val k = "g${g.source.id}"
                        val open = k in expanded
                        items(if (open) list else split.top, key = { it.key }) { StreamRow(it, s.showRawStreamDescription, showSource = false) { vm.onStreamClick(it) } }
                        if (split.rest.isNotEmpty()) item(key = "more$k") { ShowMoreRow(open, split.rest.size) { toggle(k) } }
                    }
                }
            }
        }
        if (r.done && !r.isEmpty) item { Row(Modifier.padding(horizontal = 12.dp)) { InkTextButton("Refresh streams", vm::refreshStreams, color = PaperMuted) } }
    }
}

@Composable
private fun ShowMoreRow(open: Boolean, hidden: Int, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(if (open) GlyphKind.ChevronUp else GlyphKind.ChevronDown, tint = Gold, size = 18.dp)
        Text(if (open) "Show fewer streams" else "Show $hidden more stream${if (hidden > 1) "s" else ""}", color = Gold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SourceLoadingRow(name: String) {
    Text("Asking $name…", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun SourceFailedRow(name: String, message: String, onRetry: () -> Unit, isPlugin: Boolean = false) {
    // a plugin that failed is a quiet line, not an error banner: the addon streams are what matter
    if (isPlugin) Text("Plugin $name failed: $message", color = PaperMuted, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    else ErrorNote("$name: $message", onRetry = onRetry)
}

@Composable
private fun StreamRow(item: StreamItem, showRaw: Boolean, showSource: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(shape).background(InkSurface).border(BorderStroke(0.5.dp, GoldHairline), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(item.displayName, item.source.displayName.takeIf { showSource }, item.quality.badges.joinToString(" ").ifBlank { null }, item.disabledReason).joinToString(", ")
            }
            .clickable(onClick = onClick).padding(12.dp).alpha(if (item.enabled) 1f else 0.5f),
    ) {
        Text(item.displayName, color = Paper, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val badges = buildList {
            if (item.source.isPlugin) add("plugin")
            if (showSource || item.source.isPlugin) add(item.source.displayName)
            addAll(item.quality.badges)
        }
        if (badges.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { badges.forEach { Badge(it) } }
        if (showRaw) item.detail?.takeIf { it.isNotBlank() }?.let { Text(it, color = PaperMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
        item.disabledReason?.let { Text(it, color = Vermilion, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp)) }
        if (item.kind == com.liuying.app.domain.StreamKind.External) Text("Opens outside Liuying", color = PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}
