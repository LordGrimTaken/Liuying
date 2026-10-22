package com.liuying.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.dp
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.Ink
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) Paper else PaperMuted, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Vermilion, checkedThumbColor = Paper, uncheckedTrackColor = Ink, uncheckedThumbColor = PaperMuted, uncheckedBorderColor = GoldHairline))
    }
}

@Composable
fun <T> ChoiceRow(title: String, subtitle: String? = null, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = Paper, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Text(label(selected), color = Gold, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onSelect(o) }) }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold, unfocusedBorderColor = GoldHairline, cursorColor = Gold,
    focusedTextColor = Paper, unfocusedTextColor = Paper, focusedLabelColor = Gold, unfocusedLabelColor = PaperMuted,
)

fun enumLabel(name: String): String =
    name.replace(Regex("([a-z])([A-Z0-9])"), "$1 $2").replace("UHD4K", "4K").replace("FHD1080", "1080p").replace("HD720", "720p").replace("SD480", "480p")
        .replaceFirstChar { it.uppercase() }
