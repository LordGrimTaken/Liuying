package com.liuying.app.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.domain.EpisodeLabels
import com.liuying.app.domain.Ux
import com.liuying.app.ui.common.ArtImage
import com.liuying.app.ui.common.CenterLoading
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.SectionHeader
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.nav.Tab
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.OnAccent
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

@Composable
fun CalendarScreen(navigator: Navigator) {
    val vm = containerViewModel { CalendarViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val hideWatched by vm.hideWatched.collectAsStateWithLifecycle()
    val watchedEps by vm.watchedEpisodes.collectAsStateWithLifecycle()
    val agenda = ui.agenda?.let { com.liuying.app.domain.CalendarFilters.filter(it, hideWatched, watchedEps) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = com.liuying.app.ui.nav.topBarInset(), bottom = DockClearance)) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Calendar", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp))
                if (ui.seriesOnShelf > 0) InkTextButton("Refresh", vm::refresh)
            }
            Text("New and recent episodes for the series on your shelf.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
            if (ui.seriesOnShelf > 0) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    com.liuying.app.ui.common.InkChip("Hide watched", hideWatched, { vm.setHideWatched(!hideWatched) })
                }
            }
            if (agenda != null && ui.seriesOnShelf > 0) {
                Text(
                    "${agenda.tracked} of ${agenda.series} series reported release dates.",
                    color = PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        }
        when {
            !ui.loading && ui.seriesOnShelf == 0 -> item {
                EmptyBlock("No series on your shelf", "Save a series and its upcoming episodes will show up here.",
                    action = { InkButton("Browse Today", { navigator.goTab(Tab.Today) }) })
            }
            agenda == null -> item { CenterLoading(label = "Checking release dates") }
            else -> {
                if (agenda.upcoming.isEmpty() && agenda.recent.isEmpty()) {
                    if (ui.loading) item { CenterLoading(label = "Checking release dates") }
                    else item {
                        EmptyBlock(
                            "Nothing scheduled",
                            if (agenda.tracked == 0) "None of your shelf series has episode dates from your sources."
                            else "No episodes aired in the last week or are due soon.",
                        )
                    }
                }
                if (agenda.upcoming.isNotEmpty()) {
                    item { SectionHeader("Upcoming") }
                    dayItems(agenda.upcoming, navigator)
                }
                if (agenda.recent.isNotEmpty()) {
                    item { SectionHeader("Recently aired") }
                    dayItems(agenda.recent, navigator)
                }
                if (ui.loading) item { CenterLoading(label = "Checking more series") }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dayItems(days: List<Ux.AgendaDay>, navigator: Navigator) {
    items(days.size, key = { "d${days[it].epochDay}${days[it].diff}" }) { i -> DayBlock(days[i], navigator) }
}

@Composable
private fun DayBlock(day: Ux.AgendaDay, navigator: Navigator) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val today = day.diff == 0
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.width(52.dp).clip(shape).background(if (today) Vermilion else InkSurface, shape).border(BorderStroke(0.5.dp, if (today) Vermilion else GoldHairline), shape)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(Ux.dayOfMonth(day.epochDay).toString(), style = MaterialTheme.typography.titleLarge, color = if (today) OnAccent else Paper)
            Text(Ux.monthShort(day.epochDay).uppercase(), style = MaterialTheme.typography.labelMedium, color = if (today) OnAccent else PaperMuted)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = if (day.diff in -1..1) "${day.label}  ·  ${Ux.weekdayShort(day.epochDay)}, ${Ux.monthShort(day.epochDay)} ${Ux.dayOfMonth(day.epochDay)}" else day.label
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Gold)
            day.items.forEach { AgendaCard(it, navigator) }
        }
    }
}

@Composable
private fun AgendaCard(item: Ux.AgendaItem, navigator: Navigator) {
    val shape = RoundedCornerShape(10.dp)
    val status = Ux.airStatus(item.diff)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(InkSurface, shape).border(BorderStroke(0.5.dp, GoldHairline), shape)
            .semantics(mergeDescendants = true) { contentDescription = "${item.name}, ${EpisodeLabels.line(item.season, item.episode, item.video.displayTitle)}, $status" }
            .clickable { navigator.openTitle(item.type.ifBlank { "series" }, item.mediaId) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(item.poster, item.name, Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(5.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(EpisodeLabels.line(item.season, item.episode, item.video.displayTitle), style = MaterialTheme.typography.bodyMedium, color = PaperMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(6.dp))
            val shape2 = RoundedCornerShape(4.dp)
            Text(
                status, style = MaterialTheme.typography.labelMedium, color = if (item.diff == 0) OnAccent else Gold,
                modifier = Modifier.background(if (item.diff == 0) Vermilion else InkSurface, shape2).border(BorderStroke(0.5.dp, GoldHairline), shape2).padding(horizontal = 8.dp, vertical = 3.dp),
                textAlign = TextAlign.Start,
            )
        }
    }
}
