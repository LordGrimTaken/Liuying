package com.liuying.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.repo.InstallWarning
import com.liuying.app.data.repo.PluginRepo
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.data.settings.PreferredResolution
import com.liuying.app.data.settings.ResumeBehavior
import com.liuying.app.data.settings.RowItemCount
import com.liuying.app.data.settings.StreamGrouping
import com.liuying.app.data.settings.StreamSelectionMode
import com.liuying.app.data.settings.StreamSort
import com.liuying.app.data.settings.SubtitleMode
import com.liuying.app.data.settings.TimeoutProfile
import com.liuying.app.data.sync.SyncStatus
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.plugins.PluginPermissions
import com.liuying.app.ui.common.Badge
import com.liuying.app.ui.common.ErrorNote
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.ScreenTitle
import com.liuying.app.ui.common.SectionHeader
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.GoldHairline
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion
import com.liuying.app.data.update.UpdateState
import java.text.DateFormat
import java.util.Date

@Composable
fun YouScreen(navigator: Navigator) {
    val vm = containerViewModel { YouViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val repos by vm.repos.collectAsStateWithLifecycle()
    val add by vm.add.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val repoAdd by vm.repoAdd.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var removing by remember { mutableStateOf<Source?>(null) }
    var removingRepo by remember { mutableStateOf<PluginRepo?>(null) }
    var consenting by remember { mutableStateOf<Pair<PluginRepo, String?>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = DockClearance)) {
        item { ScreenTitle("You") }

        // ---------------------------------------------------------------- account
        item { SectionHeader("Liuying account") }
        item {
            if (account.signedIn) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Signed in as ${account.username}", color = Paper, style = MaterialTheme.typography.bodyLarge)
                    Text(syncLine(account.status), color = if (account.status is SyncStatus.Failed) Vermilion else PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                    Text("Your shelf, sources and progress sync best-effort. Everything works offline first.", color = PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InkButton("Sync now", vm::syncNow, enabled = account.status != SyncStatus.Syncing)
                        InkButton("Sign out", vm::signOut, filled = false)
                    }
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optional. Sign in to keep your shelf, sources and progress in step with liuyingapp.com.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
                    if (account.status is SyncStatus.Failed) Text(syncLine(account.status), color = Vermilion, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(form.username, vm::setUsername, label = { Text("Username") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    OutlinedTextField(form.password, vm::setPassword, label = { Text("Password") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    form.error?.let { Text(it, color = Vermilion, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InkButton(if (form.busy) "Please wait" else "Sign in", { vm.submitAccount(false) }, enabled = !form.busy)
                        InkButton("Create account", { vm.submitAccount(true) }, enabled = !form.busy, filled = false)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- sources
        item { SectionHeader("Sources") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a manifest link from any compatible source. Liuying ships with none and never adds one for you.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(add.input, vm::setAddInput, label = { Text("Manifest URL") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                add.error?.let { Text(it, color = Vermilion, style = MaterialTheme.typography.bodyMedium) }
                InkButton(if (add.busy && add.preview == null) "Checking" else "Check link", vm::previewSource, enabled = !add.busy && add.input.isNotBlank())
                add.preview?.let { p ->
                    Column(Modifier.fillMaxWidth().border(BorderStroke(0.5.dp, GoldHairline), RoundedCornerShape(10.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.manifest.name, color = Paper, style = MaterialTheme.typography.titleMedium)
                        Text("${p.host}  ·  v${p.manifest.version.ifBlank { "?" }}", color = PaperMuted, style = MaterialTheme.typography.labelMedium)
                        if (p.manifest.description.isNotBlank()) Text(p.manifest.description, color = PaperMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (p.manifest.catalogs.isNotEmpty()) Badge("${p.manifest.catalogs.size} catalogs")
                            p.manifest.resources.map { it.name }.distinct().forEach { Badge(it) }
                        }
                        p.warnings.forEach { w -> warningText(w)?.let { Text(it, color = Gold, style = MaterialTheme.typography.bodyMedium) } }
                        if (p.requiresExtraConfirmation) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(add.confirmRisky, vm::setRiskyConfirmed)
                                Text("I understand and want to add it", color = Paper, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (p.isDuplicate) InkButton("Replace", { vm.install(true) }, enabled = !add.busy && (!p.requiresExtraConfirmation || add.confirmRisky))
                            else InkButton("Add source", { vm.install(false) }, enabled = !add.busy && (!p.requiresExtraConfirmation || add.confirmRisky))
                            InkButton("Cancel", vm::cancelPreview, filled = false)
                        }
                    }
                }
            }
        }
        if (sources.isEmpty()) {
            item { Text("No sources yet.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
        }
        sources.forEachIndexed { idx, s ->
            item(key = "src${s.id}") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(s.displayName, color = if (s.enabled) Paper else PaperMuted, style = MaterialTheme.typography.titleMedium)
                            Text(s.host + (if (s.isHttp) "  ·  plain http" else ""), color = PaperMuted, style = MaterialTheme.typography.labelMedium)
                        }
                        Switch(s.enabled, { vm.setSourceEnabled(s, it) })
                    }
                    s.lastError?.let { Text("Last refresh failed: $it", color = Vermilion, style = MaterialTheme.typography.labelMedium) }
                    if (s.needsConfiguration) Text("This source needs configuring on its own page before it works.", color = Gold, style = MaterialTheme.typography.labelMedium)
                    Row {
                        InkTextButton("Up", { vm.moveSource(s, -1) }, color = if (idx > 0) Gold else PaperMuted)
                        InkTextButton("Down", { vm.moveSource(s, 1) }, color = if (idx < sources.lastIndex) Gold else PaperMuted)
                        InkTextButton("Refresh", { vm.refreshSource(s) })
                        InkTextButton("Remove", { removing = s }, color = Vermilion)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- playback
        item { SectionHeader("Playback") }
        item { PlaybackSettings(settings, vm) }

        item { SectionHeader("Content") }
        item { ContentSettings(settings, vm) }

        // ---------------------------------------------------------------- repositories
        item { SectionHeader("Repositories", trailing = { Badge("Not yet runnable") }) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Plugin repositories list small scrapers that could add streams. This version only stores them: no plugin script is downloaded or run yet, " +
                        "so nothing you enable here has any effect until the sandboxed runner ships.",
                    color = PaperMuted, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item { ToggleRow("Allow scraper plugins", "Global off switch. Off by default.", settings.pluginsEnabled) { v -> vm.update { it.copy(pluginsEnabled = v) } } }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(repoAdd.input, vm::setRepoInput, label = { Text("Repository manifest URL") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                repoAdd.error?.let { Text(it, color = Vermilion, style = MaterialTheme.typography.bodyMedium) }
                InkButton(if (repoAdd.busy) "Fetching" else "Add repository", vm::addRepo, enabled = !repoAdd.busy && repoAdd.input.isNotBlank())
            }
        }
        repos.forEach { r ->
            item(key = "repo${r.id}") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(r.name + (if (r.version.isNotBlank()) "  v${r.version}" else ""), color = Paper, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(r.author?.let { "by $it" }, "${r.plugins.size} plugins", if (r.consented) "permissions accepted" else "permissions not yet accepted").joinToString("  ·  "),
                        color = PaperMuted, style = MaterialTheme.typography.labelMedium)
                    if (r.description.isNotBlank()) Text(r.description, color = PaperMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    r.lastError?.let { Text("Last update failed: $it", color = Vermilion, style = MaterialTheme.typography.labelMedium) }
                    r.plugins.forEach { p ->
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(p.info.name + (if (p.info.version.isNotBlank()) "  v${p.info.version}" else ""), color = Paper, style = MaterialTheme.typography.bodyLarge)
                                if (p.info.description.isNotBlank()) Text(p.info.description, color = PaperMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(if (p.info.permissions.isEmpty()) "No permissions" else p.info.permissions.joinToString("; ") { PluginPermissions.describe(it) }, color = Gold, style = MaterialTheme.typography.labelMedium)
                            }
                            Switch(p.enabled && settings.pluginsEnabled, { on ->
                                if (!on) vm.setPluginEnabled(r.id, p.info.id, false)
                                else if (!r.consented) consenting = r to p.info.id
                                else vm.setPluginEnabled(r.id, p.info.id, true)
                            }, enabled = settings.pluginsEnabled)
                        }
                    }
                    Row {
                        InkTextButton("Update", { vm.refreshRepo(r) })
                        InkTextButton("Remove", { removingRepo = r }, color = Vermilion)
                    }
                }
            }
        }

        item { SectionHeader("About") }
        item { ToggleRow("Check for updates automatically", "Looks at liuyingapp.com at most every 6 hours", settings.autoUpdateCheck) { v -> vm.update { it.copy(autoUpdateCheck = v) } } }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Liuying", color = Paper, style = MaterialTheme.typography.bodyLarge)
                Text("An independent player for sources you add yourself. It hosts no content.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
                Text("Version ${vm.updates.installedVersionName} (${vm.updates.installedVersionCode})", color = PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                Text(updateLine(updateState), color = if (updateState is UpdateState.Failed) Vermilion else PaperMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
                InkTextButton("Check for updates", vm::checkForUpdates, color = if (updateState is UpdateState.Checking || updateState is UpdateState.Downloading) PaperMuted else Gold)
                InkTextButton("Reset all settings", { confirmReset = true }, color = Vermilion)
            }
        }
    }

    removing?.let { s ->
        ConfirmDialog("Remove ${s.displayName}?", "Your shelf and progress stay. You can add the source again later.", "Remove", { removing = null }) { vm.removeSource(s); removing = null }
    }
    removingRepo?.let { r ->
        ConfirmDialog("Remove ${r.name}?", "Its plugins are removed too.", "Remove", { removingRepo = null }) { vm.removeRepo(r); removingRepo = null }
    }
    if (confirmReset) ConfirmDialog("Reset settings?", "Sources, shelf and progress are not touched.", "Reset", { confirmReset = false }) { vm.resetSettings(); confirmReset = false }
    consenting?.let { (r, pluginId) ->
        val plugin = r.plugins.firstOrNull { it.info.id == pluginId }
        AlertDialog(
            onDismissRequest = { consenting = null }, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
            title = { Text("Allow plugins from ${r.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Plugins are small scripts written by ${r.author ?: "the repository owner"}. When the runner exists, a plugin you enable could:")
                    val perms = plugin?.info?.permissions.orEmpty()
                    if (perms.isEmpty()) Text("• Ask for nothing beyond the limits below") else perms.forEach { Text("• " + PluginPermissions.describe(it), color = Gold) }
                    Text("Always:")
                    PluginPermissions.always.forEach { Text("• $it") }
                    Text("Nothing runs in this version. You can turn plugins off at any time, here or with the global switch.")
                }
            },
            confirmButton = { InkTextButton("Allow", { vm.consent(r, pluginId); consenting = null }) },
            dismissButton = { InkTextButton("Not now", { consenting = null }, color = PaperMuted) },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { InkTextButton(action, onConfirm, color = Vermilion) },
        dismissButton = { InkTextButton("Cancel", onDismiss, color = PaperMuted) },
    )
}

private fun updateLine(s: UpdateState): String = when (s) {
    UpdateState.Idle -> "Updates come from liuyingapp.com."
    UpdateState.Checking -> "Checking…"
    UpdateState.UpToDate -> "You have the latest version."
    is UpdateState.Available -> "Version ${s.manifest.versionName} is available."
    is UpdateState.Downloading -> "Downloading ${s.manifest.versionName}…"
    is UpdateState.Ready -> "Version ${s.manifest.versionName} is downloaded."
    is UpdateState.Failed -> s.message
}

private fun syncLine(s: SyncStatus): String = when (s) {
    SyncStatus.Idle -> "Waiting to sync."
    SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Done -> "Last synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.at))
    is SyncStatus.Failed -> s.message
}

private fun warningText(w: InstallWarning): String? = when (w) {
    InstallWarning.InsecureHttp -> "This link uses plain http: anything between you and the source can read it."
    InstallWarning.Adult -> "This source says it carries adult content."
    InstallWarning.ConfigurationRequired -> "This source needs configuring on its own page before it returns anything."
    is InstallWarning.DuplicateUrl -> "You already have this source (${w.existing.displayName})."
    is InstallWarning.DuplicateManifestId -> "A source with the same id is installed (${w.existing.displayName}). Replace updates it."
    is InstallWarning.TooManySources -> "You already have ${w.count} sources. More will slow searches."
}

@Composable
private fun PlaybackSettings(s: AppSettings, vm: YouViewModel) {
    Column {
        ChoiceRow("Resume", "When a title has saved progress", ResumeBehavior.entries, s.resumeBehavior, { enumLabel(it.name) }) { v -> vm.update { it.copy(resumeBehavior = v) } }
        ToggleRow("Play next episode automatically", null, s.autoPlayNext) { v -> vm.update { it.copy(autoPlayNext = v) } }
        ChoiceRow("Stream choice", "List lets you pick; auto plays the best match", StreamSelectionMode.entries, s.streamSelection, { enumLabel(it.name) }) { v -> vm.update { it.copy(streamSelection = v) } }
        ChoiceRow("Preferred quality", null, PreferredResolution.entries, s.preferredResolution, { enumLabel(it.name) }) { v -> vm.update { it.copy(preferredResolution = v) } }
        ChoiceRow("Auto-select wait", "How long to wait for slower sources", listOf(2, 3, 5, 8), s.autoSelectWaitSec, { "$it s" }) { v -> vm.update { it.copy(autoSelectWaitSec = v) } }
        ChoiceRow("Group streams", null, StreamGrouping.entries, s.streamGrouping, { enumLabel(it.name) }) { v -> vm.update { it.copy(streamGrouping = v) } }
        ChoiceRow("Sort streams", null, StreamSort.entries, s.streamSort, { enumLabel(it.name) }) { v -> vm.update { it.copy(streamSort = v) } }
        ToggleRow("Show full stream descriptions", null, s.showRawStreamDescription) { v -> vm.update { it.copy(showRawStreamDescription = v) } }
        ToggleRow("Hide streams that cannot play", null, s.hideNonPlayableStreams) { v -> vm.update { it.copy(hideNonPlayableStreams = v) } }
        ChoiceRow("Mark watched at", "Share of the runtime played", listOf(0.80, 0.85, 0.90, 0.95, 0.98), s.completionThreshold, { "${(it * 100).toInt()}%" }) { v -> vm.update { it.copy(completionThreshold = v) } }
        ChoiceRow("Seek step", null, listOf(5, 10, 15, 30), s.seekStepSec, { "$it s" }) { v -> vm.update { it.copy(seekStepSec = v) } }
        ChoiceRow("Subtitles", null, SubtitleMode.entries, s.subtitleMode, { enumLabel(it.name) }) { v -> vm.update { it.copy(subtitleMode = v) } }
        ToggleRow("Fetch subtitles from sources", null, s.autoFetchSubtitles) { v -> vm.update { it.copy(autoFetchSubtitles = v) } }
    }
}

@Composable
private fun ContentSettings(s: AppSettings, vm: YouViewModel) {
    Column {
        ToggleRow("Hide adult catalogs", null, s.hideAdultCatalogs) { v -> vm.update { it.copy(hideAdultCatalogs = v) } }
        ToggleRow("Allow plain http sources", "Needed for some local-network sources", s.allowInsecureHttp) { v -> vm.update { it.copy(allowInsecureHttp = v) } }
        ChoiceRow("Titles per row", null, RowItemCount.entries, s.rowItemCount, { "${it.count}" }) { v -> vm.update { it.copy(rowItemCount = v) } }
        ChoiceRow("Source patience", "Patient doubles every timeout", TimeoutProfile.entries, s.timeoutProfile, { enumLabel(it.name) }) { v -> vm.update { it.copy(timeoutProfile = v) } }
        ToggleRow("Continue watching row", null, s.showContinueWatching) { v -> vm.update { it.copy(showContinueWatching = v) } }
        ToggleRow("Up next cards", "Suggest the next episode", s.showUpNext) { v -> vm.update { it.copy(showUpNext = v) } }
        ToggleRow("Save watch history", "Off stops recording progress", s.saveWatchHistory) { v -> vm.update { it.copy(saveWatchHistory = v) } }
        ToggleRow("Remember searches", null, s.recentSearchesEnabled) { v -> vm.update { it.copy(recentSearchesEnabled = v) } }
    }
}
