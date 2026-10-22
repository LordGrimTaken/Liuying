package com.liuying.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.liuying.app.domain.ProgressRules
import com.liuying.app.domain.sync.SettingsSyncLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Settings on DataStore. Every value is saved instantly; missing keys fall back to the spec-07 defaults, which is
 * also what makes upgrades from older schema versions lossless (new keys simply read their defaults).
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { read(it) }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    /** Atomic read-modify-write of the whole settings object. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    /**
     * Edit settings that sync with the account: [transform] runs, then [keys] are marked unsynced so the next sync pushes
     * them (and so a pull does not overwrite them first).
     */
    suspend fun updateSynced(vararg keys: String, transform: (AppSettings) -> AppSettings) =
        update { transform(it).let { n -> n.copy(syncDirtyKeys = n.syncDirtyKeys + keys) } }

    /** Back to defaults (does not touch sources, library or progress). */
    suspend fun resetToDefaults() {
        store.edit { it.clear() }
    }

    // ------------------------------------------------------------------------------ mapping

    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val fontScale = stringPreferencesKey("font_scale")
        val homeOrder = stringPreferencesKey("home_order")
        val hiddenCatalogs = stringPreferencesKey("hidden_catalogs")
        val syncDirty = stringPreferencesKey("sync_dirty_keys")
        val language = stringPreferencesKey("language")
        val posterSize = stringPreferencesKey("poster_size")
        val titlesUnder = booleanPreferencesKey("titles_under_posters")
        val badges = booleanPreferencesKey("show_badges")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val showCw = booleanPreferencesKey("show_continue_watching")
        val showUpNext = booleanPreferencesKey("show_up_next")
        val hero = booleanPreferencesKey("hero_enabled")
        val heroKey = stringPreferencesKey("hero_catalog_key")
        val hideAdult = booleanPreferencesKey("hide_adult")
        val rowCount = stringPreferencesKey("row_item_count")
        val resume = stringPreferencesKey("resume_behavior")
        val threshold = doublePreferencesKey("completion_threshold")
        val autoNext = booleanPreferencesKey("auto_play_next")
        val countdown = intPreferencesKey("next_countdown_sec")
        val askNext = booleanPreferencesKey("ask_before_next")
        val streamSel = stringPreferencesKey("stream_selection")
        val prefRes = stringPreferencesKey("preferred_resolution")
        val langWords = stringPreferencesKey("preferred_language_words")
        val excluded = stringPreferencesKey("excluded_words")
        val maxSize = doublePreferencesKey("max_size_gb")
        val preferHdr = booleanPreferencesKey("prefer_hdr")
        val autoWait = intPreferencesKey("auto_select_wait_sec")
        val prefSource = longPreferencesKey("preferred_source_id")
        val grouping = stringPreferencesKey("stream_grouping")
        val sort = stringPreferencesKey("stream_sort")
        val rawDesc = booleanPreferencesKey("show_raw_stream_description")
        val hideNonPlayable = booleanPreferencesKey("hide_non_playable")
        val seekStep = intPreferencesKey("seek_step_sec")
        val longSkip = intPreferencesKey("long_skip_sec")
        val resumeRewind = intPreferencesKey("resume_rewind_sec")
        val firstRun = booleanPreferencesKey("first_run_guide_dismissed")
        val clipDetect = booleanPreferencesKey("clipboard_link_detection")
        val clipSeen = stringPreferencesKey("clipboard_link_seen")
        val hideWatchedBrowse = booleanPreferencesKey("hide_watched_browse")
        val calHideWatched = booleanPreferencesKey("calendar_hide_watched")
        val gBright = booleanPreferencesKey("gesture_brightness")
        val gVol = booleanPreferencesKey("gesture_volume")
        val gSpeed = booleanPreferencesKey("gesture_speed_hold")
        val gPinch = booleanPreferencesKey("gesture_pinch")
        val aspect = stringPreferencesKey("default_aspect")
        val speed = floatPreferencesKey("default_speed")
        val resetSpeed = booleanPreferencesKey("reset_speed")
        val orientation = stringPreferencesKey("orientation")
        val keepOn = booleanPreferencesKey("keep_screen_on")
        val pauseUnplug = booleanPreferencesKey("pause_on_unplug")
        val hwDecode = stringPreferencesKey("hardware_decoding")
        val mobileData = booleanPreferencesKey("allow_mobile_data")
        val external = stringPreferencesKey("external_player")
        val audioLangs = stringPreferencesKey("preferred_audio_langs")
        val subLangs = stringPreferencesKey("preferred_subtitle_langs")
        val subMode = stringPreferencesKey("subtitle_mode")
        val autoSubs = booleanPreferencesKey("auto_fetch_subtitles")
        val subSize = floatPreferencesKey("subtitle_size_scale")
        val subColor = longPreferencesKey("subtitle_color")
        val subOutline = booleanPreferencesKey("subtitle_outline")
        val subBg = booleanPreferencesKey("subtitle_background")
        val subBottom = intPreferencesKey("subtitle_bottom_percent")
        val subBold = booleanPreferencesKey("subtitle_bold")
        val subEdge = stringPreferencesKey("subtitle_edge")
        val subEdgeColor = longPreferencesKey("subtitle_edge_color")
        val subBgOpacity = intPreferencesKey("subtitle_bg_opacity")
        val timeout = stringPreferencesKey("timeout_profile")
        val insecure = booleanPreferencesKey("allow_insecure_http")
        val hideNoPoster = booleanPreferencesKey("hide_titles_without_poster")
        val trailer = booleanPreferencesKey("trailer_enabled")
        val trailerAuto = booleanPreferencesKey("trailer_autoplay_hero")
        val spoiler = booleanPreferencesKey("spoiler_protection")
        val apiKey = stringPreferencesKey("metadata_api_key")
        val history = booleanPreferencesKey("save_watch_history")
        val libSort = stringPreferencesKey("library_sort")
        val libView = stringPreferencesKey("library_view")
        val recent = booleanPreferencesKey("recent_searches_enabled")
        val plugins = booleanPreferencesKey("plugins_enabled")
        val autoUpdate = booleanPreferencesKey("auto_update_check")
        val imgCache = intPreferencesKey("image_cache_limit_mb")
        val schema = intPreferencesKey("schema_version")
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val sep = "\n"
    private fun split(s: String?) = s?.split(sep)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    internal fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = p.enum(K.theme, d.theme),
            accent = p[K.accent] ?: d.accent,
            fontScale = p[K.fontScale]?.takeIf { it in SettingsSyncLogic.FONT_SCALES } ?: d.fontScale,
            homeOrder = split(p[K.homeOrder]),
            hiddenCatalogs = p[K.hiddenCatalogs]?.let { runCatching { jsonParser.parseToJsonElement(it) }.getOrNull() }
                ?.let { SettingsSyncLogic.parseHidden(it) }.orEmpty(),
            syncDirtyKeys = split(p[K.syncDirty]).toSet(),
            language = p[K.language] ?: d.language,
            posterSize = p.enum(K.posterSize, d.posterSize),
            titlesUnderPosters = p[K.titlesUnder] ?: d.titlesUnderPosters,
            showBadges = p[K.badges] ?: d.showBadges,
            reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
            showContinueWatching = p[K.showCw] ?: d.showContinueWatching,
            showUpNext = p[K.showUpNext] ?: d.showUpNext,
            heroEnabled = p[K.hero] ?: d.heroEnabled,
            heroCatalogKey = p[K.heroKey],
            hideAdultCatalogs = p[K.hideAdult] ?: d.hideAdultCatalogs,
            rowItemCount = p.enum(K.rowCount, d.rowItemCount),
            resumeBehavior = p.enum(K.resume, d.resumeBehavior),
            completionThreshold = ProgressRules.clampThreshold(p[K.threshold] ?: d.completionThreshold),
            autoPlayNext = p[K.autoNext] ?: d.autoPlayNext,
            nextEpisodeCountdownSec = p[K.countdown] ?: d.nextEpisodeCountdownSec,
            askBeforeNext = p[K.askNext] ?: d.askBeforeNext,
            streamSelection = p.enum(K.streamSel, d.streamSelection),
            preferredResolution = p.enum(K.prefRes, d.preferredResolution),
            preferredLanguageWords = split(p[K.langWords]),
            excludedWords = split(p[K.excluded]),
            maxSizeGb = p[K.maxSize],
            preferHdr = p[K.preferHdr] ?: d.preferHdr,
            autoSelectWaitSec = (p[K.autoWait] ?: d.autoSelectWaitSec).coerceIn(1, 10),
            preferredSourceId = p[K.prefSource],
            streamGrouping = p.enum(K.grouping, d.streamGrouping),
            streamSort = p.enum(K.sort, d.streamSort),
            showRawStreamDescription = p[K.rawDesc] ?: d.showRawStreamDescription,
            hideNonPlayableStreams = p[K.hideNonPlayable] ?: d.hideNonPlayableStreams,
            seekStepSec = p[K.seekStep] ?: d.seekStepSec,
            longSkipSec = (p[K.longSkip] ?: d.longSkipSec).coerceIn(30, 120),
            resumeRewindSec = (p[K.resumeRewind] ?: d.resumeRewindSec).coerceIn(0, 30),
            firstRunGuideDismissed = p[K.firstRun] ?: d.firstRunGuideDismissed,
            clipboardLinkDetection = p[K.clipDetect] ?: d.clipboardLinkDetection,
            clipboardLinkSeen = p[K.clipSeen] ?: d.clipboardLinkSeen,
            hideWatchedInBrowse = p[K.hideWatchedBrowse] ?: d.hideWatchedInBrowse,
            calendarHideWatched = p[K.calHideWatched] ?: d.calendarHideWatched,
            gestureBrightness = p[K.gBright] ?: d.gestureBrightness,
            gestureVolume = p[K.gVol] ?: d.gestureVolume,
            gestureSpeedHold = p[K.gSpeed] ?: d.gestureSpeedHold,
            gesturePinchZoom = p[K.gPinch] ?: d.gesturePinchZoom,
            defaultAspect = p.enum(K.aspect, d.defaultAspect),
            defaultSpeed = p[K.speed] ?: d.defaultSpeed,
            resetSpeedEachTime = p[K.resetSpeed] ?: d.resetSpeedEachTime,
            orientation = p.enum(K.orientation, d.orientation),
            keepScreenOn = p[K.keepOn] ?: d.keepScreenOn,
            pauseOnHeadphoneUnplug = p[K.pauseUnplug] ?: d.pauseOnHeadphoneUnplug,
            hardwareDecoding = p.enum(K.hwDecode, d.hardwareDecoding),
            allowMobileData = p[K.mobileData] ?: d.allowMobileData,
            externalPlayer = p.enum(K.external, d.externalPlayer),
            preferredAudioLanguages = split(p[K.audioLangs]),
            preferredSubtitleLanguages = split(p[K.subLangs]),
            subtitleMode = p.enum(K.subMode, d.subtitleMode),
            autoFetchSubtitles = p[K.autoSubs] ?: d.autoFetchSubtitles,
            subtitleSizeScale = p[K.subSize] ?: d.subtitleSizeScale,
            subtitleColor = p[K.subColor] ?: d.subtitleColor,
            subtitleOutline = p[K.subOutline] ?: d.subtitleOutline,
            subtitleBackground = p[K.subBg] ?: d.subtitleBackground,
            subtitleBottomPercent = p[K.subBottom] ?: d.subtitleBottomPercent,
            subtitleBold = p[K.subBold] ?: d.subtitleBold,
            subtitleEdge = p[K.subEdge] ?: d.subtitleEdge,
            subtitleEdgeColor = p[K.subEdgeColor] ?: d.subtitleEdgeColor,
            subtitleBgOpacity = (p[K.subBgOpacity] ?: d.subtitleBgOpacity).coerceIn(0, 100),
            timeoutProfile = p.enum(K.timeout, d.timeoutProfile),
            allowInsecureHttp = p[K.insecure] ?: d.allowInsecureHttp,
            hideTitlesWithoutPoster = p[K.hideNoPoster] ?: d.hideTitlesWithoutPoster,
            trailerEnabled = p[K.trailer] ?: d.trailerEnabled,
            trailerAutoplayHero = p[K.trailerAuto] ?: d.trailerAutoplayHero,
            spoilerProtection = p[K.spoiler] ?: d.spoilerProtection,
            metadataApiKey = p[K.apiKey] ?: d.metadataApiKey,
            saveWatchHistory = p[K.history] ?: d.saveWatchHistory,
            librarySort = p.enum(K.libSort, d.librarySort),
            libraryView = p.enum(K.libView, d.libraryView),
            recentSearchesEnabled = p[K.recent] ?: d.recentSearchesEnabled,
            pluginsEnabled = p[K.plugins] ?: d.pluginsEnabled,
            autoUpdateCheck = p[K.autoUpdate] ?: d.autoUpdateCheck,
            imageCacheLimitMb = p[K.imgCache] ?: d.imageCacheLimitMb,
            schemaVersion = p[K.schema] ?: AppSettings.CURRENT_SCHEMA,
        )
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.putOrRemove(key: Preferences.Key<T>, v: T?) {
        if (v == null) remove(key) else this[key] = v
    }

    internal fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        p[K.theme] = s.theme.name
        p[K.accent] = s.accent
        p[K.fontScale] = s.fontScale
        p[K.homeOrder] = s.homeOrder.joinToString(sep)
        p[K.hiddenCatalogs] = JsonObject(s.hiddenCatalogs.mapValues { JsonPrimitive(it.value) }).toString()
        p[K.syncDirty] = s.syncDirtyKeys.joinToString(sep)
        p[K.language] = s.language
        p[K.posterSize] = s.posterSize.name
        p[K.titlesUnder] = s.titlesUnderPosters
        p[K.badges] = s.showBadges
        p[K.reduceMotion] = s.reduceMotion
        p[K.showCw] = s.showContinueWatching
        p[K.showUpNext] = s.showUpNext
        p[K.hero] = s.heroEnabled
        p.putOrRemove(K.heroKey, s.heroCatalogKey)
        p[K.hideAdult] = s.hideAdultCatalogs
        p[K.rowCount] = s.rowItemCount.name
        p[K.resume] = s.resumeBehavior.name
        p[K.threshold] = ProgressRules.clampThreshold(s.completionThreshold)
        p[K.autoNext] = s.autoPlayNext
        p[K.countdown] = s.nextEpisodeCountdownSec
        p[K.askNext] = s.askBeforeNext
        p[K.streamSel] = s.streamSelection.name
        p[K.prefRes] = s.preferredResolution.name
        p[K.langWords] = s.preferredLanguageWords.joinToString(sep)
        p[K.excluded] = s.excludedWords.joinToString(sep)
        p.putOrRemove(K.maxSize, s.maxSizeGb)
        p[K.preferHdr] = s.preferHdr
        p[K.autoWait] = s.autoSelectWaitSec.coerceIn(1, 10)
        p.putOrRemove(K.prefSource, s.preferredSourceId)
        p[K.grouping] = s.streamGrouping.name
        p[K.sort] = s.streamSort.name
        p[K.rawDesc] = s.showRawStreamDescription
        p[K.hideNonPlayable] = s.hideNonPlayableStreams
        p[K.seekStep] = s.seekStepSec
        p[K.longSkip] = s.longSkipSec.coerceIn(30, 120)
        p[K.resumeRewind] = s.resumeRewindSec.coerceIn(0, 30)
        p[K.firstRun] = s.firstRunGuideDismissed
        p[K.clipDetect] = s.clipboardLinkDetection
        p[K.clipSeen] = s.clipboardLinkSeen
        p[K.hideWatchedBrowse] = s.hideWatchedInBrowse
        p[K.calHideWatched] = s.calendarHideWatched
        p[K.gBright] = s.gestureBrightness
        p[K.gVol] = s.gestureVolume
        p[K.gSpeed] = s.gestureSpeedHold
        p[K.gPinch] = s.gesturePinchZoom
        p[K.aspect] = s.defaultAspect.name
        p[K.speed] = s.defaultSpeed
        p[K.resetSpeed] = s.resetSpeedEachTime
        p[K.orientation] = s.orientation.name
        p[K.keepOn] = s.keepScreenOn
        p[K.pauseUnplug] = s.pauseOnHeadphoneUnplug
        p[K.hwDecode] = s.hardwareDecoding.name
        p[K.mobileData] = s.allowMobileData
        p[K.external] = s.externalPlayer.name
        p[K.audioLangs] = s.preferredAudioLanguages.joinToString(sep)
        p[K.subLangs] = s.preferredSubtitleLanguages.joinToString(sep)
        p[K.subMode] = s.subtitleMode.name
        p[K.autoSubs] = s.autoFetchSubtitles
        p[K.subSize] = s.subtitleSizeScale
        p[K.subColor] = s.subtitleColor
        p[K.subOutline] = s.subtitleOutline
        p[K.subBg] = s.subtitleBackground
        p[K.subBottom] = s.subtitleBottomPercent
        p[K.subBold] = s.subtitleBold
        p[K.subEdge] = s.subtitleEdge
        p[K.subEdgeColor] = s.subtitleEdgeColor
        p[K.subBgOpacity] = s.subtitleBgOpacity
        p[K.timeout] = s.timeoutProfile.name
        p[K.insecure] = s.allowInsecureHttp
        p[K.hideNoPoster] = s.hideTitlesWithoutPoster
        p[K.trailer] = s.trailerEnabled
        p[K.trailerAuto] = s.trailerAutoplayHero
        p[K.spoiler] = s.spoilerProtection
        p[K.apiKey] = s.metadataApiKey
        p[K.history] = s.saveWatchHistory
        p[K.libSort] = s.librarySort.name
        p[K.libView] = s.libraryView.name
        p[K.recent] = s.recentSearchesEnabled
        p[K.plugins] = s.pluginsEnabled
        p[K.autoUpdate] = s.autoUpdateCheck
        p[K.imgCache] = s.imageCacheLimitMb
        p[K.schema] = AppSettings.CURRENT_SCHEMA
    }
}
