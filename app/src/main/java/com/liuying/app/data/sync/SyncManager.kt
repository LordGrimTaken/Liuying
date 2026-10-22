package com.liuying.app.data.sync

import com.liuying.app.data.account.AccountApi
import com.liuying.app.data.account.ApiResult
import com.liuying.app.data.account.RemoteState
import com.liuying.app.data.account.SessionStore
import com.liuying.app.data.repo.LibraryRepository
import com.liuying.app.data.repo.ProgressRepository
import com.liuying.app.data.repo.SourceRepository
import com.liuying.app.data.settings.SettingsRepository
import com.liuying.app.domain.Clock
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.sync.RemoteSource
import com.liuying.app.domain.sync.SettingsSyncLogic
import com.liuying.app.domain.sync.SyncLogic
import com.liuying.app.domain.sync.SyncedSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Done(val at: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

data class AccountState(val username: String?, val status: SyncStatus = SyncStatus.Idle) {
    val signedIn: Boolean get() = username != null
}

/**
 * Local-first, best-effort sync with the Liuying account server. The app never waits on it: every local change
 * schedules a debounced [syncSoon], and failures only surface as a status line on the You screen.
 *
 * Progress is last-writer-wins by updatedAt. Library and sources use a three-way merge against the set both sides
 * agreed on last time, so deletions propagate without tombstones.
 */
class SyncManager(
    private val api: AccountApi,
    private val session: SessionStore,
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
) {
    private val mutex = Mutex()
    private var pending: Job? = null
    private val _state = MutableStateFlow(AccountState(session.username.takeIf { session.sid != null }))
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        library.onLocalChange = { syncSoon(1_500) }
        progress.onLocalChange = { syncSoon(20_000) }
    }

    /** Call after editing a synced setting (the repository already marked its key dirty). */
    fun settingsChanged() = syncSoon(1_500)

    /** Call once at app start. */
    fun start() { if (api.signedIn) syncSoon(1_000) }

    suspend fun signIn(username: String, password: String, create: Boolean): Result<Unit> {
        val r = if (create) api.signUp(username.trim(), password) else api.logIn(username.trim(), password)
        return when (r) {
            is ApiResult.Err -> Result.failure(IllegalStateException(r.message + (r.retryAfterSec?.let { " (try again in ${it}s)" } ?: "")))
            is ApiResult.Ok -> {
                for (k in KINDS) session.setBase(k, emptySet())
                _state.value = AccountState(r.value)
                scope.launch { syncNow(first = true) }
                Result.success(Unit)
            }
        }
    }

    suspend fun signOut() {
        pending?.cancel()
        api.logOut()
        _state.value = AccountState(null)
    }

    fun syncSoon(delayMs: Long = 2_000) {
        if (!api.signedIn) return
        pending?.cancel()
        pending = scope.launch { delay(delayMs); syncNow() }
    }

    /** One full pull-merge-push round. Returns true on success. Safe to call concurrently (rounds are serialized). */
    suspend fun syncNow(first: Boolean = false): Boolean = mutex.withLock {
        if (!api.signedIn) return@withLock false
        _state.value = _state.value.copy(status = SyncStatus.Syncing)
        val ok = try { round(first) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { fail("Sync failed: ${e.message ?: "unknown error"}") }
        if (ok) _state.value = AccountState(session.username, SyncStatus.Done(clock.nowMs()))
        ok
    }

    private fun fail(message: String): Boolean {
        _state.value = _state.value.copy(status = SyncStatus.Failed(message))
        return false
    }

    private fun failErr(e: ApiResult.Err): Boolean {
        if (e.unauthenticated) {
            session.clear()
            _state.value = AccountState(null, SyncStatus.Failed("Your session ended. Sign in again to keep syncing."))
            return false
        }
        return fail(e.message)
    }

    private suspend fun round(first: Boolean): Boolean {
        val remote: RemoteState = when (val r = api.getState()) {
            is ApiResult.Err -> return failErr(r)
            is ApiResult.Ok -> r.value
        }
        val now = clock.nowMs()
        if (!syncLibrary(remote, first)) return false
        if (!syncSources(remote, first)) return false
        if (!syncProgress(remote, now)) return false
        return syncSettings(remote)
    }

    // ------------------------------------------------------------------------------------ settings

    /** Pull-merge-push of the shared settings object; only keys this app owns are touched, others pass through. */
    private suspend fun syncSettings(remote: RemoteState): Boolean {
        val cur = settings.current()
        val dirty = cur.syncDirtyKeys.filter { it in SettingsSyncLogic.KEYS }.toSet()
        val local = SyncedSettings(cur.homeOrder, cur.hiddenCatalogs, cur.heroEnabled, cur.accent, cur.fontScale)
        val merged = SettingsSyncLogic.merge(local, remote.settings, dirty)
        if (merged != local) {
            settings.update { it.copy(homeOrder = merged.homeOrder, hiddenCatalogs = merged.hiddenCatalogs, heroEnabled = merged.hero, accent = merged.accent, fontScale = merged.fontScale) }
        }
        if (dirty.isEmpty()) return true
        val payload = SettingsSyncLogic.payload(remote.settings, merged, dirty)
        if (payload != remote.settings) {
            val r = api.putSettings(payload)
            if (r is ApiResult.Err) return failErr(r)
        }
        settings.update { it.copy(syncDirtyKeys = it.syncDirtyKeys - dirty) }
        return true
    }

    // ------------------------------------------------------------------------------------ library

    private suspend fun syncLibrary(remote: RemoteState, first: Boolean): Boolean {
        val local = library.snapshot()
        val localIds = local.map { it.id }.toSet()
        val remoteIds = remote.library.map { it.id }.toSet()
        val base = if (first) emptySet() else session.base(KIND_LIBRARY)
        val m = SyncLogic.threeWay(localIds, remoteIds, base)

        library.putFromSync(remote.library.filter { it.id in m.addLocal }.map {
            LibraryItem(
                type = it.type, id = it.id, name = it.name, poster = it.poster, background = null, year = null,
                genres = emptyList(), addedAt = SyncLogic.parseIsoMs(it.addedAt) ?: clock.nowMs(),
            )
        })
        library.removeIdsFromSync(m.removeLocal)

        val adds = local.filter { it.id in m.addRemote }.mapNotNull { SyncLogic.toRemoteLibrary(it) }
        val removes = m.removeRemote.toList()
        if (adds.isNotEmpty() || removes.isNotEmpty()) {
            val r = api.postLibrary(adds, removes)
            if (r is ApiResult.Err) return failErr(r)
        }
        session.setBase(KIND_LIBRARY, (remoteIds - m.removeRemote) + adds.map { it.id })
        return true
    }

    // ------------------------------------------------------------------------------------ sources

    private suspend fun syncSources(remote: RemoteState, first: Boolean): Boolean {
        val localAll = sources.getAll()
        val localUrls = localAll.map { it.manifestUrl }.toSet()
        val remoteByUrl = remote.sources.associateBy { it.url }
        val base = if (first) emptySet() else session.base(KIND_SOURCES)
        val m = SyncLogic.threeWay(localUrls, remoteByUrl.keys, base)

        for (s in localAll) if (s.manifestUrl in m.removeLocal) sources.remove(s.id)

        val failedAdds = LinkedHashSet<String>()
        val allowHttp = settings.current().allowInsecureHttp
        for (url in m.addLocal) {
            val installed = installFromSync(url, allowHttp, remoteByUrl[url]?.enabled ?: true)
            if (!installed) failedAdds += url
        }

        val now = sources.getAll()
        val desired = now.filter { SyncLogic.validSourceUrl(it.manifestUrl) }.map { RemoteSource(it.manifestUrl, it.enabled) } +
            failedAdds.mapNotNull { remoteByUrl[it] }
        val desiredTrimmed = desired.distinctBy { it.url }.take(MAX_SOURCES)
        if (desiredTrimmed != remote.sources) {
            val r = api.putSources(desiredTrimmed)
            if (r is ApiResult.Err) return failErr(r)
        }
        session.setBase(KIND_SOURCES, desiredTrimmed.map { it.url }.toSet() - failedAdds)
        return true
    }

    private suspend fun installFromSync(url: String, allowHttp: Boolean, enabled: Boolean): Boolean {
        val preview = sources.preview(url, allowHttp).getOrNull() ?: return false
        // Risky sources (plain http, adult) need an explicit tap on this device, so sync never installs them silently.
        if (preview.requiresExtraConfirmation || preview.isDuplicate) return false
        val src = sources.install(preview).getOrNull() ?: return false
        if (!enabled) sources.setEnabled(src.id, false)
        return true
    }

    // ------------------------------------------------------------------------------------ progress

    private suspend fun syncProgress(remote: RemoteState, now: Long): Boolean {
        val local = progress.snapshot()
        val types = HashMap<String, String>()
        library.snapshot().forEach { types[it.id] = it.type }
        local.forEach { types.putIfAbsent(it.titleId, it.type) }
        val merge = SyncLogic.mergeProgress(local, remote.progress, { types[it] }, now)
        progress.putFromSync(merge.storeLocal)
        for (chunk in merge.pushRemote.chunked(SyncLogic.PROGRESS_BATCH)) {
            val r = api.postProgress(chunk)
            if (r is ApiResult.Err) return failErr(r)
        }
        return true
    }

    companion object {
        const val KIND_LIBRARY = "library"
        const val KIND_SOURCES = "sources"
        private val KINDS = listOf(KIND_LIBRARY, KIND_SOURCES)
        private const val MAX_SOURCES = 100
    }
}
