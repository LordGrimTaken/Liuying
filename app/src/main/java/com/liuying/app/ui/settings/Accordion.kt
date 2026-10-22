package com.liuying.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuying.app.ui.common.EaseOutSoft
import com.liuying.app.ui.common.Glyph
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.motionMs
import com.liuying.app.ui.common.pressScale
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted

/** Which top-level sections are open. Survives rotation and process death (rememberSaveable) and is remembered across launches. */
class ExpandedSections(initial: Set<String>, private val persist: (Set<String>) -> Unit) {
    var open by mutableStateOf(initial)
        private set

    fun isOpen(id: String) = id in open
    fun toggle(id: String) {
        open = if (id in open) open - id else open + id
        persist(open)
    }
}

@Composable
fun rememberExpandedSections(): ExpandedSections {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("liuying_ui", android.content.Context.MODE_PRIVATE) }
    val persist: (Set<String>) -> Unit = remember { { set -> runCatching { prefs.edit().putString("you_open", YouLogic.encodeOpen(set)).apply() } } }
    return rememberSaveable(
        saver = listSaver(save = { it.open.toList() }, restore = { ExpandedSections(it.toSet(), persist) }),
    ) { ExpandedSections(YouLogic.decodeOpen(runCatching { prefs.getString("you_open", null) }.getOrNull()), persist) }
}

/** A saveable set of ids (for nested expansion state such as expanded plugin rows). */
@Composable
fun rememberSavableIds(vararg keys: Any?): androidx.compose.runtime.MutableState<Set<String>> =
    rememberSaveable(*keys, saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })) { mutableStateOf(emptySet<String>()) }

/**
 * One top-level section: a card with a header (title, one-line [summary], rotating chevron) that expands to [content].
 * The animation is skipped under Reduce motion.
 */
@Composable
fun SettingsSection(
    title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    modifier: Modifier = Modifier, badge: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(shape).background(InkSurface.copy(alpha = 0.7f), shape).border(BorderStroke(0.5.dp, GoldHairline), shape)) {
        AccordionHeader(title, summary, expanded, onToggle, big = true, badge = badge)
        SectionBody(expanded) { Column(Modifier.padding(bottom = 14.dp), content = content) }
    }
}

/** A nested group inside a section (a source's catalogs, one repository). */
@Composable
fun SubSection(
    title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(shape).background(Paper.copy(alpha = 0.04f), shape).border(BorderStroke(0.5.dp, GoldHairline.copy(alpha = 0.6f)), shape)) {
        AccordionHeader(title, summary, expanded, onToggle, big = false, badge = null)
        SectionBody(expanded) { Column(Modifier.padding(bottom = 10.dp), content = content) }
    }
}

@Composable
private fun SectionBody(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(motionMs(280), easing = EaseOutSoft)) + fadeIn(tween(motionMs(200))),
        exit = shrinkVertically(tween(motionMs(240), easing = EaseOutSoft)) + fadeOut(tween(motionMs(160))),
    ) { content() }
}

@Composable
private fun AccordionHeader(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, big: Boolean, badge: (@Composable () -> Unit)?) {
    val source = remember { MutableInteractionSource() }
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(motionMs(280), easing = EaseOutSoft), label = "chevron")
    Row(
        Modifier.fillMaxWidth().pressScale(source, 0.99f).heightIn(min = if (big) 64.dp else 52.dp)
            .semantics { role = Role.Button; stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(interactionSource = source, indication = null, onClick = onToggle)
            .padding(horizontal = if (big) 16.dp else 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                title, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (big) TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 19.sp) else MaterialTheme.typography.titleMedium,
            )
            Text(summary, color = PaperMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
        }
        badge?.invoke()
        Glyph(GlyphKind.ChevronDown, tint = PaperMuted, size = 24.dp, modifier = Modifier.rotate(angle))
    }
}
