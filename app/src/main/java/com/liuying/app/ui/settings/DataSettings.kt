package com.liuying.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.ui.common.InkTextButton
import com.liuying.app.ui.history.titleLookup
import com.liuying.app.ui.common.containerViewModel
import com.liuying.app.ui.theme.InkSurface
import com.liuying.app.ui.theme.Paper
import com.liuying.app.ui.theme.PaperMuted
import com.liuying.app.ui.theme.Vermilion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Cache size, clear cache, clear watch history, debug info and a typed-confirmation full reset. */
class DataViewModel(private val c: AppContainer, private val app: android.app.Application) : ViewModel() {
    val cacheBytes = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<String?>(null)

    init { refreshSize() }

    fun refreshSize() {
        viewModelScope.launch { cacheBytes.value = withContext(Dispatchers.IO) { sizeOf(app.cacheDir) } }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { c.http.cache?.evictAll() }
                runCatching { c.db.metaCacheDao().deleteAll() }
                runCatching { coil3.SingletonImageLoader.get(app).let { it.diskCache?.clear(); it.memoryCache?.clear() } }
                runCatching { File(app.cacheDir, "updates").deleteRecursively() }
            }
            refreshSize()
            message.value = "Cache cleared."
        }
    }

    fun clearHistory() {
        viewModelScope.launch { c.progress.clearAll(); message.value = "Watch history cleared." }
    }

    /** Everything local: sources, shelf, progress, settings, session, caches. */
    fun resetApp() {
        viewModelScope.launch {
            runCatching { c.sync.signOut() }
            withContext(Dispatchers.IO) {
                runCatching { c.db.clearAllTables() }
                runCatching { c.http.cache?.evictAll() }
                runCatching { coil3.SingletonImageLoader.get(app).let { it.diskCache?.clear(); it.memoryCache?.clear() } }
                runCatching { File(app.cacheDir, "updates").deleteRecursively() }
            }
            runCatching { app.getSharedPreferences("liuying_track_memory", Context.MODE_PRIVATE).edit().clear().apply() }
            c.settings.resetToDefaults()
            refreshSize()
            message.value = "Liuying is back to a clean slate."
        }
    }

    /** Plain text for bug reports. Hosts only: no full source URLs (they may carry personal config) and no credentials. */
    suspend fun debugInfo(): String {
        val s = c.settings.settings.first()
        val sources = c.sources.getAll()
        val acct = c.sync.state.value
        return buildString {
            appendLine("Liuying ${c.updates.installedVersionName} (${c.updates.installedVersionCode})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Sources: ${sources.size} (${sources.count { it.enabled }} enabled)")
            sources.forEach { appendLine("  - ${it.host} ${if (it.enabled) "on" else "off"} fetched=${it.fetchedAt} error=${it.lastError ?: "none"}") }
            appendLine("Account: ${if (acct.signedIn) "signed in" else "signed out"}, sync=${acct.status}")
            appendLine("Settings: theme=${s.theme} timeout=${s.timeoutProfile} hw=${s.hardwareDecoding} http=${s.allowInsecureHttp}")
        }
    }

    fun clearMessage() { message.value = null }

    enum class Export(val mime: String, val fileName: String) {
        LibraryCsv("text/csv", "liuying-shelf.csv"), HistoryCsv("text/csv", "liuying-history.csv"), Json("application/json", "liuying-export.json"),
    }

    /** L031: builds the export text. Sources, stream links and credentials are never included. */
    suspend fun buildExport(kind: Export): String {
        val library = c.library.observeAll().first()
        val progress = c.progress.snapshot().filter(com.liuying.app.domain.HistoryLogic::isVisible).sortedByDescending { it.updatedAt }
        val metas = c.titleLookup(progress)
        val names = library.associateBy { it.type to it.id }
        val rows = progress.map { e ->
            com.liuying.app.domain.ExportHistoryRow(e, metas[e.type to e.titleId]?.name?.takeIf { it.isNotBlank() } ?: names[e.type to e.titleId]?.name ?: e.titleId)
        }
        return when (kind) {
            Export.LibraryCsv -> com.liuying.app.domain.ExportLogic.libraryCsv(library)
            Export.HistoryCsv -> com.liuying.app.domain.ExportLogic.historyCsv(rows)
            Export.Json -> com.liuying.app.domain.ExportLogic.json(library, rows, System.currentTimeMillis(), c.updates.installedVersionName)
        }
    }

    fun writeExport(kind: Export, uri: android.net.Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = buildExport(kind)
                    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no stream")
                }.isSuccess
            }
            message.value = if (ok) "Exported." else "Could not save the file."
        }
    }

    private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
}

@Composable
fun DataSettings(onOpenHistory: (() -> Unit)? = null) {
    val context = LocalContext.current
    val vm = containerViewModel { DataViewModel(it, context.applicationContext as android.app.Application) }
    val bytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmHistory by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var pending by remember { mutableStateOf<DataViewModel.Export?>(null) }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val k = pending; pending = null
        if (uri != null && k != null) vm.writeExport(k, uri)
    }
    fun export(k: DataViewModel.Export) { pending = k; saver.launch(k.fileName) }

    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() } }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Cache: " + (bytes?.let { "%.1f MB".format(it / 1_048_576.0) } ?: "measuring"), color = PaperMuted, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        if (onOpenHistory != null) InkTextButton("Watch history", onOpenHistory)
        InkTextButton("Export shelf (CSV)", { export(DataViewModel.Export.LibraryCsv) })
        InkTextButton("Export history (CSV)", { export(DataViewModel.Export.HistoryCsv) })
        InkTextButton("Export everything (JSON)", { export(DataViewModel.Export.Json) })
        Text("Exports hold titles and watch times only: no sources, links or account details.", color = PaperMuted, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        InkTextButton("Clear cache", vm::clearCache)
        InkTextButton("Clear watch history", { confirmHistory = true }, color = Vermilion)
        InkTextButton("Copy debug info", {
            scope.launch {
                val text = vm.debugInfo()
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Liuying debug info", text))
                Toast.makeText(context, "Debug info copied.", Toast.LENGTH_SHORT).show()
            }
        })
        InkTextButton("Reset app…", { typed = ""; confirmReset = true }, color = Vermilion)
    }

    if (confirmHistory) AlertDialog(
        onDismissRequest = { confirmHistory = false }, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
        title = { Text("Clear watch history?") },
        text = { Text("Continue watching and watched marks are cleared on this device and, if you are signed in, on your account. Your shelf and sources stay.") },
        confirmButton = { InkTextButton("Clear", { vm.clearHistory(); confirmHistory = false }, color = Vermilion) },
        dismissButton = { InkTextButton("Cancel", { confirmHistory = false }, color = PaperMuted) },
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, containerColor = InkSurface, titleContentColor = Paper, textContentColor = PaperMuted,
        title = { Text("Reset Liuying?") },
        text = {
            Column {
                Text("This removes every source, your shelf, watch progress and settings from this device, and signs you out. Your account itself is not deleted. Type RESET to confirm.")
                OutlinedTextField(typed, { typed = it }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        },
        confirmButton = { InkTextButton("Reset", { if (typed.trim() == "RESET") { vm.resetApp(); confirmReset = false } }, color = if (typed.trim() == "RESET") Vermilion else PaperMuted) },
        dismissButton = { InkTextButton("Cancel", { confirmReset = false }, color = PaperMuted) },
    )
}
