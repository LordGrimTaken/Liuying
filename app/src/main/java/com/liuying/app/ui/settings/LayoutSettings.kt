package com.liuying.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.common.InkTextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.ui.common.Glyph
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.IconCircleButton
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkChip
import com.liuying.app.ui.theme.AccentChoice
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.OnAccent

private val TextSizes = listOf("s" to "Small", "m" to "Medium", "l" to "Large")

/** Accent colour and text size; both sync with the account (same keys as the web player). */
@Composable
fun AppearanceSettings(s: AppSettings, vm: YouViewModel) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Accent colour", color = Paper, style = MaterialTheme.typography.bodyLarge)
        Text("Used for buttons and selected chips.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AccentChoice.entries.forEach { a ->
                val on = s.accent == a.key
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .semantics { role = Role.RadioButton; selected = on; contentDescription = "${a.label} accent" }
                        .clickable { vm.setAccent(a.key) }.padding(vertical = 6.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(a.color, CircleShape)
                            .border(BorderStroke(if (on) 3.dp else 1.dp, if (on) Paper else GoldHairline), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Glyph(GlyphKind.Check, tint = a.onColor, size = 22.dp) }
                    Text(a.label, color = if (on) Paper else PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Text("Text size", color = Paper, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
        Text("Scales text across the app.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextSizes.forEach { (k, label) -> InkChip(label, s.fontScale == k, { vm.setFontScale(k) }) }
        }
        ToggleRow("Reduce motion", "Stops the featured hero from rotating by itself.", s.reduceMotion, onChange = { v -> vm.update { it.copy(reduceMotion = v) } })
    }
}

/** Catalog order, per-catalog visibility, hero on/off and reset. Synced with the account. Catalogs sit under one dropdown per source. */
@Composable
fun TodayLayoutSettings(s: AppSettings, rows: List<LayoutRow>, vm: YouViewModel) {
    Column(Modifier.fillMaxWidth()) {
        ToggleRow("Featured hero", "Show the rotating featured titles at the top of Today.", s.heroEnabled) { vm.setHero(it) }
        ToggleRow("Continue watching", "Show your in-progress and up next titles.", s.showContinueWatching) { v -> vm.update { it.copy(showContinueWatching = v) } }
        Text(
            if (rows.isEmpty()) "Add a source to arrange its catalogs here." else "Order the catalogs on Today and hide the ones you do not want.",
            color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        val allKeys = rows.map { it.key }
        YouLogic.groupBySource(rows) { it.source }.forEach { group ->
            var open by rememberSaveable(group.source) { mutableStateOf(false) }
            val hidden = group.rows.count { it.hidden }
            val groupKeys = group.rows.map { it.key }
            SubSection(group.source, YouLogic.layoutSummary(group.rows.size, hidden), open, { open = !open }) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    InkTextButton("Show all", { vm.setCatalogsHidden(groupKeys, false) }, color = if (hidden > 0) Gold else PaperMuted)
                    InkTextButton("Hide all", { vm.setCatalogsHidden(groupKeys, true) }, color = if (hidden < group.rows.size) Gold else PaperMuted)
                }
                group.rows.forEachIndexed { i, r ->
                    val up = YouLogic.moveDeltaWithinGroup(allKeys, groupKeys, r.key, -1)
                    val down = YouLogic.moveDeltaWithinGroup(allKeys, groupKeys, r.key, 1)
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).clip(shape).background(InkSurface, shape).border(BorderStroke(0.5.dp, GoldHairline), shape)
                            .padding(start = 12.dp).heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.title, color = if (r.hidden) PaperMuted else Paper, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f).padding(end = 4.dp))
                        IconCircleButton(GlyphKind.ChevronUp, "Move ${r.title} up", { up?.let { vm.moveCatalog(r.key, it) } }, enabled = up != null, diameter = 44.dp, tint = PaperMuted)
                        IconCircleButton(GlyphKind.ChevronDown, "Move ${r.title} down", { down?.let { vm.moveCatalog(r.key, it) } }, enabled = down != null, diameter = 44.dp, tint = PaperMuted)
                        Switch(
                            !r.hidden, { vm.setCatalogHidden(r.key, !it) },
                            modifier = Modifier.padding(horizontal = 6.dp).semantics { contentDescription = "Show ${r.title} on Today" },
                            colors = SwitchDefaults.colors(checkedTrackColor = com.liuying.app.ui.theme.Vermilion, checkedThumbColor = OnAccent, uncheckedTrackColor = Ink, uncheckedThumbColor = PaperMuted, uncheckedBorderColor = GoldHairline),
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text("Reset layout", color = Paper, style = MaterialTheme.typography.bodyLarge)
            Text("Show every catalog again, in the sources' own order, with the hero on.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            InkButton("Reset layout", vm::resetLayout, filled = false)
        }
    }
}
