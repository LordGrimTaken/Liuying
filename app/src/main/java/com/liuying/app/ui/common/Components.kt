package com.liuying.app.ui.common

import androidx.compose.ui.semantics.clearAndSetSemantics
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.liuying.app.AppContainer
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.OnAccent
import com.liuying.app.ui.theme.Paper
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.remember
import coil3.request.crossfade
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

val PosterShape = RoundedCornerShape(8.dp)

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() })
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, lineHeight = 30.sp), color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = PaperMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** Image with an ink-and-gold monogram fallback while loading or when there is no artwork. */
@Composable
fun ArtImage(url: String?, label: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(InkSurface), contentAlignment = Alignment.Center) {
        Text(
            label.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.headlineSmall, color = Gold.copy(alpha = 0.55f), fontFamily = FontFamily.Serif,
        )
        if (!url.isNullOrBlank()) {
            val fade = motionMs(400)
            val request = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(url)
                .apply { if (fade > 0) crossfade(fade) }.build()
            AsyncImage(model = request, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String,
    poster: String?,
    modifier: Modifier = Modifier,
    width: Dp = 118.dp,
    subtitle: String? = null,
    progress: Float? = null,
    watched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** Non-null shows the quick-save button; true = already on the shelf. */
    saved: Boolean? = null,
    onToggleSave: (() -> Unit)? = null,
    /** Grid cells: take the width the grid gives instead of the fixed [width]. */
    fillWidth: Boolean = false,
    onClick: () -> Unit,
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(width)).pressScale(source, 0.97f)
        .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(PosterShape).border(BorderStroke(0.5.dp, GoldHairline), PosterShape)) {
            ArtImage(poster, title, Modifier.fillMaxSize())
            if (watched) Text("Seen", style = MaterialTheme.typography.labelMedium, color = OnAccent,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Vermilion, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            if (saved != null && onToggleSave != null) {
                QuickSave(saved, title, onToggleSave, Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.labelLarge.forTitle(title), color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = PaperMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Quick save/unsave on a poster. The hit area is 48dp even though the disc is smaller. */
@Composable
fun QuickSave(saved: Boolean, title: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .semantics { contentDescription = if (saved) "Remove $title from shelf" else "Add $title to shelf"; role = Role.Button }
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (saved) Vermilion else Ink.copy(alpha = 0.72f), androidx.compose.foundation.shape.CircleShape)
                .border(BorderStroke(if (saved) 0.dp else 1.dp, GoldHairline), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(if (saved) GlyphKind.Check else GlyphKind.Plus, tint = if (saved) OnAccent else Paper, size = 16.dp) }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).background(Ink.copy(alpha = 0.7f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(Vermilion))
    }
}

@Composable
fun PosterPlaceholder(width: Dp = 118.dp) {
    Column(Modifier.width(width)) {
        val hi = Paper.copy(alpha = 0.07f)
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(PosterShape).background(InkSurface).shimmer(InkSurface, hi))
        Box(Modifier.padding(top = 8.dp).size(width = width * 0.7f, height = 10.dp).clip(RoundedCornerShape(3.dp)).background(InkSurface).shimmer(InkSurface, hi))
    }
}

@Composable
fun CenterLoading(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        if (label != null) Text(label, color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun ErrorNote(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).border(BorderStroke(0.5.dp, GoldHairline), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onRetry != null) InkTextButton("Retry", onRetry)
    }
}

@Composable
fun InkTextButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Gold,
    description: String? = null,
) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp))
            .semantics { role = Role.Button; if (description != null) contentDescription = description }
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // a decorative glyph in the label must not be read out ("single left-pointing angle quotation mark")
        Text(text, color = color, style = MaterialTheme.typography.labelLarge,
            modifier = if (description != null) Modifier.clearAndSetSemantics { } else Modifier)
    }
}

/** The one back affordance (QA #26/#27): chevron glyph, read as "Back". */
@Composable
fun BackTextButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    InkTextButton("‹ Back", onClick, modifier, color = Paper, description = "Back")

@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, filled: Boolean = true) {
    val shape = RoundedCornerShape(10.dp)
    val bg = if (filled) (if (enabled) Vermilion else Vermilion.copy(alpha = 0.35f)) else androidx.compose.ui.graphics.Color.Transparent
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.pressScale(source, 0.97f).clip(shape).background(bg, shape).then(if (filled) Modifier else Modifier.border(BorderStroke(1.dp, GoldHairline), shape))
            .semantics { role = Role.Button }.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (!enabled) PaperMuted else if (filled) OnAccent else Paper, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

@Composable
fun InkChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = if (selected) OnAccent else Paper,
        modifier = modifier.heightIn(min = 40.dp).clip(shape).background(if (selected) Vermilion else InkSurface, shape).border(BorderStroke(0.5.dp, GoldHairline), shape)
            .semantics { role = Role.Button; this.selected = selected }
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Gold,
        modifier = modifier.border(BorderStroke(0.5.dp, GoldHairline), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
fun EmptyBlock(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        com.liuying.app.ui.InkCircle(Modifier.size(84.dp).alpha(0.6f))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Paper, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = PaperMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}
