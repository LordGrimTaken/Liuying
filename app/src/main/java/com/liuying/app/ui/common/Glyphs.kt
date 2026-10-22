package com.liuying.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.OnAccent
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.Vermilion

enum class GlyphKind { Play, Pause, Info, Plus, Check, Share, Close, Search, Eye, ChevronDown, ChevronUp, Calendar }

/** Small hand-drawn line icons; there is no icon font dependency. */
@Composable
fun Glyph(kind: GlyphKind, modifier: Modifier = Modifier, tint: Color = Paper, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            GlyphKind.Play -> drawPath(Path().apply { moveTo(w * .3f, h * .18f); lineTo(w * .84f, h * .5f); lineTo(w * .3f, h * .82f); close() }, tint, style = Fill)
            GlyphKind.Pause -> {
                drawRect(tint, Offset(w * .24f, h * .18f), androidx.compose.ui.geometry.Size(w * .2f, h * .64f))
                drawRect(tint, Offset(w * .58f, h * .18f), androidx.compose.ui.geometry.Size(w * .2f, h * .64f))
            }
            GlyphKind.Info -> {
                drawCircle(tint, radius = w * .4f, style = st)
                drawLine(tint, Offset(w * .5f, h * .46f), Offset(w * .5f, h * .7f), sw, StrokeCap.Round)
                drawCircle(tint, radius = sw * .65f, center = Offset(w * .5f, h * .3f))
            }
            GlyphKind.Plus -> {
                drawLine(tint, Offset(w * .5f, h * .2f), Offset(w * .5f, h * .8f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * .2f, h * .5f), Offset(w * .8f, h * .5f), sw, StrokeCap.Round)
            }
            GlyphKind.Check -> drawPath(Path().apply { moveTo(w * .2f, h * .54f); lineTo(w * .42f, h * .74f); lineTo(w * .8f, h * .28f) }, tint, style = st)
            GlyphKind.Share -> {
                val a = Offset(w * .78f, h * .2f); val b = Offset(w * .22f, h * .5f); val c = Offset(w * .78f, h * .8f)
                drawLine(tint, a, b, sw, StrokeCap.Round); drawLine(tint, b, c, sw, StrokeCap.Round)
                listOf(a, b, c).forEach { drawCircle(Ink, radius = w * .12f, center = it); drawCircle(tint, radius = w * .12f, center = it, style = st) }
            }
            GlyphKind.Close -> {
                drawLine(tint, Offset(w * .24f, h * .24f), Offset(w * .76f, h * .76f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * .76f, h * .24f), Offset(w * .24f, h * .76f), sw, StrokeCap.Round)
            }
            GlyphKind.Search -> {
                drawCircle(tint, radius = w * .27f, center = Offset(w * .43f, h * .43f), style = st)
                drawLine(tint, Offset(w * .63f, h * .63f), Offset(w * .86f, h * .86f), sw, StrokeCap.Round)
            }
            GlyphKind.Eye -> {
                drawPath(Path().apply {
                    moveTo(w * .08f, h * .5f); quadraticTo(w * .5f, h * .08f, w * .92f, h * .5f); quadraticTo(w * .5f, h * .92f, w * .08f, h * .5f)
                }, tint, style = st)
                drawCircle(tint, radius = w * .12f, center = Offset(w * .5f, h * .5f))
            }
            GlyphKind.ChevronDown -> drawPath(Path().apply { moveTo(w * .22f, h * .38f); lineTo(w * .5f, h * .66f); lineTo(w * .78f, h * .38f) }, tint, style = st)
            GlyphKind.ChevronUp -> drawPath(Path().apply { moveTo(w * .22f, h * .62f); lineTo(w * .5f, h * .34f); lineTo(w * .78f, h * .62f) }, tint, style = st)
            GlyphKind.Calendar -> {
                drawRoundRect(tint, Offset(w * .14f, h * .2f), androidx.compose.ui.geometry.Size(w * .72f, h * .66f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = st)
                drawLine(tint, Offset(w * .14f, h * .42f), Offset(w * .86f, h * .42f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * .34f, h * .1f), Offset(w * .34f, h * .28f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * .66f, h * .1f), Offset(w * .66f, h * .28f), sw, StrokeCap.Round)
            }
        }
    }
}

/** Round icon button with a 48dp touch target. [filled] paints it in the accent colour. */
@Composable
fun IconCircleButton(
    kind: GlyphKind, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    filled: Boolean = false, tint: Color = Paper, diameter: Dp = 48.dp, onDark: Boolean = false, enabled: Boolean = true,
) {
    val bg = if (filled) Vermilion else if (onDark) Ink.copy(alpha = 0.6f) else InkSurface.copy(alpha = 0f)
    Box(
        modifier.size(diameter).clip(CircleShape).background(bg, CircleShape)
            .border(BorderStroke(if (filled) 0.dp else 1.dp, GoldHairline), CircleShape)
            .semantics { contentDescription = description; role = Role.Button; if (!enabled) disabled() }
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(kind, tint = if (filled) OnAccent else tint, size = diameter * 0.46f) }
}

