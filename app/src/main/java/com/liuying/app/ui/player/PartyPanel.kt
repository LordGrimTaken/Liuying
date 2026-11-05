package com.liuying.app.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.party.PartyController
import com.liuying.app.data.party.PartyPhase
import com.liuying.app.data.party.PartyUiState
import com.liuying.app.domain.party.PartyLogic
import com.liuying.app.ui.settings.fieldColors
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

/**
 * The "Watch party" section of the player's More panel: create a room (code + shareable link), join by code or link,
 * and once active show the code, the link and the way out. Reuses the panel widgets so it matches the other tabs.
 */
@Composable
internal fun PartySection(c: PlayerController) {
    val party = c.party
    SectionLabel("Watch party")
    if (party == null) {
        Muted("Watch parties are unavailable in this build.")
        return
    }
    val state by party.state.collectAsStateWithLifecycle()
    if (state.active) ActiveParty(c, party, state) else NoParty(c, party, state)
}

@Composable
private fun ActiveParty(c: PlayerController, party: PartyController, state: PartyUiState) {
    val context = LocalContext.current
    val code = state.code.orEmpty()
    val link = PartyLogic.shareLink(code).orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            code,
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 5.sp, fontWeight = FontWeight.Bold),
            color = Gold,
            modifier = Modifier.semantics { contentDescription = "Party code ${code.toCharArray().joinToString(" ")}" },
        )
        Text(if (state.host) "Host" else "Guest", style = MaterialTheme.typography.labelMedium, color = PaperMuted)
    }
    Spacer(Modifier.height(4.dp))
    Muted(
        if (state.host) "You are the host. Everyone follows your play, pause and seek."
        else "Following ${state.hostName?.takeIf { it.isNotBlank() } ?: "the host"}. Your controls are overridden."
    )
    if (state.members > 1) Muted("${state.members} in the room")
    Spacer(Modifier.height(8.dp))
    Text(link, style = MaterialTheme.typography.bodyMedium, color = PaperMuted)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(
            "Copy link",
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Watch party link", link))
                c.showHud("Watch party link copied")
            },
        )
        PillButton("Share", onClick = { shareParty(context, link, c.request.title) })
    }
    Spacer(Modifier.height(10.dp))
    PillButton(if (state.host) "End party" else "Leave", onClick = { party.leave() })
    state.warning?.let { Spacer(Modifier.height(6.dp)); Muted(it) }
}

@Composable
private fun NoParty(c: PlayerController, party: PartyController, state: PartyUiState) {
    val keyboard = LocalSoftwareKeyboardController.current
    var typed by remember { mutableStateOf("") }
    Muted("Start a room and everyone follows your play, pause and seek. Each device streams on its own.")
    Spacer(Modifier.height(8.dp))
    PillButton(
        if (state.phase == PartyPhase.STARTING) "Starting..." else "Start a watch party",
        onClick = { if (!state.busy) c.startWatchParty() },
        emphasized = true,
    )
    SectionLabel("Join with a code")
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it.take(80) },
        label = { Text("Party code or link") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { if (!state.busy) { keyboard?.hide(); party.join(typed) } }),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    PillButton(
        if (state.phase == PartyPhase.JOINING) "Joining..." else "Join",
        onClick = { if (!state.busy) { keyboard?.hide(); party.join(typed) } },
    )
    Spacer(Modifier.height(6.dp))
    state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Vermilion) }
    state.notice?.let { Muted(it) }
}

private fun shareParty(context: Context, link: String, title: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Watch $title with me on Liuying: $link")
        context.startActivity(Intent.createChooser(send, "Share watch party"))
    }
}
