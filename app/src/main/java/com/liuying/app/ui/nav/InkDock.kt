package com.liuying.app.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

/** Floating, rounded bottom bar. Built from plain Compose, no NavigationBar. */
@Composable
fun InkDock(
    current: Tab?,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .shadow(12.dp, shape, clip = false)
            .background(InkSurface, shape)
            .border(BorderStroke(0.5.dp, GoldHairline), shape)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            DockItem(tab, selected = tab == current, onClick = { onSelect(tab) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DockItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint = if (selected) Paper else PaperMuted
    Column(
        modifier = modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TabGlyph(tab, selected, tint)
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = tint)
        // vermilion seal-dot under the active tab
        Canvas(Modifier.padding(top = 3.dp).size(width = 14.dp, height = 2.dp)) {
            if (selected) drawRect(Vermilion, size = Size(size.width, size.height))
        }
    }
}

/** Tiny hand-drawn glyphs; each is a brush-like path. */
@Composable
private fun TabGlyph(tab: Tab, selected: Boolean, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val color = if (selected) Gold else tint
        when (tab) {
            Tab.Today -> {
                drawCircle(color, radius = w * 0.18f, center = Offset(w / 2, h / 2), style = stroke)
                val p = Path().apply {
                    moveTo(w * 0.08f, h * 0.82f); cubicTo(w * 0.35f, h * 0.7f, w * 0.65f, h * 0.9f, w * 0.92f, h * 0.78f)
                }
                drawPath(p, color, style = stroke)
            }
            Tab.Search -> {
                drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
                drawLine(color, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.9f, h * 0.9f), stroke.width, StrokeCap.Round)
            }
            Tab.Shelf -> {
                drawLine(color, Offset(w * 0.15f, h * 0.88f), Offset(w * 0.85f, h * 0.88f), stroke.width, StrokeCap.Round)
                drawRect(color, Offset(w * 0.2f, h * 0.2f), Size(w * 0.14f, h * 0.62f), style = stroke)
                drawRect(color, Offset(w * 0.44f, h * 0.12f), Size(w * 0.14f, h * 0.7f), style = stroke)
                drawLine(color, Offset(w * 0.72f, h * 0.82f), Offset(w * 0.86f, h * 0.24f), stroke.width, StrokeCap.Round)
            }
            Tab.You -> {
                drawCircle(color, radius = w * 0.17f, center = Offset(w / 2, h * 0.32f), style = stroke)
                val p = Path().apply {
                    moveTo(w * 0.15f, h * 0.9f); cubicTo(w * 0.2f, h * 0.55f, w * 0.8f, h * 0.55f, w * 0.85f, h * 0.9f)
                }
                drawPath(p, color, style = stroke)
            }
        }
    }
}
