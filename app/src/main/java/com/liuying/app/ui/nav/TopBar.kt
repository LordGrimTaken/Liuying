package com.liuying.app.ui.nav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuying.app.data.sync.AccountState
import com.liuying.app.data.sync.SyncStatus
import com.liuying.app.ui.common.Glyph
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.motionMs
import com.liuying.app.ui.common.pressScale
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.OnAccent
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.Vermilion

/**
 * Translucent top bar: seal and wordmark on the left, round icon buttons (Search, Calendar, Settings) and the account avatar
 * on the right. Over artwork it is only a dark top-down scrim; [solid] (after scrolling, or on pages without artwork) fades it
 * to a solid bar over 200 ms.
 */
@Composable
fun LiuyingTopBar(
    solid: Boolean, account: AccountState,
    onSearch: () -> Unit, onCalendar: () -> Unit, onSettings: () -> Unit, onAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val solidAlpha by animateFloatAsState(if (solid) 1f else 0f, tween(motionMs(200)), label = "solid")
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().alpha(1f - solidAlpha).background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.72f), Color.Transparent))))
        Box(Modifier.matchParentSize().alpha(solidAlpha).background(Ink.copy(alpha = 0.94f)))
        Row(Modifier.statusBarsPadding().height(TopBarHeight).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Seal()
            Text("Liuying", color = Paper, style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 21.sp), modifier = Modifier.padding(start = 10.dp))
            Spacer(Modifier.weight(1f))
            BarIcon(GlyphKind.Search, "Search", onSearch)
            BarIcon(GlyphKind.Calendar, "Calendar", onCalendar)
            BarIcon(GlyphKind.Gear, "Settings", onSettings)
            Spacer(Modifier.width(2.dp))
            Avatar(account, onAccount)
        }
    }
}

/** The seal: a small vermilion rounded square with 流 over 影. */
@Composable
fun Seal(modifier: Modifier = Modifier) {
    Box(modifier.size(32.dp).clip(RoundedCornerShape(3.dp)).background(Vermilion), contentAlignment = Alignment.Center) {
        Text("流\n影", color = OnAccent, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 13.sp))
    }
}

@Composable
private fun BarIcon(kind: GlyphKind, description: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    // 48dp touch target around the 42dp round button
    Box(Modifier.size(48.dp).clickable(interactionSource = source, indication = null, onClick = onClick).semantics { contentDescription = description; role = Role.Button }, contentAlignment = Alignment.Center) {
        Box(Modifier.pressScale(source).size(42.dp).clip(CircleShape).background(Ink.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
            Glyph(kind, tint = Paper, size = 24.dp)
        }
    }
}

/** Signed in: the first letter of the username on the accent colour with a sync dot. Signed out: a person glyph. */
@Composable
private fun Avatar(account: AccountState, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val desc = if (account.signedIn) "Account ${account.username}" else "Sign in"
    Box(Modifier.size(48.dp).clickable(interactionSource = source, indication = null, onClick = onClick).semantics { contentDescription = desc; role = Role.Button }, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(11.dp)
        Box(
            Modifier.pressScale(source).size(36.dp).clip(shape)
                .then(if (account.signedIn) Modifier.background(Vermilion, shape) else Modifier.background(Ink.copy(alpha = 0.28f), shape).border(1.dp, Vermilion.copy(alpha = 0.7f), shape)),
            contentAlignment = Alignment.Center,
        ) {
            if (account.signedIn) Text(account.username.orEmpty().take(1).uppercase(), color = OnAccent, style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp))
            else Glyph(GlyphKind.Person, tint = Paper, size = 20.dp)
        }
        if (account.signedIn) {
            val dot = when (account.status) {
                is SyncStatus.Done -> Color(0xFF4CAF50)
                SyncStatus.Syncing -> Gold
                is SyncStatus.Failed -> Color(0xFFE5484D)
                else -> null
            }
            if (dot != null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 3.dp).size(10.dp).clip(CircleShape).background(dot).border(1.5.dp, Ink, CircleShape))
        }
    }
}
