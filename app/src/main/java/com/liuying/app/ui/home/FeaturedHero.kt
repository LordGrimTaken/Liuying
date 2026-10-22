package com.liuying.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.ui.common.ArtImage
import com.liuying.app.ui.common.Glyph
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.LocalReduceMotion
import com.liuying.app.ui.common.pressScale
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.OnAccent
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.Vermilion

const val HERO_ROTATE_MS = 8_000L
const val HERO_CROSSFADE_MS = 900

/** Hero height: 62% of the screen on phones, clamped to 440..680dp (the design reference). */
@Composable
fun heroHeight(): Dp {
    val h = LocalConfiguration.current.screenHeightDp
    return (h * 0.62f).coerceIn(440f, 680f).dp
}

/**
 * Full-bleed featured hero (up to five titles), meant to sit under the translucent top bar. Slides crossfade over 900 ms,
 * rotate every 8 s (stopped by touch, drag, the pause control or reduced motion) and change on a horizontal swipe.
 */
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
    val reduce = reduceMotion || LocalReduceMotion.current
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(reduce) }
    var touching by remember { mutableStateOf(false) }
    val safeIndex = index.coerceIn(0, items.lastIndex)
    fun go(i: Int) { index = ((i % items.size) + items.size) % items.size }

    // restart the 8 s timer after every change, so a manual swipe gets a full interval
    LaunchedEffect(safeIndex, paused, touching, items.size, reduce) {
        if (items.size < 2 || paused || touching || reduce) return@LaunchedEffect
        kotlinx.coroutines.delay(HERO_ROTATE_MS)
        go(safeIndex + 1)
    }

    Box(
        modifier.fillMaxWidth().height(heroHeight())
            .semantics { contentDescription = "Featured titles" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    touching = false
                }
            }
            .pointerInput(items.size) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                    onDragEnd = { if (total < -60f) go(safeIndex + 1) else if (total > 60f) go(safeIndex - 1) },
                )
            },
    ) {
        AnimatedContent(
            targetState = safeIndex, modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val ms = if (reduce) 0 else HERO_CROSSFADE_MS
                fadeIn(tween(ms, easing = LinearEasing)) togetherWith fadeOut(tween(ms, easing = LinearEasing))
            },
            label = "hero",
        ) { page ->
            val it = items[page.coerceIn(0, items.lastIndex)]
            HeroSlide(it, page, items.size, fallbackType, saved(it), onPlay = { onPlay(it) }, onInfo = { onInfo(it) }, onToggleSave = { onToggleSave(it) },
                controls = {
                    if (items.size > 1) HeroDots(page, items.size, paused, { paused = !paused }, ::go)
                })
        }
    }
}

@Composable
private fun HeroSlide(
    it: MetaPreview, index: Int, count: Int, fallbackType: String, saved: Boolean,
    onPlay: () -> Unit, onInfo: () -> Unit, onToggleSave: () -> Unit, controls: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ArtImage(it.background ?: it.poster, it.title, Modifier.fillMaxSize())
        // top scrim for the bar, then a strong fade into the page colour behind the text
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = 0.55f), 0.22f to Color.Transparent, 0.4f to Ink.copy(alpha = 0.35f), 0.72f to Ink.copy(alpha = 0.9f), 1f to Ink)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 22.dp, height = 1.dp).background(Gold))
                Text(
                    "FEATURED  ·  ${index + 1} OF $count", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp), color = Gold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                it.title, style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp), color = Paper,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                it.displayYear?.let { y -> Text(y, style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.85f)) }
                TypeChip(typeLabel(it.type.ifBlank { fallbackType }))
                it.genres.take(2).forEachIndexed { i, g ->
                    if (i == 0) Box(Modifier.size(3.dp).clip(CircleShape).background(Paper.copy(alpha = 0.5f)))
                    Text(g, style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.85f), maxLines = 1)
                }
            }
            it.description?.takeIf { d -> d.isNotBlank() }?.let { d ->
                Text(d, style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroAction("Play", GlyphKind.Play, filled = true, onClick = onPlay, modifier = Modifier.weight(1f))
                HeroAction(if (saved) "Listed" else "List", if (saved) GlyphKind.Check else GlyphKind.Plus, onClick = onToggleSave, modifier = Modifier.weight(1f))
                HeroAction("Details", GlyphKind.Info, onClick = onInfo, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { controls() }
                it.imdbRating?.takeIf { r -> r.isNotBlank() }?.let { r -> RatingBadge(r) }
            }
        }
    }
}

/** One of three equal hero buttons: Play is accent-filled, the others translucent. */
@Composable
private fun HeroAction(label: String, glyph: GlyphKind, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(source, 0.96f).height(50.dp).clip(shape)
            .background(if (filled) Vermilion else Ink.copy(alpha = 0.5f), shape)
            .then(if (filled) Modifier else Modifier.border(1.dp, Paper.copy(alpha = 0.28f), shape))
            .semantics { role = Role.Button }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(glyph, tint = if (filled) OnAccent else Paper, size = 20.dp)
        Text(label, color = if (filled) OnAccent else Paper, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun TypeChip(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Text(text.uppercase(), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = Paper,
        modifier = Modifier.border(1.dp, Paper.copy(alpha = 0.4f), shape).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun RatingBadge(rating: String) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.clip(shape).background(Ink.copy(alpha = 0.5f), shape).border(1.dp, Gold, shape).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(GlyphKind.Star, tint = Gold, size = 14.dp)
        Text(rating, color = Gold, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun HeroDots(page: Int, count: Int, paused: Boolean, onTogglePause: () -> Unit, onGo: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = if (paused) "Resume rotation" else "Pause rotation"; role = Role.Button }.clickable(onClick = onTogglePause),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Ink.copy(alpha = 0.5f)).border(1.dp, Paper.copy(alpha = 0.28f), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(if (paused) GlyphKind.Play else GlyphKind.Pause, tint = Paper, size = 16.dp)
            }
        }
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i ->
                val on = i == page
                Box(
                    Modifier.height(32.dp).width(if (on) 30.dp else 22.dp)
                        .semantics { contentDescription = "Show featured title ${i + 1} of $count"; role = Role.Button }
                        .clickable { onGo(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.height(6.dp).width(if (on) 22.dp else 6.dp).clip(RoundedCornerShape(50)).background(if (on) Vermilion else Paper.copy(alpha = 0.35f)))
                }
            }
        }
    }
}

internal fun typeLabel(type: String): String = when (type) {
    "movie" -> "Movie"; "series" -> "Series"; "tv" -> "TV"; "channel" -> "Channel"
    "" -> "Title"
    else -> type.replaceFirstChar { it.uppercase() }
}
