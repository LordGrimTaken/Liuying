package com.liuying.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/** True when the system animation scale is 0 or Liuying's own Reduce motion setting is on. Every animation checks it. */
val LocalReduceMotion = compositionLocalOf { false }

/** Duration helper: 0 under reduced motion, so state changes simply snap. */
@Composable
fun motionMs(ms: Int): Int = if (LocalReduceMotion.current) 0 else ms

/** The design's easing: cubic-bezier(.2,.7,.3,1). */
val EaseOutSoft = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)

/** Press feedback: scales down to [scale] while pressed (120 ms). */
fun Modifier.pressScale(source: InteractionSource, scale: Float = 0.96f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val s by animateFloatAsState(if (pressed && !reduce) scale else 1f, tween(if (reduce) 0 else 120), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Left-to-right 1.4 s shimmer for skeleton blocks; a static block under reduced motion. */
fun Modifier.shimmer(base: Color, highlight: Color): Modifier = composed {
    val reduce = LocalReduceMotion.current
    if (reduce) return@composed this
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x")
    drawWithContent {
        drawContent()
        val w = size.width.coerceAtLeast(1f)
        val start = x * w
        drawRect(Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent), Offset(start - w * 0.4f, 0f), Offset(start + w * 0.4f, 0f)))
    }
}
