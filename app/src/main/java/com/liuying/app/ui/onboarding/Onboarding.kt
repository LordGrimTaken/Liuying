package com.liuying.app.ui.onboarding

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.liuying.app.AppContainer
import com.liuying.app.data.remote.AddonUrls
import com.liuying.app.domain.ClipboardLink
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * L078: three plain steps on an empty Today. Skippable, and the skip is remembered.
 * Replaces the single "add a source" empty state until dismissed.
 */
@Composable
fun FirstRunGuide(onAddSource: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp).background(InkSurface, shape).border(BorderStroke(0.5.dp, GoldHairline), shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Welcome to Liuying", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Paper, modifier = Modifier.semantics { heading() })
        Text("Liuying is a player and organiser. It brings no catalogue of its own: everything on Today comes from sources you add.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
        GuideStep(1, "What a source is", "A source is an addon: a web service, run by someone else, that lists titles and finds streams. You add one with its manifest link, which ends in manifest.json.")
        GuideStep(2, "Add one", "Paste the link or tap a stremio:// link in your browser. You can check what it offers before it is installed.")
        GuideStep(3, "What it can see", "Sources you add can see your IP address and what you ask them for. Your shelf and history stay on this phone unless you sign in to sync.")
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            InkButton("Add a source", onAddSource)
            InkTextButton("Skip", onSkip, color = PaperMuted)
        }
    }
}

@Composable
private fun GuideStep(n: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.size(28.dp).background(Vermilion.copy(alpha = 0.18f), CircleShape).border(BorderStroke(1.dp, Vermilion), CircleShape), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$n", color = Paper, style = MaterialTheme.typography.labelLarge)
        }
        Column {
            Text("Step $n: $title", color = Paper, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(body, color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * L079: when the app comes to the front and the clipboard holds just a source manifest link, offer to add it.
 * Each link is offered once (accepted or not), and only when the setting is on.
 */
@Composable
fun ClipboardLinkOffer(container: AppContainer) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var offer by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                // Android 10+ only lets the focused app read the clipboard, so wait until the window has it.
                scope.launch {
                    kotlinx.coroutines.delay(400)
                    val s = container.settings.current()
                    if (!s.clipboardLinkDetection) return@launch
                    val text = runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    }.getOrNull()
                    val link = ClipboardLink.detect(text)
                    val installed = link != null && container.sources.sources.first().any { src ->
                        AddonUrls.normalizeManifestUrl(link).getOrNull() == src.manifestUrl
                    }
                    if (ClipboardLink.shouldOffer(link, s.clipboardLinkDetection, s.clipboardLinkSeen, installed)) offer = link
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    val link = offer ?: return
    val host = runCatching { AddonUrls.host(AddonUrls.normalizeManifestUrl(link).getOrThrow()) }.getOrDefault(link)
    fun markSeen() { val l = link; offer = null; CoroutineScope(Dispatchers.Main).launch { container.settings.update { it.copy(clipboardLinkSeen = l) } } }
    AlertDialog(
        onDismissRequest = { markSeen() }, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
        title = { Text("Add this source?") },
        text = { Text("You copied a source link from $host. You can check what it offers before anything is installed. Sources can see your IP address.") },
        confirmButton = { InkTextButton("Review and add", { val l = link; markSeen(); container.pendingLink.value = l }) },
        dismissButton = { InkTextButton("Not now", { markSeen() }, color = PaperMuted) },
    )
}
