package com.liuying.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.repo.InstallPreview
import com.liuying.app.data.repo.PluginRepo
import com.liuying.app.data.remote.toUserMessage
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.data.sync.AccountState
import com.liuying.app.domain.model.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddSourceUi(
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val preview: InstallPreview? = null,
    val confirmRisky: Boolean = false,
)

data class AccountFormUi(val username: String = "", val password: String = "", val busy: Boolean = false, val error: String? = null)

data class RepoAddUi(val input: String = "", val busy: Boolean = false, val error: String? = null)

class YouViewModel(private val c: AppContainer) : ViewModel() {
    private val eager = SharingStarted.WhileSubscribed(5_000)
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, eager, AppSettings())
    val sources: StateFlow<List<Source>> = c.sources.sources.stateIn(viewModelScope, eager, emptyList())
    val account: StateFlow<AccountState> = c.sync.state
    val repos: StateFlow<List<PluginRepo>> = c.plugins.repos.stateIn(viewModelScope, eager, emptyList())
    val pluginsRunnable: Boolean get() = c.pluginRunner.runnable

    val add = MutableStateFlow(AddSourceUi())
    val form = MutableStateFlow(AccountFormUi())
    val repoAdd = MutableStateFlow(RepoAddUi())
    val message = MutableStateFlow<String?>(null)

    fun update(f: (AppSettings) -> AppSettings) { viewModelScope.launch { c.settings.update(f) } }
    fun resetSettings() { viewModelScope.launch { c.settings.resetToDefaults(); message.value = "Settings reset to defaults." } }

    // ------------------------------------------------------------------------------------ sources

    fun setAddInput(s: String) = add.update { it.copy(input = s, error = null) }

    fun previewSource() {
        val input = add.value.input.trim()
        if (input.isEmpty() || add.value.busy) return
        add.update { it.copy(busy = true, error = null, preview = null, confirmRisky = false) }
        viewModelScope.launch {
            val r = c.sources.preview(input, settings.value.allowInsecureHttp)
            add.update { cur -> r.fold({ cur.copy(busy = false, preview = it) }, { cur.copy(busy = false, error = it.toUserMessage()) }) }
        }
    }

    fun setRiskyConfirmed(v: Boolean) = add.update { it.copy(confirmRisky = v) }
    fun cancelPreview() = add.update { it.copy(preview = null, error = null, confirmRisky = false) }

    fun install(replace: Boolean) {
        val cur = add.value
        val p = cur.preview ?: return
        add.update { it.copy(busy = true) }
        viewModelScope.launch {
            c.sources.install(p, replace = replace, confirmRisky = cur.confirmRisky).fold(
                onSuccess = {
                    add.value = AddSourceUi()
                    message.value = "Added ${it.displayName}."
                    c.sync.syncSoon(1_000)
                },
                onFailure = { e -> add.update { it.copy(busy = false, error = e.toUserMessage()) } },
            )
        }
    }

    fun setSourceEnabled(s: Source, on: Boolean) { viewModelScope.launch { c.sources.setEnabled(s.id, on); c.sync.syncSoon(1_000) } }
    fun moveSource(s: Source, delta: Int) {
        val list = sources.value
        val idx = list.indexOfFirst { it.id == s.id }
        if (idx < 0) return
        viewModelScope.launch { c.sources.move(s.id, idx + delta); c.sync.syncSoon(1_000) }
    }
    fun removeSource(s: Source) { viewModelScope.launch { c.sources.remove(s.id); message.value = "Removed ${s.displayName}."; c.sync.syncSoon(1_000) } }
    fun refreshSource(s: Source) {
        viewModelScope.launch {
            c.sources.refreshManifest(s.id).fold({ message.value = "${s.displayName} refreshed." }, { message.value = "${s.displayName}: ${it.toUserMessage()}" })
        }
    }

    // ------------------------------------------------------------------------------------ account

    fun setUsername(s: String) = form.update { it.copy(username = s, error = null) }
    fun setPassword(s: String) = form.update { it.copy(password = s, error = null) }

    fun submitAccount(create: Boolean) {
        val f = form.value
        if (f.busy) return
        if (f.username.isBlank() || f.password.isEmpty()) { form.update { it.copy(error = "Enter a username and password.") }; return }
        form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            c.sync.signIn(f.username, f.password, create).fold(
                onSuccess = { form.value = AccountFormUi(); message.value = if (create) "Account created. Syncing." else "Signed in. Syncing." },
                onFailure = { e -> form.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    fun signOut() { viewModelScope.launch { c.sync.signOut(); message.value = "Signed out. Your data stays on this device." } }
    fun syncNow() { viewModelScope.launch { c.sync.syncNow() } }

    // ------------------------------------------------------------------------------------ plugin repositories

    fun setRepoInput(s: String) = repoAdd.update { it.copy(input = s, error = null) }

    fun addRepo() {
        val input = repoAdd.value.input.trim()
        if (input.isEmpty() || repoAdd.value.busy) return
        repoAdd.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            c.plugins.add(input).fold(
                onSuccess = { repoAdd.value = RepoAddUi(); message.value = "Repository added. Its plugins are off until you allow them." },
                onFailure = { e -> repoAdd.update { it.copy(busy = false, error = e.message ?: "Could not add this repository.") } },
            )
        }
    }

    fun refreshRepo(r: PluginRepo) {
        viewModelScope.launch { c.plugins.refresh(r.id).fold({ message.value = "${r.name} updated." }, { message.value = "${r.name}: ${it.message}" }) }
    }

    fun removeRepo(r: PluginRepo) { viewModelScope.launch { c.plugins.remove(r.id); message.value = "Removed ${r.name}." } }
    fun consent(r: PluginRepo, thenEnable: String?) {
        viewModelScope.launch {
            c.plugins.recordConsent(r.id)
            if (thenEnable != null) c.plugins.setEnabled(r.id, thenEnable, true)
        }
    }
    fun setPluginEnabled(repoId: Long, pluginId: String, on: Boolean) { viewModelScope.launch { c.plugins.setEnabled(repoId, pluginId, on) } }
    val updates = c.updates
    val updateState: StateFlow<com.liuying.app.data.update.UpdateState> = c.updates.state
    fun checkForUpdates() = c.updates.checkNow()
    fun clearMessage() { message.value = null }
}
