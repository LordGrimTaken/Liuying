package com.liuying.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.liuying.app.data.account.AccountApi
import com.liuying.app.data.account.PrefsSessionStore
import com.liuying.app.data.db.LiuyingDatabase
import com.liuying.app.data.party.PartyApi
import com.liuying.app.data.party.PartyController
import com.liuying.app.data.remote.AddonClient
import com.liuying.app.data.repo.CatalogRepository
import com.liuying.app.data.repo.LibraryRepository
import com.liuying.app.data.repo.MetaRepository
import com.liuying.app.data.repo.RowSnapshots
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
import com.liuying.app.data.plugins.PluginHttp
import com.liuying.app.data.plugins.PluginScripts
import com.liuying.app.data.plugins.QuickJsPluginRunner
import com.liuying.app.data.plugins.TmdbResolver
import com.liuying.app.data.repo.PluginStreams
import com.liuying.app.domain.plugins.PluginRunner
import com.liuying.app.ui.player.PlayerDependencies
import com.liuying.app.domain.model.PlaybackRef
import com.liuying.app.domain.sync.SyncLogic
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
    // a corrupt settings file is replaced by defaults instead of crashing every launch (reads were already guarded, writes were not)
    private val settingsStore = PreferenceDataStoreFactory.create(
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
        scope = scope,
    ) { app.preferencesDataStoreFile("settings") }
    val settings = SettingsRepository(settingsStore, com.liuying.app.data.settings.SecretCodec(com.liuying.app.data.settings.KeystoreBox()))

    @Volatile private var timeoutScale = 1.0
    val http = AddonClient.createHttpClient(app.cacheDir.resolve("addon-http"))
    val addonClient = AddonClient(http, timeoutScale = { timeoutScale })
    /** Video and subtitle traffic: no disk cache (a 200 response for a whole video file would be written to it). */
    private val playerHttp = http.newBuilder().cache(null)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    /** APK download: no cache, and never follows an https redirect down to plain http. */
    private val downloadHttp = http.newBuilder().cache(null).followSslRedirects(false)
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()

    val sources by lazy { SourceRepository(db, db.sourceDao(), addonClient) }
    val catalogs by lazy { CatalogRepository(sources, addonClient, settings.settings, RowSnapshots(app.cacheDir.resolve("rows"))) }
    val metas by lazy { MetaRepository(sources, addonClient, RoomMetaStore(db.metaCacheDao())) }
    val streams by lazy { StreamRepository(sources, addonClient, pluginStreams) }
    val library by lazy { LibraryRepository(db.libraryDao()) }
    val progress by lazy { ProgressRepository(db.progressDao(), settings) }
    val recentSearches get() = db.recentSearchDao()

    /** Sandboxed plugin runner: its own HTTP client (no cookies, no cache, private addresses refused) and a fresh JS engine per run. */
    private val pluginHttp by lazy { PluginHttp("Liuying/${app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty().ifBlank { "0" }} (Android)") }
    val pluginRunner: PluginRunner by lazy {
        val scripts = PluginScripts(pluginHttp)
        QuickJsPluginRunner(pluginHttp, loadScript = { scripts.load(it) })
    }
    val pluginStreams by lazy {
        PluginStreams(plugins, pluginRunner, masterSwitch = { settings.current().pluginsEnabled },
            tmdb = TmdbResolver(http, apiKey = { settings.current().metadataApiKey }))
    }
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

    val updates by lazy {
        com.liuying.app.data.update.UpdateController(app, com.liuying.app.data.update.Updater(downloadHttp), scope, autoEnabled = { settings.current().autoUpdateCheck })
    }

    private val session = PrefsSessionStore(app.getSharedPreferences("liuying_account", 0))
    val accountApi = AccountApi(http, session)
    val partyApi by lazy { PartyApi(accountApi) }
    val sync by lazy { SyncManager(accountApi, session, library, progress, sources, settings, scope) }

    /**
     * A watch-party session for one player. The player binds it to its own ExoPlayer; it uses the app scope so the
     * final leave/end still reaches the server while the player activity is being destroyed.
     */
    fun newPartyController(): PartyController = PartyController(partyApi, scope)

    /** Details-screen hints: the list item (and its source) a title was opened from. */
    private val hints = HashMap<String, Pair<MetaPreview, Long?>>()
    fun putHint(type: String, preview: MetaPreview, sourceId: Long?) { synchronized(hints) { hints["$type|${preview.id}"] = preview to sourceId } }
    fun hint(type: String, id: String): Pair<MetaPreview, Long?>? = synchronized(hints) { hints["$type|$id"] }

    /** A manifest link opened from outside (stremio://, or an https .../manifest.json link); consumed by the You tab's add-source flow. */
    val pendingLink = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private val _playerExits = MutableSharedFlow<PlayerExit>(extraBufferCapacity = 8)
    val playerExits: SharedFlow<PlayerExit> = _playerExits.asSharedFlow()

    @Volatile private var playerSettings = PlayerSettings()

    fun start() {
        scope.launch { settings.migrateSecrets() }
        PlayerDependencies.progressReporter = progress
        PlayerDependencies.httpClient = playerHttp
        PlayerDependencies.gateway = com.liuying.app.ui.details.AppPlaybackGateway(this)
        PlayerDependencies.trackMemory = com.liuying.app.data.settings.TrackMemoryPrefs(app.getSharedPreferences("liuying_track_memory", 0))
        PlayerDependencies.onPlaybackStarting = { r ->
            val ref = if (r.episodeId == null) PlaybackRef.movie(r.type, r.mediaId)
            else SyncLogic.parseEpisodeId(r.mediaId, r.episodeId)?.let { (s, e) -> PlaybackRef.episode(r.type, r.mediaId, r.episodeId, s, e) }
            if (ref != null) progress.ensureRegistered(ref)
        }
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
        updates.checkOnStart()
    }
}
