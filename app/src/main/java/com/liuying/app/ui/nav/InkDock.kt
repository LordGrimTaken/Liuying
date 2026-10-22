package com.liuying.app.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuying.app.ui.common.EaseOutSoft
import com.liuying.app.ui.common.Glyph
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.motionMs
import com.liuying.app.ui.common.pressScale
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

private fun Tab.glyph() = when (this) {
    Tab.Today -> GlyphKind.Home
    Tab.Movies -> GlyphKind.Film
    Tab.Series -> GlyphKind.Tv
    Tab.Shelf -> GlyphKind.Books
    Tab.You -> GlyphKind.Person
}

/**
 * Bottom navigation: five equal items, the active one in a rounded pill tinted with the accent (24%).
 * 64dp plus the gesture inset. [current] null (Calendar) leaves every item inactive.
 */
@Composable
fun InkDock(current: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(InkSurface.copy(alpha = 0.97f))) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(GoldHairline.copy(alpha = 0.5f)))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab -> DockItem(tab, tab == current, { onSelect(tab) }, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DockItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (selected) Vermilion else PaperMuted, tween(motionMs(200)), label = "tint")
    val pill by animateColorAsState(if (selected) Vermilion.copy(alpha = 0.24f) else androidx.compose.ui.graphics.Color.Transparent, tween(motionMs(280), easing = EaseOutSoft), label = "pill")
    Box(modifier.height(64.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.pressScale(source, 0.94f).clip(RoundedCornerShape(16.dp)).background(pill)
                .semantics { role = Role.Tab; this.selected = selected; contentDescription = tab.label }
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Glyph(tab.glyph(), tint = tint, size = 22.dp)
            Text(tab.label, color = tint, maxLines = 1, softWrap = false, style = TextStyle(fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium))
        }
    }
}
