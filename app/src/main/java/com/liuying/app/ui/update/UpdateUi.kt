package com.liuying.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.update.UpdateController
import com.liuying.app.data.update.UpdateManifest
import com.liuying.app.data.update.UpdateState
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion

/** App-wide update prompt: shown on start (when a newer build exists) and after a manual check. */
@Composable
fun UpdateDialog(controller: UpdateController) {
    val state by controller.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is UpdateState.Available -> if (s.prompt) {
            AlertDialog(
                onDismissRequest = controller::dismissPrompt, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
                title = { Text("Update available") },
                text = { Notes(s.manifest, controller.installedVersionName) },
                confirmButton = { InkTextButton("Update now", controller::download) },
                dismissButton = {
                    Row {
                        InkTextButton("Skip this version", controller::skipVersion, color = PaperMuted)
                        InkTextButton("Later", controller::dismissPrompt, color = PaperMuted)
                    }
                },
            )
        }
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {}, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
            title = { Text("Downloading ${s.manifest.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = Vermilion)
                    Text("${(s.progress * 100).toInt()}%")
                }
            },
            confirmButton = {},
            dismissButton = { InkTextButton("Cancel", controller::cancelDownload, color = PaperMuted) },
        )
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = controller::dismissPrompt, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
            title = { Text("Ready to install") },
            text = { Text("Version ${s.manifest.versionName} is downloaded and verified. If Android asks, allow Liuying to install apps, then come back and tap Install.") },
            confirmButton = { InkTextButton("Install", { controller.install() }) },
            dismissButton = { InkTextButton("Later", controller::dismissPrompt, color = PaperMuted) },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = controller::dismissPrompt, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
            title = { Text("Update problem") }, text = { Text(s.message) },
            confirmButton = { InkTextButton("OK", controller::dismissPrompt, color = Vermilion) },
        )
        else -> Unit
    }
}

@Composable
private fun Notes(m: UpdateManifest, installed: String) {
    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Version ${m.versionName} is ready (you have $installed).", color = Paper)
        if (m.notes.isNotBlank()) Text(m.notes, style = MaterialTheme.typography.bodyMedium)
        if (m.sizeBytes > 0) Text("%.1f MB".format(m.sizeBytes / 1_048_576.0), color = Gold, style = MaterialTheme.typography.labelMedium)
    }
}
