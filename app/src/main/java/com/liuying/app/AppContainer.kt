package com.liuying.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.liuying.app.data.account.AccountApi
import com.liuying.app.data.account.PrefsSessionStore
import com.liuying.app.data.db.LiuyingDatabase
import com.liuying.app.data.remote.AddonClient
import com.liuying.app.data.repo.CatalogRepository
import com.liuying.app.data.repo.LibraryRepository
import com.liuying.app.data.repo.MetaRepository
import com.liuying.app.data.repo.PluginRepository
import com.liuying.app.data.repo.ProgressRepository
import com.liuying.app.data.repo.RoomMetaStore
import com.liuying.app.data.repo.SourceRepository
import com.liuying.app.data.repo.StreamRepository
import com.liuying.app.data.settings.PlayerSettingsMapper
import com.liuying.app.data.settings.SettingsRepository
import com.liuying.app.data.settings.TimeoutProfile
import com.liuying.app.data.sync.SyncManager
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.plugins.NoopPluginRunner
import com.liuying.app.domain.plugins.PluginRunner
import com.liuying.app.ui.player.PlayerDependencies
import com.liuying.app.ui.player.PlayerExit
import com.liuying.app.ui.player.PlayerSettings
import com.liuying.app.ui.player.PlayerSettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.Request

/** Hand-rolled dependency container; one per process. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: LiuyingDatabase by lazy { LiuyingDatabase.build(app) }
    private val settingsStore = PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile("settings") }
    val settings = SettingsRepository(settingsStore)

    @Volatile private var timeoutScale = 1.0
    val http = AddonClient.createHttpClient(app.cacheDir.resolve("addon-http"))
    val addonClient = AddonClient(http, timeoutScale = { timeoutScale })

    val sources by lazy { SourceRepository(db, db.sourceDao(), addonClient) }
    val catalogs by lazy { CatalogRepository(sources, addonClient, settings.settings) }
    val metas by lazy { MetaRepository(sources, addonClient, RoomMetaStore(db.metaCacheDao())) }
    val streams by lazy { StreamRepository(sources, addonClient) }
    val library by lazy { LibraryRepository(db.libraryDao()) }
    val progress by lazy { ProgressRepository(db.progressDao(), settings) }
    val recentSearches get() = db.recentSearchDao()

    val pluginRunner: PluginRunner = NoopPluginRunner
    val plugins by lazy {
        PluginRepository(db.pluginDao(), fetch = { url ->
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("The repository answered with HTTP ${r.code}.")
                        val body = r.body?.string().orEmpty()
                        if (body.length > 2_000_000) throw java.io.IOException("The manifest is too large.")
                        body
                    }
                }.recoverCatching { throw java.io.IOException(if (it is java.io.IOException) it.message ?: "Could not reach the repository." else "Could not read the repository.") }
            }
        })
    }

    private val session = PrefsSessionStore(app.getSharedPreferences("liuying_account", 0))
    val accountApi = AccountApi(http, session)
    val sync by lazy { SyncManager(accountApi, session, library, progress, sources, settings, scope) }

    /** Details-screen hints: the list item (and its source) a title was opened from. */
    private val hints = HashMap<String, Pair<MetaPreview, Long?>>()
    fun putHint(type: String, preview: MetaPreview, sourceId: Long?) { synchronized(hints) { hints["$type|${preview.id}"] = preview to sourceId } }
    fun hint(type: String, id: String): Pair<MetaPreview, Long?>? = synchronized(hints) { hints["$type|$id"] }

    private val _playerExits = MutableSharedFlow<PlayerExit>(extraBufferCapacity = 8)
    val playerExits: SharedFlow<PlayerExit> = _playerExits.asSharedFlow()

    @Volatile private var playerSettings = PlayerSettings()

    fun start() {
        PlayerDependencies.progressReporter = progress
        PlayerDependencies.httpClient = http
        PlayerDependencies.settingsProvider = object : PlayerSettingsProvider {
            override fun current(): PlayerSettings = playerSettings
            override fun save(settings: PlayerSettings) {
                playerSettings = settings
                scope.launch { this@AppContainer.settings.update { PlayerSettingsMapper.applyBack(it, settings) } }
            }
        }
        PlayerDependencies.onExit = { exit ->
            _playerExits.tryEmit(exit)
            sync.syncSoon(2_000)
        }
        scope.launch {
            settings.settings.collect {
                playerSettings = PlayerSettingsMapper.from(it)
                timeoutScale = if (it.timeoutProfile == TimeoutProfile.Patient) 2.0 else 1.0
            }
        }
        scope.launch { runCatching { sources.refreshStale() } }
        sync.start()
    }
}
