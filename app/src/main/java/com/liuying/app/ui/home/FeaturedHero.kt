package com.liuying.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.ui.common.ArtImage
import com.liuying.app.ui.common.Glyph
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.IconCircleButton
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val HERO_ROTATE_MS = 8_000L

/**
 * Rotating featured hero: up to five titles from the first catalog. Rotation pauses while a finger is down, while
 * dragging, after the pause button, and never runs when [reduceMotion] is set.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FeaturedHero(
    items: List<MetaPreview>,
    fallbackType: String,
    saved: (MetaPreview) -> Boolean,
    reduceMotion: Boolean,
    onPlay: (MetaPreview) -> Unit,
    onInfo: (MetaPreview) -> Unit,
    onToggleSave: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(reduceMotion) }
    var touching by remember { mutableStateOf(false) }
    val dragging by pager.interactionSource.collectIsDraggedAsState()

    // restart the 8 s timer after every page change, so a manual swipe gets a full interval
    LaunchedEffect(pager.currentPage, paused, touching, dragging, items.size, reduceMotion) {
        if (items.size < 2 || paused || touching || dragging || reduceMotion) return@LaunchedEffect
        delay(HERO_ROTATE_MS)
        pager.animateScrollToPage((pager.currentPage + 1) % items.size)
    }

    Box(
        modifier.fillMaxWidth().height(470.dp)
            .semantics { contentDescription = "Featured titles" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    touching = false
                }
            },
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val it = items[page]
            HeroSlide(it, page, items.size, fallbackType, saved(it), onPlay = { onPlay(it) }, onInfo = { onInfo(it) }, onToggleSave = { onToggleSave(it) })
        }
        if (items.size > 1) {
            Row(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(
                    if (paused) GlyphKind.Play else GlyphKind.Pause,
                    if (paused) "Resume rotation" else "Pause rotation",
                    { paused = !paused }, onDark = true, diameter = 48.dp,
                )
                Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    items.indices.forEach { i ->
                        val on = i == pager.currentPage
                        Box(
                            Modifier.size(32.dp).clip(CircleShape)
                                .semantics { contentDescription = "Show featured title ${i + 1} of ${items.size}" }
                                .clickable { scope.launch { pager.animateScrollToPage(i) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(if (on) 10.dp else 8.dp).clip(CircleShape).background(if (on) Gold else Color.Transparent)
                                    .border(BorderStroke(1.dp, if (on) Gold else GoldHairline), CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroSlide(
    it: MetaPreview, index: Int, count: Int, fallbackType: String, saved: Boolean,
    onPlay: () -> Unit, onInfo: () -> Unit, onToggleSave: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ArtImage(it.background ?: it.poster, it.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.35f), Color.Transparent, Ink.copy(alpha = 0.92f), Ink))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 62.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 22.dp, height = 1.dp).background(Gold))
                Text(
                    "FEATURED  ·  ${index + 1} OF $count", style = MaterialTheme.typography.labelMedium, color = Gold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                it.title, style = MaterialTheme.typography.headlineMedium, color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            val genres = it.genres.take(3)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                it.imdbRating?.takeIf { r -> r.isNotBlank() }?.let { r -> HeroChip("★ $r", gold = true) }
                it.displayYear?.let { y -> HeroChip(y) }
                HeroChip(typeLabel(it.type.ifBlank { fallbackType }))
                genres.forEach { g -> HeroChip(g) }
            }
            it.description?.takeIf { d -> d.isNotBlank() }?.let { d ->
                Text(d, style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Play", onPlay, Modifier.height(48.dp))
                InkButton("More info", onInfo, Modifier.height(48.dp), filled = false)
                IconCircleButton(
                    if (saved) GlyphKind.Check else GlyphKind.Plus,
                    if (saved) "Remove ${it.title} from shelf" else "Add ${it.title} to shelf",
                    onToggleSave, filled = saved,
                )
            }
        }
    }
}

@Composable
private fun HeroChip(text: String, gold: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = if (gold) Gold else Paper,
        modifier = Modifier.background(Ink.copy(alpha = 0.55f), shape).border(BorderStroke(0.5.dp, if (gold) Gold else GoldHairline), shape).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

internal fun typeLabel(type: String): String = when (type) {
    "movie" -> "Movie"; "series" -> "Series"; "tv" -> "TV"; "channel" -> "Channel"
    "" -> "Title"
    else -> type.replaceFirstChar { it.uppercase() }
}
