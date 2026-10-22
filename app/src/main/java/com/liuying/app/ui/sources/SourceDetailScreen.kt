package com.liuying.app.ui.sources

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.domain.model.CatalogEntry
import com.liuying.app.domain.model.CatalogPref
import com.liuying.app.domain.model.ManifestCatalog
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.displayName
import com.liuying.app.domain.model.needsInput
import com.liuying.app.domain.model.usableCatalogs
import com.liuying.app.domain.sync.SettingsSyncLogic
import com.liuying.app.ui.common.Badge
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.common.GlyphKind
import com.liuying.app.ui.common.IconCircleButton
import com.liuying.app.ui.common.InkButton
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.common.SectionHeader
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.nav.DockClearance
import com.liuying.app.ui.nav.Navigator
import com.liuying.app.ui.theme.Gold
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SourceDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val source: StateFlow<Source?> = c.sources.sources.map { l -> l.firstOrNull { it.id == id } }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val prefs: StateFlow<List<CatalogPref>> = c.sources.catalogPrefs.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val hidden: StateFlow<Map<String, Boolean>> = c.settings.settings.map { it.hiddenCatalogs }.stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())
    val message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun setTodayVisible(entry: CatalogEntry, visible: Boolean) {
        viewModelScope.launch {
            c.settings.updateSynced(SettingsSyncLogic.HIDDEN) { it.copy(hiddenCatalogs = it.hiddenCatalogs + (entry.webKey to !visible)) }
            c.sync.settingsChanged()
        }
    }

    fun setBrowse(s: Source, cat: ManifestCatalog, on: Boolean) { viewModelScope.launch { c.sources.setCatalogBrowse(s.id, cat.type, cat.id, on) } }
    fun setEnabled(s: Source, on: Boolean) { viewModelScope.launch { c.sources.setEnabled(s.id, on); c.sync.syncSoon(1_000) } }
    fun refresh(s: Source) {
        viewModelScope.launch {
            c.sources.refreshManifest(s.id).fold(
                { message.value = "${s.displayName} refreshed." },
                { message.value = "${s.displayName}: ${it.message ?: "could not refresh"}" },
            )
        }
    }
    fun clearMessage() { message.value = null }
}

@Composable
fun SourceDetailScreen(sourceId: Long, navigator: Navigator) {
    val vm = containerViewModel(key = "source:$sourceId") { SourceDetailViewModel(it, sourceId) }
    val source by vm.source.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler { navigator.back() }
    androidx.compose.runtime.LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            IconCircleButton(GlyphKind.Close, "Back", navigator::back)
            Text("Source", style = MaterialTheme.typography.titleLarge, color = Paper, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
        }
        val s = source
        if (s == null) {
            EmptyBlock("Source not found", "It may have been removed.", action = { InkButton("Go back", navigator::back) })
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = DockClearance)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.displayName, color = Paper, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Switch(s.enabled, { vm.setEnabled(s, it) })
                    }
                    Text(s.host + (if (s.isHttp) "  ·  plain http" else "") + (s.manifest.version.takeIf { it.isNotBlank() }?.let { "  ·  v$it" } ?: ""), color = PaperMuted, style = MaterialTheme.typography.labelMedium)
                    if (s.manifest.description.isNotBlank()) Text(s.manifest.description, color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        s.manifest.resources.map { it.name }.distinct().forEach { Badge(it) }
                    }
                }
            }
            item { SectionHeader("Health") }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    Text("Last refreshed: " + if (s.fetchedAt > 0) fmt.format(Date(s.fetchedAt)) else "never", color = Paper, style = MaterialTheme.typography.bodyMedium)
                    if (s.lastError != null) {
                        Text("Last problem: ${s.lastError}" + (s.lastErrorAt?.let { " (${fmt.format(Date(it))})" } ?: ""), color = Vermilion, style = MaterialTheme.typography.bodyMedium)
                    } else Text("No problems reported.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
                    if (s.needsConfiguration) Text("This source needs configuring on its own page before it works.", color = Gold, style = MaterialTheme.typography.bodyMedium)
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        InkTextButton("Refresh now", { vm.refresh(s) })
                        if (s.needsConfiguration) InkTextButton("Configure", { openConfigure(context, s) })
                    }
                }
            }
            item { SectionHeader("Catalogs") }
            val cats = s.manifest.usableCatalogs
            if (cats.isEmpty()) item { Text("This source offers no catalogs. It can still provide streams, subtitles or details.", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp)) }
            items(cats, key = { it.type + "|" + it.id }) { cat ->
                val entry = CatalogEntry(s, cat)
                val pref = prefs.firstOrNull { it.sourceId == s.id && it.type == cat.type && it.catalogId == cat.id }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(cat.displayName + "  ·  " + cat.type, color = Paper, style = MaterialTheme.typography.bodyLarge)
                    if (cat.needsInput) {
                        Text("Needs input this app cannot supply, so it is not shown on Today or Browse.", color = Gold, style = MaterialTheme.typography.labelMedium)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("On Today", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(hidden[entry.webKey] != true, { vm.setTodayVisible(entry, it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("In Browse", color = PaperMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(pref?.showInBrowse ?: true, { vm.setBrowse(s, cat, it) })
                        }
                    }
                }
            }
            item { SectionHeader("Share") }
            item {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    InkTextButton("Copy link", {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Manifest URL", s.manifestUrl))
                        Toast.makeText(context, "Link copied.", Toast.LENGTH_SHORT).show()
                    })
                    InkTextButton("Share", {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.manifestUrl)
                        runCatching { context.startActivity(Intent.createChooser(send, "Share source link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
                }
            }
        }
    }
}

/** A source that needs setup keeps its configuration page next to its manifest (`.../configure`); opened in the browser. */
private fun openConfigure(context: Context, s: Source) {
    val url = s.baseUrl.trimEnd('/') + "/configure"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "No browser available.", Toast.LENGTH_SHORT).show() }
}
