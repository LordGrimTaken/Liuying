package com.liuying.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest, val prompt: Boolean) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateState
    data class Ready(val manifest: UpdateManifest, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Android half: state machine for the UI, check throttling, FileProvider hand-off to the system installer. */
class UpdateController(
    private val app: Context,
    private val updater: Updater,
    private val scope: CoroutineScope,
    private val autoEnabled: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs = app.getSharedPreferences("liuying_update", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    private val updatesDir get() = File(app.cacheDir, "updates")

    val installedVersionCode: Long
        get() = runCatching { PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0)) }.getOrDefault(0L)
    val installedVersionName: String
        get() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    /** App start: honours the setting and the 12 h throttle; failures stay silent. */
    fun checkOnStart() {
        scope.launch {
            runCatching { updatesDir.listFiles()?.forEach { it.delete() } } // stale downloads from earlier versions
            if (UpdatePolicy.shouldAutoCheck(autoEnabled(), now(), prefs.getLong(KEY_LAST, 0L))) check(manual = false)
        }
    }

    /** Manual check from Settings: always runs and always reports a result. */
    fun checkNow() { if (_state.value !is UpdateState.Checking && _state.value !is UpdateState.Downloading) check(manual = true) }

    private fun check(manual: Boolean) {
        job?.cancel()
        _state.value = UpdateState.Checking
        job = scope.launch {
            try {
                val m = updater.fetchManifest()
                prefs.edit().putLong(KEY_LAST, now()).apply()
                _state.value = when {
                    !UpdatePolicy.isNewer(installedVersionCode, m) -> UpdateState.UpToDate
                    !manual && m.versionCode == prefs.getLong(KEY_SKIPPED, 0L) -> UpdateState.Idle // "Skip this version"
                    else -> UpdateState.Available(m, prompt = true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = if (manual) UpdateState.Failed(e.message ?: "Could not check for updates.") else UpdateState.Idle
            }
        }
    }

    /** "Later" on the dialog: keep the info in Settings but stop prompting. */
    fun dismissPrompt() {
        val s = _state.value
        if (s is UpdateState.Available) _state.value = s.copy(prompt = false)
        else if (s is UpdateState.Ready) _state.value = UpdateState.Available(s.manifest, prompt = false) // "Later": the file stays cached, Settings offers it again
        else if (s is UpdateState.Failed || s is UpdateState.UpToDate) _state.value = UpdateState.Idle
    }

    /** "Skip this version": automatic checks stay quiet about it; a manual check still offers it. */
    fun skipVersion() {
        val s = _state.value as? UpdateState.Available ?: return
        prefs.edit().putLong(KEY_SKIPPED, s.manifest.versionCode).apply()
        _state.value = UpdateState.Idle
    }

    fun download() {
        val m = (_state.value as? UpdateState.Available)?.manifest ?: return
        val need = if (m.sizeBytes > 0) m.sizeBytes * 2 else 0L
        if (need > 0 && runCatching { app.cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE) < need) {
            _state.value = UpdateState.Failed("Not enough free storage to download this update (${need / 1_048_576} MB needed).")
            return
        }
        _state.value = UpdateState.Downloading(m, 0f)
        job?.cancel()
        job = scope.launch {
            try {
                val f = updater.download(m, File(updatesDir, Updater.apkFileName(m))) { p -> _state.value = UpdateState.Downloading(m, p) }
                _state.value = UpdateState.Ready(m, f)
                install()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = UpdateState.Failed(e.message ?: "The update failed.")
            }
        }
    }

    /** True if the installer was launched; false if the user first has to allow installs from Liuying. */
    fun install(): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        if (!app.packageManager.canRequestPackageInstalls()) {
            awaitingInstallPermission = true
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", ready.file)
        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    @Volatile private var awaitingInstallPermission = false

    /** Call when the app returns to the foreground: if the user just allowed installs, carry on with the install. */
    fun resumeInstallIfPending() {
        if (!awaitingInstallPermission) return
        if (_state.value !is UpdateState.Ready) { awaitingInstallPermission = false; return }
        if (app.packageManager.canRequestPackageInstalls()) { awaitingInstallPermission = false; install() }
    }

    fun cancelDownload() {
        val s = _state.value
        if (s is UpdateState.Downloading) { job?.cancel(); _state.value = UpdateState.Available(s.manifest, prompt = false) }
    }

    private companion object { const val KEY_LAST = "last_check_ms"; const val KEY_SKIPPED = "skipped_version_code" }
}
