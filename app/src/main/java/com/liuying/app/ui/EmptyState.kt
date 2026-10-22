package com.liuying.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.Vermilion

/** Ink-circle (enso) motif: a brushed ring that does not quite close, with a vermilion seal dot. */
@Composable
fun InkCircle(modifier: Modifier = Modifier.size(132.dp)) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(InkSurface, radius = r * 0.92f, center = c)
        drawArc(
            color = Paper.copy(alpha = 0.9f),
            startAngle = -70f, sweepAngle = 320f, useCenter = false,
            topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f),
            size = androidx.compose.ui.geometry.Size(r * 1.56f, r * 1.56f),
            style = Stroke(width = r * 0.11f, cap = StrokeCap.Round),
        )
        drawCircle(GoldHairline, radius = r * 0.98f, center = c, style = Stroke(width = 1.dp.toPx()))
        val flow = Path().apply {
            moveTo(c.x - r * 0.35f, c.y + r * 0.05f)
            cubicTo(c.x - r * 0.1f, c.y - r * 0.2f, c.x + r * 0.1f, c.y + r * 0.3f, c.x + r * 0.35f, c.y - r * 0.05f)
        }
        drawPath(flow, Paper.copy(alpha = 0.55f), style = Stroke(width = r * 0.05f, cap = StrokeCap.Round))
        drawCircle(Vermilion, radius = r * 0.09f, center = Offset(c.x + r * 0.5f, c.y + r * 0.58f))
    }
}

@Composable
fun PlaceholderScreen(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(bottom = DockClearance),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(28.dp))
        InkCircle()
        Spacer(Modifier.height(24.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
