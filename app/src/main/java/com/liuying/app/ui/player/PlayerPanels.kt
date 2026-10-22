package com.liuying.app.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liuying.app.ui.player.logic.Languages
import com.liuying.app.ui.player.logic.TimeFormat
import com.liuying.app.ui.player.logic.TrackInfo
import com.liuying.app.ui.player.logic.TrackLabels
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion
import kotlin.math.roundToInt

@Composable
internal fun SidePanel(c: PlayerController) {
    val tab = c.panelTab ?: return
    Row(Modifier.fillMaxSize()) {
        // Scrim: tapping outside closes the panel; the left side keeps the video visible for live preview.
        Box(Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { c.panelTab = null })
        Column(
            Modifier.width(380.dp).fillMaxHeight()
                .background(InkSurface.copy(alpha = 0.96f))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tab.title, style = MaterialTheme.typography.titleLarge, color = Paper)
                PillButton("Done", { c.panelTab = null })
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelTab.entries.forEach { t -> Chip(t.title, t == tab) { c.panelTab = t } }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (tab) {
                    PanelTab.AUDIO -> AudioTab(c)
                    PanelTab.SUBTITLES -> SubtitlesTab(c)
                    PanelTab.STYLE -> StyleTab(c)
                    PanelTab.TIMING -> TimingTab(c)
                    PanelTab.SPEED -> SpeedTab(c)
                }
            }
        }
    }
}

@Composable
private fun AudioTab(c: PlayerController) {
    if (c.audioTracks.isEmpty()) { Muted("No selectable audio tracks in this stream."); return }
    c.audioTracks.forEach { t ->
        Row2(c.audioLabels[t.key] ?: "Audio", t.key == c.selectedAudioKey) { c.selectAudio(t.key) }
    }
}

@Composable
private fun SubtitlesTab(c: PlayerController) {
    var showAll by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) c.addSubtitleFile(uri) }
    val preferred = c.settings.preferredSubtitleLanguages

    Row2("Off", c.selectedSubtitleKey == null) { c.selectSubtitle(null) }
    val groups = TrackLabels.groupSubtitles(c.subtitleTracks, preferred)
    var hidden = 0
    groups.forEach { (title, tracks) ->
        val isEmbedded = title == "Embedded"
        val visible = if (isEmbedded || showAll || preferred.isEmpty()) tracks
        else tracks.filter { Languages.preferenceIndex(it.language, preferred) >= 0 }
        hidden += tracks.size - visible.size
        if (visible.isNotEmpty()) {
            SectionLabel(if (isEmbedded) "Embedded" else "From sources · $title")
            visible.forEach { t -> Row2(c.subtitleLabels[t.key] ?: "Subtitle", t.key == c.selectedSubtitleKey) { c.selectSubtitle(t.key) } }
        }
    }
    if (c.subtitleTracks.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Muted("No subtitles found for this video")
    }
    if (hidden > 0 || showAll) {
        Spacer(Modifier.height(8.dp))
        PillButton(if (showAll) "Preferred languages only" else "All languages ($hidden more)", { showAll = !showAll })
    }
    Spacer(Modifier.height(16.dp))
    SectionLabel("Add")
    PillButton("Choose file from device", { picker.launch(arrayOf("*/*")) })
    Spacer(Modifier.height(6.dp))
    Muted("SRT, VTT, ASS/SSA and TTML are supported. Files are read as UTF-8.")
    c.subtitleBusyMessage?.let { Spacer(Modifier.height(6.dp)); Muted(it) }
}

@Composable
private fun StyleTab(c: PlayerController) {
    val s = c.settings
    Muted("Changes preview live on the video.")
    Spacer(Modifier.height(8.dp))
    LabeledSlider("Text size ${s.subtitleSizePercent}%", s.subtitleSizePercent.toFloat(), 50f..200f, 14,
        onChange = { v -> c.updateSettings(false) { it.copy(subtitleSizePercent = (v / 10f).roundToInt() * 10) } },
        onDone = { c.persistSettings() })
    SwitchRow("Bold", s.subtitleBold) { v -> c.updateSettings { it.copy(subtitleBold = v) } }

    SectionLabel("Colour")
    val colors = listOf(0xFFFFFFFF.toInt(), 0xFFF2E9DA.toInt(), 0xFFF2D14B.toInt(), 0xFFC9A45C.toInt(), 0xFF8FD3E8.toInt(), 0xFF9BE29B.toInt())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { col ->
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(col))
                    .border(if (s.subtitleColor == col) 3.dp else 1.dp, if (s.subtitleColor == col) Vermilion else Paper.copy(alpha = 0.3f), CircleShape)
                    .clickable { c.updateSettings { it.copy(subtitleColor = col) } },
            )
        }
    }

    SectionLabel("Background")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("None", s.subtitleBackground == SubtitleBackground.NONE) { c.updateSettings { it.copy(subtitleBackground = SubtitleBackground.NONE) } }
        Chip("Box", s.subtitleBackground == SubtitleBackground.BOX) { c.updateSettings { it.copy(subtitleBackground = SubtitleBackground.BOX) } }
    }
    if (s.subtitleBackground == SubtitleBackground.BOX) {
        LabeledSlider("Box opacity ${s.subtitleBackgroundOpacityPercent}%", s.subtitleBackgroundOpacityPercent.toFloat(), 0f..100f, 9,
            onChange = { v -> c.updateSettings(false) { it.copy(subtitleBackgroundOpacityPercent = (v / 10f).roundToInt() * 10) } },
            onDone = { c.persistSettings() })
    }

    SectionLabel("Edge")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(SubtitleEdge.NONE to "None", SubtitleEdge.OUTLINE to "Outline", SubtitleEdge.DROP_SHADOW to "Shadow", SubtitleEdge.RAISED to "Raised").forEach { (e, l) ->
            Chip(l, s.subtitleEdge == e) { c.updateSettings { it.copy(subtitleEdge = e) } }
        }
    }

    LabeledSlider("Distance from bottom ${s.subtitleBottomOffsetPercent}%", s.subtitleBottomOffsetPercent.toFloat(), 0f..40f, 39,
        onChange = { v -> c.updateSettings(false) { it.copy(subtitleBottomOffsetPercent = v.roundToInt()) } },
        onDone = { c.persistSettings() })
    Spacer(Modifier.height(8.dp))
    PillButton("Reset style", {
        val d = PlayerSettings()
        c.updateSettings {
            it.copy(
                subtitleSizePercent = d.subtitleSizePercent, subtitleBold = d.subtitleBold, subtitleColor = d.subtitleColor,
                subtitleBackground = d.subtitleBackground, subtitleBackgroundOpacityPercent = d.subtitleBackgroundOpacityPercent,
                subtitleEdge = d.subtitleEdge, subtitleEdgeColor = d.subtitleEdgeColor, subtitleBottomOffsetPercent = d.subtitleBottomOffsetPercent,
            )
        }
    })
}

@Composable
private fun TimingTab(c: PlayerController) {
    Muted("Shift subtitles if they run early or late. Negative values show them earlier.")
    Spacer(Modifier.height(16.dp))
    Text(TimeFormat.delay(c.subtitleDelayMs), color = Paper, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        PillButton("-1.0", { c.adjustSubtitleDelay(-1000) })
        PillButton("-0.1", { c.adjustSubtitleDelay(-100) })
        PillButton("+0.1", { c.adjustSubtitleDelay(100) })
        PillButton("+1.0", { c.adjustSubtitleDelay(1000) })
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { PillButton("Reset", { c.setSubtitleDelay(0) }) }
    Spacer(Modifier.height(10.dp))
    Muted("Applies to this playback only.")
}

@Composable
private fun SpeedTab(c: PlayerController) {
    Text("%.2fx".format(c.speed), color = Paper, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    LabeledSlider("", c.speed, 0.25f..3f, 54, onChange = { v -> c.setSpeed((v * 20f).roundToInt() / 20f) }, onDone = {})
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f).forEach { v -> Chip("${v}x", c.speed == v) { c.setSpeed(v) } }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1.75f, 2f, 2.5f, 3f).forEach { v -> Chip("${v}x", c.speed == v) { c.setSpeed(v) } }
    }
    Spacer(Modifier.height(10.dp))
    Muted("Hold a finger on the video for temporary 2x.")
}

// ---------- error ----------

@Composable
internal fun ErrorOverlay(c: PlayerController, e: ErrorInfo, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.92f)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(InkSurface)
                .border(1.dp, Gold.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(e.title, style = MaterialTheme.typography.titleLarge, color = Paper, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(e.message, style = MaterialTheme.typography.bodyMedium, color = PaperMuted, textAlign = TextAlign.Center)
            e.technical?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.labelMedium, color = PaperMuted.copy(alpha = 0.6f)) }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Retry", { c.retry() }, emphasized = true)
                PillButton("Try another stream", { c.tryAnotherStream() })
                PillButton("Close", onClose)
            }
        }
    }
}

// ---------- widgets ----------

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) = PillButton(text, onClick, selected = selected)

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Gold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = PaperMuted)

@Composable
private fun Row2(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Gold.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Paper, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selected) Text("✓", color = Gold, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Paper, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Vermilion, checkedThumbColor = Paper, uncheckedTrackColor = InkSurface, uncheckedThumbColor = PaperMuted),
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit, onDone: () -> Unit) {
    if (label.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(label, color = Paper, style = MaterialTheme.typography.bodyMedium) }
    Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone,
        colors = SliderDefaults.colors(thumbColor = Vermilion, activeTrackColor = Gold, inactiveTrackColor = Paper.copy(alpha = 0.2f)),
    )
}
