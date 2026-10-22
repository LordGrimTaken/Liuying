package com.liuying.app.ui.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.session.MediaSession
import com.liuying.app.ui.player.logic.AutoSubtitleMode
import com.liuying.app.ui.player.logic.ErrorClassifier
import com.liuying.app.ui.player.logic.ErrorKind
import com.liuying.app.ui.player.logic.HeaderPolicy
import com.liuying.app.ui.player.logic.Languages
import com.liuying.app.ui.player.logic.NextEpisodeTiming
import com.liuying.app.ui.player.logic.ProgressThrottle
import com.liuying.app.ui.player.logic.RetryPolicy
import com.liuying.app.ui.player.logic.SelectionPrefs
import com.liuying.app.ui.player.logic.SubtitleChoice
import com.liuying.app.ui.player.logic.SubtitleFormat
import com.liuying.app.ui.player.logic.TrackInfo
import com.liuying.app.ui.player.logic.TrackKind
import com.liuying.app.ui.player.logic.TrackLabels
import com.liuying.app.ui.player.logic.TrackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

enum class UiPhase { LOADING, READY, RECONNECTING, ERROR }

data class ErrorInfo(val kind: ErrorKind, val title: String, val message: String, val technical: String?)

/** In-process memory of the user's manual subtitle choice per title (language, not file). */
internal object SubtitleMemory {
    data class Entry(val language: String?, val off: Boolean)
    private val map = ConcurrentHashMap<String, Entry>()
    fun get(mediaId: String): Entry? = map[mediaId]
    fun put(mediaId: String, e: Entry) { map[mediaId] = e }
}

/**
 * Owns the ExoPlayer, MediaSession and all observable player state for one PlayerActivity.
 * Compose reads the `mutableStateOf` fields directly.
 */
@OptIn(UnstableApi::class)
class PlayerController(private val activity: PlayerActivity, val request: PlaybackRequest) {

    private val scope get() = activity.lifecycleScope
    private val provider get() = PlayerDependencies.settingsProvider

    // ---- observable state ----
    var settings by mutableStateOf(provider.current()); private set
    var phase by mutableStateOf(UiPhase.LOADING); private set
    var buffering by mutableStateOf(false); private set
    var isPlaying by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var bufferedMs by mutableLongStateOf(0L); private set
    var speed by mutableFloatStateOf(1f); private set
    var aspect by mutableStateOf(settings.aspectDefault); private set
    var error by mutableStateOf<ErrorInfo?>(null); private set
    var reconnectAttempt by mutableStateOf(0); private set
    var reconnectSecondsLeft by mutableStateOf(0); private set
    var audioTracks by mutableStateOf<List<TrackInfo>>(emptyList()); private set
    var subtitleTracks by mutableStateOf<List<TrackInfo>>(emptyList()); private set
    var audioLabels by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var subtitleLabels by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var selectedAudioKey by mutableStateOf<String?>(null); private set
    var selectedSubtitleKey by mutableStateOf<String?>(null); private set
    var subtitleDelayMs by mutableLongStateOf(0L); private set
    var nextPhase by mutableStateOf<NextEpisodeTiming.Phase>(NextEpisodeTiming.Phase.Hidden); private set
    var videoAspect by mutableFloatStateOf(16f / 9f); private set
    var inPip by mutableStateOf(false)
    var locked by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)
    var interactionTick by mutableLongStateOf(0L); private set
    var panelTab by mutableStateOf<PanelTab?>(null)
    var hud by mutableStateOf<String?>(null); private set
    var speedHold by mutableStateOf(false); private set
    var scrubPreviewMs by mutableStateOf<Long?>(null)
    var seekFeedback by mutableStateOf<SeekFeedback?>(null); private set
    var subtitleBusyMessage by mutableStateOf<String?>(null); private set
    var resumedToast by mutableStateOf<String?>(null); private set

    data class SeekFeedback(val forward: Boolean, val seconds: Int, val id: Long)

    // ---- engine ----
    private val delayHolder = SubtitleDelayHolder()
    private val retryPolicy = RetryPolicy()
    private val throttle = ProgressThrottle()
    private val failedSubtitleUrls = HashSet<String>()
    private val localSubtitles = ArrayList<LocalSubtitle>()
    private var strippedExternalOnce = false
    private var resolvedMimes: Map<Int, String> = emptyMap()
    private val trackRefs = HashMap<String, Pair<Tracks.Group, Int>>()
    private var started = false
    private var released = false
    private var userTouchedSub = false
    private var autoAudioDone = false
    private var lastAutoSubSignature: String? = null
    private var pendingSelectSubId: String? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0
    private var lastErrorPositionMs = 0L
    private var nextDismissed = false
    private var advanced = false
    private var speedBeforeHold = 1f
    private var hudJob: Job? = null
    private var feedbackId = 0L
    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private val streamHost = HeaderPolicy.hostOf(request.url)
    private val subtitleOnlyHosts: Set<String> = request.externalSubtitles
        .mapNotNull { HeaderPolicy.hostOf(it.url) }.toSet() - setOfNotNull(streamHost)

    private data class LocalSubtitle(val id: String, val uri: Uri, val label: String, val mime: String)

    val player: ExoPlayer
    private val session: MediaSession

    init {
        val ctx = activity.applicationContext
        val httpFactory = OkHttpDataSource.Factory(PlayerDependencies.httpClient)
        val baseFactory = DefaultDataSource.Factory(ctx, httpFactory)
        val headers = request.headers
        val dsFactory = if (headers.isEmpty()) baseFactory else ResolvingDataSource.Factory(baseFactory) { spec: DataSpec ->
            if (HeaderPolicy.shouldApply(spec.uri.host, spec.uri.scheme, streamHost, subtitleOnlyHosts)) {
                val merged = LinkedHashMap<String, String>(headers)
                merged.putAll(spec.httpRequestHeaders) // engine-set headers (Range) win
                spec.buildUpon().setHttpRequestHeaders(merged).build()
            } else spec
        }

        val renderers = object : DefaultRenderersFactory(ctx) {
            override fun buildTextRenderers(
                context: Context, output: TextOutput, outputLooper: android.os.Looper,
                extensionRendererMode: Int, out: java.util.ArrayList<Renderer>,
            ) {
                val before = out.size
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                for (i in before until out.size) out[i] = DelayedTextRenderer(out[i], delayHolder)
            }
        }.setEnableDecoderFallback(true)

        val s = settings
        player = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dsFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(s.seekStepSeconds * 1000L)
            .setSeekForwardIncrementMs(s.seekStepSeconds * 1000L)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) // subtitles stay off until our rules pick one
            .setPreferredAudioLanguages(*s.preferredAudioLanguages.toTypedArray())
            .build()
        speed = s.defaultSpeed
        player.playbackParameters = PlaybackParameters(s.defaultSpeed)
        session = MediaSession.Builder(activity, player).setId("liuying-player-${System.nanoTime()}").build()
    }

    // ---- lifecycle ----
    fun start() {
        if (started) return
        started = true
        player.addListener(listener)
        originalBrightness = activity.window.attributes.screenBrightness
        scope.launch {
            resolvedMimes = resolveUnknownSubtitleMimes()
            if (released) return@launch
            player.setMediaItem(buildItem(), request.startPositionMs.coerceAtLeast(0))
            player.playWhenReady = true
            player.prepare()
            if (request.startPositionMs > 5_000) {
                resumedToast = "Resumed at ${com.liuying.app.ui.player.logic.TimeFormat.clock(request.startPositionMs)}"
                delay(2500); resumedToast = null
            }
        }
        scope.launch {
            while (true) {
                delay(250)
                sync()
            }
        }
        scope.launch {
            while (true) {
                delay(1000)
                if (player.isPlaying) maybeReport(force = false)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        retryJob?.cancel()
        reportFinal()
        activity.window.attributes = activity.window.attributes.also { it.screenBrightness = originalBrightness }
        session.release()
        player.release()
    }

    // ---- sync / state ----
    private fun sync() {
        if (released) return
        isPlaying = player.isPlaying
        val d = player.duration
        durationMs = if (d == C.TIME_UNSET) 0L else d
        if (scrubPreviewMs == null) positionMs = player.currentPosition.coerceAtLeast(0)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0)
        if (isPlaying && retryAttempt > 0 && positionMs - lastErrorPositionMs > 5_000) retryAttempt = 0
        nextPhase = NextEpisodeTiming.phase(
            positionMs, durationMs, request.nextEpisode != null, nextDismissed, settings.autoPlayNext,
        )
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> {
                    if (phase == UiPhase.LOADING || phase == UiPhase.RECONNECTING) phase = UiPhase.READY
                    hasPlayed = true
                }
                Player.STATE_ENDED -> onEnded()
            }
            sync()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@PlayerController.isPlaying = isPlaying
            if (!isPlaying && hasPlayed) maybeReport(force = true)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && hasPlayed) maybeReport(force = true)
        }

        override fun onTracksChanged(tracks: Tracks) = refreshTracks(tracks)

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlayerError(e: PlaybackException) = handleError(e)
    }

    private var hasPlayed = false

    // ---- media item ----
    private fun activeExternal(): List<Pair<Int, ExternalSubtitle>> =
        request.externalSubtitles.withIndex()
            .filter { it.value.url !in failedSubtitleUrls && !(strippedExternalOnce) }
            .map { it.index to it.value }

    private fun buildItem(): MediaItem {
        val subs = ArrayList<MediaItem.SubtitleConfiguration>()
        for ((i, e) in activeExternal()) {
            val mime = SubtitleFormat.fromExtension(e.url) ?: resolvedMimes[i] ?: SubtitleFormat.SUBRIP
            subs += MediaItem.SubtitleConfiguration.Builder(Uri.parse(e.url))
                .setMimeType(mime)
                .setLanguage(Languages.base(e.language))
                .setLabel(e.label ?: e.sourceName)
                .setId("ext:$i")
                .build()
        }
        for (l in localSubtitles) {
            subs += MediaItem.SubtitleConfiguration.Builder(l.uri)
                .setMimeType(l.mime).setLabel(l.label).setId(l.id).build()
        }
        val lower = request.url.substringBefore('?').lowercase()
        val mime = when {
            lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".ism") || lower.endsWith(".isml") -> MimeTypes.APPLICATION_SS
            else -> null
        }
        return MediaItem.Builder()
            .setUri(request.url)
            .setMediaId(request.mediaId)
            .also { if (mime != null) it.setMimeType(mime) }
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(request.title).setSubtitle(request.subtitleLine).build(),
            )
            .setSubtitleConfigurations(subs)
            .build()
    }

    /** Probes the first bytes of external subtitles whose URL has no recognisable extension (bounded to ~2.5 s). */
    private suspend fun resolveUnknownSubtitleMimes(): Map<Int, String> {
        val unknown = request.externalSubtitles.withIndex().filter { SubtitleFormat.fromExtension(it.value.url) == null }
        if (unknown.isEmpty()) return emptyMap()
        return withTimeoutOrNull(2_500) {
            withContext(Dispatchers.IO) {
                unknown.map { (i, e) ->
                    async {
                        runCatching {
                            val req = Request.Builder().url(e.url).header("Range", "bytes=0-1023").build()
                            PlayerDependencies.httpClient.newCall(req).execute().use { r ->
                                val bytes = r.body?.source()?.let { src -> src.request(1024); src.buffer.snapshot().toByteArray() }
                                i to SubtitleFormat.sniff(String(bytes ?: ByteArray(0), Charsets.UTF_8).take(512))
                            }
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        } ?: emptyMap()
    }

    /** Re-creates the media item (e.g. after dropping a bad subtitle or adding a file) keeping position and play state. */
    private fun rebuildKeepingPosition() {
        val pos = player.currentPosition
        val play = player.playWhenReady
        pendingSelectSubId = pendingSelectSubId ?: currentSubtitleFormatId()
        player.setMediaItem(buildItem(), pos)
        player.playWhenReady = play
        player.prepare()
    }

    private fun currentSubtitleFormatId(): String? =
        selectedSubtitleKey?.let { trackRefs[it] }?.let { (g, i) -> g.getTrackFormat(i).id }

    // ---- tracks ----
    private fun refreshTracks(tracks: Tracks) {
        trackRefs.clear()
        val audio = ArrayList<TrackInfo>()
        val subs = ArrayList<TrackInfo>()
        var selAudio: String? = null
        var selSub: String? = null
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val key = "$gi:$ti"
                trackRefs[key] = g to ti
                val label = f.label
                val role = f.roleFlags
                if (g.type == C.TRACK_TYPE_AUDIO) {
                    audio += TrackInfo(
                        key, TrackKind.AUDIO, f.language, label, f.channelCount,
                        f.codecs ?: f.sampleMimeType, (f.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                    )
                    if (g.isTrackSelected(ti)) selAudio = key
                } else {
                    val id = f.id.orEmpty()
                    val extIdx = if (id.startsWith("ext:")) id.removePrefix("ext:").toIntOrNull() else null
                    val ext = extIdx?.let { request.externalSubtitles.getOrNull(it) }
                    val isLocal = id.startsWith("file:")
                    subs += TrackInfo(
                        key, TrackKind.TEXT, f.language ?: ext?.language, label ?: ext?.label, 0, null,
                        (f.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                        isForced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 || TrackLabels.looksForced(label),
                        isSdh = (role and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0 || TrackLabels.looksSdh(label),
                        isExternal = ext != null || isLocal,
                        sourceName = ext?.sourceName ?: if (isLocal) "File" else null,
                        sourceOrder = ext?.sourceOrder ?: 0,
                        hashMatched = ext?.hashMatched ?: false,
                    )
                    if (g.isTrackSelected(ti)) selSub = key
                }
            }
        }
        audioTracks = audio
        subtitleTracks = subs
        audioLabels = TrackLabels.audioLabels(audio)
        subtitleLabels = TrackLabels.subtitleLabels(subs)
        selectedAudioKey = selAudio
        selectedSubtitleKey = if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) null else selSub

        // Restore a subtitle after the media item was rebuilt / a file was added.
        pendingSelectSubId?.let { id ->
            val hit = subs.firstOrNull { trackRefs[it.key]?.let { (g, i) -> g.getTrackFormat(i).id } == id }
            if (hit != null) { pendingSelectSubId = null; applySubtitle(hit.key, remember = false) }
        }
        runAutoSelection(audio, subs, selAudio)
    }

    private fun selectionPrefs(): SelectionPrefs = SelectionPrefs(
        audioLanguages = settings.preferredAudioLanguages,
        subtitleLanguages = settings.preferredSubtitleLanguages,
        mode = when (settings.subtitleMode) {
            SubtitleMode.OFF_UNLESS_NEEDED -> AutoSubtitleMode.OFF_UNLESS_NEEDED
            SubtitleMode.ALWAYS_ON -> AutoSubtitleMode.ALWAYS_ON
            SubtitleMode.FORCED_ONLY -> AutoSubtitleMode.FORCED_ONLY
        },
        preferSdh = settings.preferSdhSubtitles,
        autoUseExternal = settings.autoFetchExternalSubtitles,
    )

    private fun runAutoSelection(audio: List<TrackInfo>, subs: List<TrackInfo>, selectedAudio: String?) {
        var effectiveAudio = selectedAudio
        if (!autoAudioDone && audio.isNotEmpty()) {
            autoAudioDone = true
            val pick = TrackSelection.chooseAudio(audio, settings.preferredAudioLanguages)
            if (pick != null && pick != selectedAudio) { applyAudio(pick); effectiveAudio = pick }
        }
        if (userTouchedSub || audio.isEmpty() || subs.isEmpty()) return
        val sig = subs.joinToString("|") { it.key + ":" + (trackRefs[it.key]?.let { (g, i) -> g.getTrackFormat(i).id }) }
        if (sig == lastAutoSubSignature) return
        lastAutoSubSignature = sig
        val prefs = selectionPrefs()
        val remembered = SubtitleMemory.get(request.mediaId)
        val choice = remembered?.let { TrackSelection.applyRemembered(subs, it.language, it.off, prefs) }
            ?: TrackSelection.chooseSubtitle(subs, TrackSelection.audioLanguage(audio, effectiveAudio), prefs)
        when (choice) {
            is SubtitleChoice.Track -> applySubtitle(choice.key, remember = false)
            SubtitleChoice.Off -> Unit // already off
        }
    }

    private fun applyAudio(key: String) {
        val (g, i) = trackRefs[key] ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
        selectedAudioKey = key
    }

    fun selectAudio(key: String) {
        autoAudioDone = true
        applyAudio(key)
    }

    private fun applySubtitle(key: String?, remember: Boolean) {
        val b = player.trackSelectionParameters.buildUpon()
        if (key == null) {
            b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val (g, i) = trackRefs[key] ?: return
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        }
        player.trackSelectionParameters = b.build()
        selectedSubtitleKey = key
        if (remember) {
            val lang = key?.let { k -> subtitleTracks.firstOrNull { it.key == k }?.language }
            SubtitleMemory.put(request.mediaId, SubtitleMemory.Entry(lang, key == null))
        }
    }

    /** User choice from the sheet: overrides the rules and is remembered by language for this title. */
    fun selectSubtitle(key: String?) {
        userTouchedSub = true
        applySubtitle(key, remember = true)
    }

    fun addSubtitleFile(uri: Uri) {
        scope.launch {
            subtitleBusyMessage = "Loading subtitle..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "Subtitle file"
                    val mime = SubtitleFormat.fromExtension(name) ?: activity.contentResolver.openInputStream(uri)?.use { ins ->
                        val buf = ByteArray(1024); val n = ins.read(buf).coerceAtLeast(0)
                        SubtitleFormat.sniff(String(buf, 0, n, Charsets.UTF_8))
                    } ?: SubtitleFormat.SUBRIP
                    Triple(name, mime, uri)
                }.getOrNull()
            }
            if (result == null) { subtitleBusyMessage = "This subtitle couldn't be read"; delay(2500); subtitleBusyMessage = null; return@launch }
            val id = "file:${localSubtitles.size}"
            localSubtitles += LocalSubtitle(id, result.third, result.first, result.second)
            userTouchedSub = true
            pendingSelectSubId = id
            rebuildKeepingPosition()
            pendingSelectSubId = id
            subtitleBusyMessage = null
        }
    }

    // ---- delay ----
    fun adjustSubtitleDelay(deltaMs: Long) = setSubtitleDelay(subtitleDelayMs + deltaMs)
    fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms.coerceIn(-30_000, 30_000)
        delayHolder.delayMs = subtitleDelayMs
    }

    // ---- transport ----
    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0); player.play() }
        else if (player.isPlaying || player.playWhenReady) player.pause() else player.play()
        poke()
    }
    fun play() = player.play()
    fun pause() { player.pause() }
    fun seekTo(ms: Long) {
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        player.seekTo(ms.coerceIn(0, max)); positionMs = ms.coerceIn(0, max); poke()
    }
    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)
    fun seekStep(forward: Boolean) {
        val sec = settings.seekStepSeconds
        seekBy(if (forward) sec * 1000L else -sec * 1000L)
        seekFeedback = SeekFeedback(forward, sec, ++feedbackId)
    }
    fun setSpeed(v: Float) {
        speed = v.coerceIn(0.25f, 3f)
        player.playbackParameters = PlaybackParameters(speed)
    }
    fun beginSpeedHold() { speedBeforeHold = speed; speedHold = true; player.playbackParameters = PlaybackParameters(2f) }
    fun endSpeedHold() { if (speedHold) { speedHold = false; player.playbackParameters = PlaybackParameters(speedBeforeHold) } }

    fun cycleAspect() {
        aspect = AspectMode.entries[(aspect.ordinal + 1) % AspectMode.entries.size]
        showHud("Aspect: ${aspect.label}")
        saveSettings(settings.copy(aspectDefault = aspect))
    }

    fun poke() { interactionTick++ }

    // ---- settings ----
    /** Live-applies a settings change; pass persist=false while a slider drags and call [persistSettings] at the end. */
    fun updateSettings(persist: Boolean = true, transform: (PlayerSettings) -> PlayerSettings) {
        settings = transform(settings)
        if (persist) persistSettings()
    }
    fun persistSettings() { runCatching { provider.save(settings) } }
    private fun saveSettings(s: PlayerSettings) { settings = s; persistSettings() }

    fun showHud(text: String) {
        hud = text
        hudJob?.cancel()
        hudJob = scope.launch { delay(900); hud = null }
    }

    // ---- brightness / volume ----
    fun currentBrightness(): Float {
        val w = activity.window.attributes.screenBrightness
        if (w >= 0) return w
        return runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }
    fun setBrightness(v: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = v.coerceIn(0.01f, 1f)
        activity.window.attributes = lp
        showHud("Brightness ${(v * 100).toInt()}%")
    }
    private val audioManager get() = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    fun currentVolume(): Float {
        val am = audioManager
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }
    fun setVolume(v: Float) {
        val am = audioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).toInt(), 0)
        showHud("Volume ${(v * 100).toInt()}%")
    }

    // ---- errors ----
    private fun findHttpStatus(t: Throwable?): Int? {
        var c = t
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode
            c = c.cause
        }
        return null
    }

    private fun failedSubtitle(t: Throwable?): ExternalSubtitle? {
        var c = t
        while (c != null) {
            if (c is HttpDataSource.HttpDataSourceException) {
                val u = c.dataSpec.uri.toString()
                request.externalSubtitles.firstOrNull { it.url == u }?.let { return it }
            }
            c = c.cause
        }
        return null
    }

    private fun handleError(e: PlaybackException) {
        lastErrorPositionMs = player.currentPosition
        // A dead subtitle URL must not kill playback: drop it and carry on from the same position.
        failedSubtitle(e)?.let {
            failedSubtitleUrls += it.url
            showHud("A subtitle failed to load")
            rebuildKeepingPosition()
            return
        }
        val status = findHttpStatus(e)
        val kind = ErrorClassifier.classify(e.errorCode, status)
        if ((kind == ErrorKind.MALFORMED || kind == ErrorKind.OTHER) && !strippedExternalOnce &&
            request.externalSubtitles.any { it.url !in failedSubtitleUrls }
        ) {
            // Could be an unreadable subtitle; retry once without external subtitles.
            strippedExternalOnce = true
            rebuildKeepingPosition()
            return
        }
        when (kind) {
            ErrorKind.BEHIND_LIVE_WINDOW -> { player.seekToDefaultPosition(); player.prepare() }
            ErrorKind.NETWORK -> scheduleRetry(e)
            else -> showError(kind, e)
        }
    }

    private fun scheduleRetry(e: PlaybackException) {
        val d = retryPolicy.delayFor(retryAttempt)
        if (d == null) { showError(ErrorKind.NETWORK, e); return }
        retryAttempt++
        reconnectAttempt = retryAttempt
        phase = UiPhase.RECONNECTING
        retryJob?.cancel()
        retryJob = scope.launch {
            var left = d
            while (left > 0) {
                reconnectSecondsLeft = ((left + 999) / 1000).toInt()
                val step = minOf(left, 500L)
                delay(step); left -= step
            }
            player.prepare()
        }
    }

    private fun showError(kind: ErrorKind, e: PlaybackException) {
        retryJob?.cancel()
        phase = UiPhase.ERROR
        error = ErrorInfo(kind, ErrorClassifier.title(kind), ErrorClassifier.message(kind), e.errorCodeName)
        maybeReport(force = true)
    }

    /** Retry button: reset the backoff budget and re-prepare from the current position. */
    fun retry() {
        retryAttempt = 0
        error = null
        phase = UiPhase.LOADING
        player.prepare()
        player.play()
    }

    // ---- end / next ----
    private fun onEnded() {
        maybeReport(force = true, atEnd = true)
        val next = request.nextEpisode
        if (next != null && settings.autoPlayNext && !nextDismissed) playNextNow()
        else if (next == null) activity.onPlayerFinished(PlayerExit.Closed)
    }

    fun dismissNext() { nextDismissed = true; nextPhase = NextEpisodeTiming.Phase.Hidden }

    fun playNextNow() {
        val next = request.nextEpisode ?: return
        if (advanced) return
        advanced = true
        maybeReport(force = true, atEnd = player.playbackState == Player.STATE_ENDED)
        activity.onPlayerFinished(PlayerExit.PlayNext(request.mediaId, next.episodeId))
    }

    fun tryAnotherStream() {
        maybeReport(force = true)
        activity.onPlayerFinished(PlayerExit.TryAnotherStream(request.mediaId, request.episodeId, player.currentPosition))
    }

    // ---- progress ----
    private fun maybeReport(force: Boolean, atEnd: Boolean = false) {
        if (released || !hasPlayed) return
        val dur = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        val pos = if (atEnd && dur > 0) dur else player.currentPosition.coerceAtLeast(0)
        if (!throttle.shouldReport(System.currentTimeMillis(), pos, force)) return
        dispatchReport(pos, dur)
    }

    private fun reportFinal() {
        if (!hasPlayed) return
        val dur = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        val ended = player.playbackState == Player.STATE_ENDED
        val pos = if (ended && dur > 0) dur else player.currentPosition.coerceAtLeast(0)
        dispatchReport(pos, dur) // unconditional: leaving must always persist
    }

    private fun dispatchReport(pos: Long, dur: Long) {
        val reporter = PlayerDependencies.progressReporter
        val r = request
        PlayerDependencies.appScope.launch {
            runCatching { reporter.report(r.mediaId, r.episodeId, pos, dur) }
        }
    }

    fun onBackgrounded() { maybeReport(force = true) }
}

enum class PanelTab(val title: String) { AUDIO("Audio"), SUBTITLES("Subtitles"), STYLE("Style"), TIMING("Timing"), SPEED("Speed") }
