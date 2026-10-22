@file:OptIn(UnstableApi::class)

package com.liuying.app.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.liuying.app.ui.player.logic.GestureMath
import com.liuying.app.ui.player.logic.NextEpisodeTiming
import com.liuying.app.ui.player.logic.TimeFormat
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

@Composable
fun PlayerScreen(c: PlayerController, onClose: () -> Unit) {
    val activity = LocalContext.current as PlayerActivity

    BackHandler {
        when {
            c.panelTab != null -> c.panelTab = null
            c.locked -> c.showHud("Controls are locked")
            else -> onClose()
        }
    }
    LaunchedEffect(c.isPlaying, c.videoAspect) { activity.refreshPipParams() }
    LaunchedEffect(c.controlsVisible, c.isPlaying, c.interactionTick, c.panelTab, c.scrubPreviewMs != null) {
        if (c.controlsVisible && c.isPlaying && c.panelTab == null && c.scrubPreviewMs == null) {
            delay(4000)
            c.controlsVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(c)
        if (c.inPip) {
            if (c.phase == UiPhase.LOADING || c.buffering) CenterSpinner()
            return@Box
        }

        if (c.locked) LockLayer(c) else GestureLayer(c)

        // States
        if (c.phase == UiPhase.LOADING) TitleCard(c)
        else if (c.phase == UiPhase.RECONNECTING) ReconnectBanner(c)
        else if (c.buffering && c.phase == UiPhase.READY) CenterSpinner()

        AnimatedVisibility(
            visible = c.controlsVisible && !c.locked && c.phase != UiPhase.ERROR && c.panelTab == null,
            enter = fadeIn(), exit = fadeOut(),
        ) { ControlsOverlay(c, activity) }

        Hud(c)
        NextEpisodeCard(c)

        AnimatedVisibility(
            visible = c.panelTab != null && !c.locked,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) { SidePanel(c) }

        c.error?.let { if (c.phase == UiPhase.ERROR) ErrorOverlay(c, it, onClose) }
    }
}

@Composable
private fun VideoSurface(c: PlayerController) {
    val showControlsPad = c.controlsVisible && !c.inPip
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = c.player
            }
        },
        update = { v ->
            v.resizeMode = when (c.aspect) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            v.subtitleView?.let { SubtitleStyleApplier.apply(it, c.settings, showControlsPad) }
        },
    )
}

@Composable
private fun GestureLayer(c: PlayerController) {
    val s = c.settings
    val g = s.gesturesEnabled
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(g, s.doubleTapSeekEnabled, s.longPressSpeedEnabled) {
                val lp = viewConfiguration.longPressTimeoutMillis
                detectTapGestures(
                    onPress = {
                        if (g && s.longPressSpeedEnabled) {
                            val released = withTimeoutOrNull(lp) { tryAwaitRelease() }
                            if (released == null) {
                                c.beginSpeedHold()
                                tryAwaitRelease()
                                c.endSpeedHold()
                            }
                        }
                    },
                    onTap = { c.controlsVisible = !c.controlsVisible; c.poke() },
                    onDoubleTap = if (g && s.doubleTapSeekEnabled) { off: Offset ->
                        when (GestureMath.zoneOf(off.x, size.width.toFloat())) {
                            GestureMath.Zone.LEFT -> c.seekStep(false)
                            GestureMath.Zone.RIGHT -> c.seekStep(true)
                            GestureMath.Zone.CENTER -> c.togglePlay()
                        }
                    } else null,
                )
            }
            .pointerInput(g, s.swipeBrightnessVolumeEnabled, s.swipeScrubEnabled) {
                if (!g) return@pointerInput
                var mode = 0 // 0 undecided, -1 ignored, 1 scrub, 2 brightness, 3 volume
                var total = Offset.Zero
                var startX = 0f
                var startLevel = 0f
                var startPos = 0L
                detectDragGestures(
                    onDragStart = { o -> mode = 0; total = Offset.Zero; startX = o.x },
                    onDrag = { change, amount ->
                        change.consume()
                        total += amount
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (mode == 0) {
                            mode = if (abs(total.x) > abs(total.y)) {
                                if (s.swipeScrubEnabled && c.durationMs > 0) 1 else -1
                            } else if (!s.swipeBrightnessVolumeEnabled) -1
                            else if (GestureMath.isLeftHalf(startX, w)) 2 else 3
                            when (mode) {
                                1 -> startPos = c.player.currentPosition
                                2 -> startLevel = c.currentBrightness()
                                3 -> startLevel = c.currentVolume()
                            }
                        }
                        when (mode) {
                            1 -> c.scrubPreviewMs = GestureMath.scrubTarget(startPos, total.x, w, c.durationMs)
                            2 -> c.setBrightness(GestureMath.levelAfterDrag(startLevel, total.y, h))
                            3 -> c.setVolume(GestureMath.levelAfterDrag(startLevel, total.y, h))
                        }
                    },
                    onDragEnd = {
                        if (mode == 1) c.scrubPreviewMs?.let { c.seekTo(it) }
                        c.scrubPreviewMs = null; mode = 0
                    },
                    onDragCancel = { c.scrubPreviewMs = null; mode = 0 },
                )
            },
    )
}

@Composable
private fun LockLayer(c: PlayerController) {
    var showUnlock by remember { mutableStateOf(false) }
    LaunchedEffect(showUnlock) { if (showUnlock) { delay(2500); showUnlock = false } }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { showUnlock = true } }) {
        AnimatedVisibility(showUnlock, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp), enter = fadeIn(), exit = fadeOut()) {
            PillButton("Unlock controls", onClick = { c.locked = false; c.controlsVisible = true; c.poke() }, emphasized = true)
        }
    }
}

@Composable
private fun CenterSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Vermilion, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun TitleCard(c: PlayerController) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(c.request.title, style = MaterialTheme.typography.headlineSmall, color = Paper, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            c.request.subtitleLine?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = PaperMuted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(color = Vermilion, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(10.dp))
            Text("Loading stream", style = MaterialTheme.typography.labelMedium, color = PaperMuted)
        }
        // Back remains reachable while loading.
        Box(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout).padding(12.dp)) {
            val activity = LocalContext.current as PlayerActivity
            RoundGlyph(onClick = { activity.onPlayerFinished(PlayerExit.Closed) }) { BackArrow() }
        }
    }
}

@Composable
private fun ReconnectBanner(c: PlayerController) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha = 0.85f)).padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            CircularProgressIndicator(color = Gold, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(10.dp))
            Text("Reconnecting...", style = MaterialTheme.typography.titleMedium, color = Paper)
            Text("Attempt ${c.reconnectAttempt} of 5 · retrying in ${c.reconnectSecondsLeft}s", style = MaterialTheme.typography.labelMedium, color = PaperMuted)
        }
    }
}

@Composable
private fun ControlsOverlay(c: PlayerController, activity: PlayerActivity) {
    Box(Modifier.fillMaxSize()) {
        // top bar
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.85f), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundGlyph(onClick = { activity.onPlayerFinished(PlayerExit.Closed) }) { BackArrow() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.request.title, style = MaterialTheme.typography.titleMedium, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                c.request.subtitleLine?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = PaperMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Subtitles", { c.panelTab = PanelTab.SUBTITLES }, selected = c.selectedSubtitleKey != null)
                PillButton("Audio", { c.panelTab = PanelTab.AUDIO })
                PillButton(speedLabel(c.speed), { c.panelTab = PanelTab.SPEED })
                PillButton(c.aspect.label, { c.cycleAspect(); c.poke() })
                if (Build.VERSION.SDK_INT >= 26) PillButton("PiP", { activity.requestEnterPip() })
                PillButton("Lock", { c.locked = true; c.controlsVisible = false })
            }
        }

        // centre transport
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            val step = c.settings.seekStepSeconds
            RoundGlyph(size = 56.dp, onClick = { c.seekStep(false) }) { Text("-$step", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            RoundGlyph(size = 76.dp, filled = true, onClick = { c.togglePlay() }) {
                if (c.isPlaying) PauseGlyph() else PlayGlyph()
            }
            RoundGlyph(size = 56.dp, onClick = { c.seekStep(true) }) { Text("+$step", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        }

        // bottom bar
        var showRemaining by remember { mutableStateOf(false) }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.9f))))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            SeekBar(
                position = c.scrubPreviewMs ?: c.positionMs, duration = c.durationMs, buffered = c.bufferedMs,
                onScrub = { c.scrubPreviewMs = it; c.poke() },
                onCommit = { c.scrubPreviewMs = null; c.seekTo(it) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val pos = c.scrubPreviewMs ?: c.positionMs
                Text(TimeFormat.clock(pos), color = Paper, style = MaterialTheme.typography.labelLarge)
                Text(
                    if (c.durationMs <= 0) "--:--" else if (showRemaining) "-" + TimeFormat.clock(c.durationMs - pos) else TimeFormat.clock(c.durationMs),
                    color = PaperMuted, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { showRemaining = !showRemaining },
                )
            }
        }
    }
}

private fun speedLabel(v: Float): String = (if (v == v.toInt().toFloat()) "${v.toInt()}.0" else "%.2f".format(v).trimEnd('0')) + "x"

@Composable
private fun SeekBar(position: Long, duration: Long, buffered: Long, onScrub: (Long) -> Unit, onCommit: (Long) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val frac = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufFrac = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Canvas(
        Modifier.fillMaxWidth().height(48.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(duration) {
                detectTapGestures { o -> if (duration > 0) onCommit(GestureMath.positionForFraction(o.x / width, duration)) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFraction = (o.x / width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val f = ((dragFraction ?: 0f) + dx / width).coerceIn(0f, 1f)
                        dragFraction = f
                        if (duration > 0) onScrub(GestureMath.positionForFraction(f, duration))
                    },
                    onDragEnd = {
                        dragFraction?.let { if (duration > 0) onCommit(GestureMath.positionForFraction(it, duration)) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                )
            },
    ) {
        val cy = size.height / 2
        val th = 4.dp.toPx()
        drawLine(Paper.copy(alpha = 0.22f), Offset(0f, cy), Offset(size.width, cy), th, StrokeCap.Round)
        drawLine(Paper.copy(alpha = 0.40f), Offset(0f, cy), Offset(size.width * bufFrac, cy), th, StrokeCap.Round)
        drawLine(Vermilion, Offset(0f, cy), Offset(size.width * frac, cy), th, StrokeCap.Round)
        drawCircle(Gold, radius = if (dragFraction != null) 9.dp.toPx() else 7.dp.toPx(), center = Offset(size.width * frac, cy))
    }
}

@Composable
private fun Hud(c: PlayerController) {
    Box(Modifier.fillMaxSize()) {
        c.hud?.let { HudChip(it, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) }
        c.resumedToast?.let { HudChip(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)) }
        c.subtitleBusyMessage?.let { HudChip(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)) }
        if (c.speedHold) HudChip("2x speed", Modifier.align(Alignment.TopCenter).padding(top = 28.dp), accent = true)
        c.scrubPreviewMs?.let { p ->
            val delta = p - c.positionMs
            HudChip(
                TimeFormat.clock(p) + "  (" + (if (delta >= 0) "+" else "-") + abs(delta / 1000) + " s)",
                Modifier.align(Alignment.Center), big = true,
            )
        }
        val fb = c.seekFeedback
        if (fb != null) {
            var visible by remember(fb.id) { mutableStateOf(true) }
            LaunchedEffect(fb.id) { delay(650); visible = false }
            if (visible) HudChip(
                (if (fb.forward) "+" else "-") + fb.seconds + " s",
                Modifier.align(if (fb.forward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 56.dp),
                big = true,
            )
        }
    }
}

@Composable
private fun HudChip(text: String, modifier: Modifier = Modifier, big: Boolean = false, accent: Boolean = false) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(Ink.copy(alpha = 0.82f))
            .border(1.dp, if (accent) Vermilion else Gold.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = if (big) 22.dp else 16.dp, vertical = if (big) 12.dp else 8.dp),
    ) {
        Text(text, color = Paper, fontSize = if (big) 22.sp else 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BoxScope.NextEpisodeCardImpl(c: PlayerController) {
    val next = c.request.nextEpisode ?: return
    val phase = c.nextPhase
    AnimatedVisibility(
        visible = phase !is NextEpisodeTiming.Phase.Hidden && c.panelTab == null && !c.locked && c.error == null,
        modifier = Modifier.align(Alignment.BottomEnd),
        enter = fadeIn(), exit = fadeOut(),
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                .padding(end = 20.dp, bottom = if (c.controlsVisible) 88.dp else 28.dp)
                .width(280.dp)
                .clip(RoundedCornerShape(14.dp)).background(InkSurface.copy(alpha = 0.96f))
                .border(1.dp, Gold.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(16.dp),
        ) {
            Text(
                if (phase is NextEpisodeTiming.Phase.Countdown) "Next episode in ${phase.secondsLeft}s" else "Up next",
                color = Gold, style = MaterialTheme.typography.labelMedium,
            )
            Text(next.title, color = Paper, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Play now", { c.playNextNow() }, emphasized = true)
                PillButton("Cancel", { c.dismissNext() })
            }
        }
    }
}

@Composable
private fun NextEpisodeCard(c: PlayerController) {
    Box(Modifier.fillMaxSize()) { NextEpisodeCardImpl(c) }
}

// ---------- small building blocks ----------

@Composable
internal fun PillButton(text: String, onClick: () -> Unit, selected: Boolean = false, emphasized: Boolean = false) {
    val shape = RoundedCornerShape(50)
    // 48dp touch target around the visible pill
    Box(Modifier.heightIn(min = 48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.clip(shape)
                .background(if (emphasized) Vermilion else if (selected) Gold.copy(alpha = 0.22f) else Ink.copy(alpha = 0.55f))
                .border(1.dp, if (selected) Gold else Paper.copy(alpha = 0.28f), shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = Paper, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
    }
}

@Composable
private fun RoundGlyph(size: androidx.compose.ui.unit.Dp = 48.dp, filled: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (filled) Vermilion else Ink.copy(alpha = 0.55f))
            .border(1.dp, if (filled) Color.Transparent else Paper.copy(alpha = 0.3f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PlayGlyph() = Canvas(Modifier.size(28.dp)) {
    val p = Path().apply { moveTo(size.width * 0.22f, size.height * 0.1f); lineTo(size.width * 0.92f, size.height * 0.5f); lineTo(size.width * 0.22f, size.height * 0.9f); close() }
    drawPath(p, Paper)
}

@Composable
private fun PauseGlyph() = Canvas(Modifier.size(28.dp)) {
    val w = size.width * 0.24f
    drawRect(Paper, Offset(size.width * 0.16f, size.height * 0.1f), Size(w, size.height * 0.8f))
    drawRect(Paper, Offset(size.width * 0.6f, size.height * 0.1f), Size(w, size.height * 0.8f))
}

@Composable
private fun BackArrow() = Canvas(Modifier.size(22.dp)) {
    val sw = 2.5.dp.toPx()
    drawLine(Paper, Offset(size.width * 0.9f, size.height / 2), Offset(size.width * 0.12f, size.height / 2), sw, StrokeCap.Round)
    drawLine(Paper, Offset(size.width * 0.12f, size.height / 2), Offset(size.width * 0.48f, size.height * 0.14f), sw, StrokeCap.Round)
    drawLine(Paper, Offset(size.width * 0.12f, size.height / 2), Offset(size.width * 0.48f, size.height * 0.86f), sw, StrokeCap.Round)
}
