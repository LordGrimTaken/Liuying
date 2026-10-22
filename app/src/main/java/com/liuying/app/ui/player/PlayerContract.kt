package com.liuying.app.ui.player

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Public contract of the player feature. The rest of the app only needs this file:
 * build a [PlaybackRequest], call [PlayerLauncher.start], optionally inject
 * [PlayerDependencies.progressReporter] / [PlayerDependencies.settingsProvider] at app start.
 */

/** A subtitle file offered by a source (or attached to a stream). [language] is a 2/3-letter code or null. */
@Serializable
data class ExternalSubtitle(
    val url: String,
    val language: String?,
    val label: String?,
    val sourceName: String?,
    /** Order of the providing source in the user's source list (lower is better). */
    val sourceOrder: Int = 0,
    /** True when the match was made by video hash rather than by name. */
    val hashMatched: Boolean = false,
)

@Serializable
data class NextEpisodeInfo(val episodeId: String, val title: String)

@Serializable
data class PlaybackRequest(
    val mediaId: String,
    /** "movie" | "series" | ... (the addon-protocol type). */
    val type: String,
    val episodeId: String?,
    val title: String,
    /** Secondary line such as "S1 E4 - Episode title". */
    val subtitleLine: String?,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val startPositionMs: Long = 0L,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val nextEpisode: NextEpisodeInfo? = null,
    /** L044: the episode before this one, for the previous button. */
    val previousEpisode: NextEpisodeInfo? = null,
    /** The video id the sources were asked for (episode id, or the movie's video id); lets the player ask again for a stream switch. */
    val videoId: String? = null,
    /** The chosen stream's binge group, so the next episode can pick a matching stream. */
    val bingeGroup: String? = null,
)

/** Called by the player roughly every 10 s while playing and on pause/seek/stop/exit. */
interface ProgressReporter {
    suspend fun report(mediaId: String, episodeId: String?, positionMs: Long, durationMs: Long)
}

enum class AspectMode(val label: String) { FIT("Fit"), FILL("Stretch"), ZOOM("Crop") }
enum class SubtitleMode { OFF_UNLESS_NEEDED, ALWAYS_ON, FORCED_ONLY }
enum class SubtitleEdge { NONE, OUTLINE, DROP_SHADOW, RAISED }
enum class SubtitleBackground { NONE, BOX }

data class PlayerSettings(
    /** Ordered language codes (2- or 3-letter). */
    val preferredAudioLanguages: List<String> = listOf("en"),
    val preferredSubtitleLanguages: List<String> = listOf("en"),
    val subtitleMode: SubtitleMode = SubtitleMode.OFF_UNLESS_NEEDED,
    val preferSdhSubtitles: Boolean = false,
    val autoFetchExternalSubtitles: Boolean = true,
    /** 50..200 */
    val subtitleSizePercent: Int = 100,
    val subtitleBold: Boolean = false,
    /** ARGB */
    val subtitleColor: Int = 0xFFFFFFFF.toInt(),
    val subtitleBackground: SubtitleBackground = SubtitleBackground.NONE,
    /** 0..100, opacity of the box when [subtitleBackground] is BOX. */
    val subtitleBackgroundOpacityPercent: Int = 60,
    val subtitleEdge: SubtitleEdge = SubtitleEdge.OUTLINE,
    /** ARGB colour of the outline/shadow. */
    val subtitleEdgeColor: Int = 0xFF000000.toInt(),
    /** Offset from the bottom as percent of height, 0..40. */
    val subtitleBottomOffsetPercent: Int = 8,
    val defaultSpeed: Float = 1.0f,
    val aspectDefault: AspectMode = AspectMode.FIT,
    /** Master switch; the four below are individually toggleable and only apply when this is true. */
    val gesturesEnabled: Boolean = true,
    val doubleTapSeekEnabled: Boolean = true,
    val swipeBrightnessVolumeEnabled: Boolean = true,
    val swipeScrubEnabled: Boolean = true,
    val longPressSpeedEnabled: Boolean = true,
    val seekStepSeconds: Int = 10,
    /** L042: the +85 s style button. */
    val longSkipSeconds: Int = 85,
    val autoPlayNext: Boolean = true,
    /** Seconds of the auto-advance countdown (5..30). */
    val nextCountdownSec: Int = 10,
    /** When true the next episode is only offered ("Play now"); it never starts by itself. */
    val askBeforeNext: Boolean = false,
    val pauseOnHeadphoneUnplug: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** False = locked to sensor landscape; true = follows the device rotation. */
    val autoRotate: Boolean = false,
    val pipOnHome: Boolean = true,
)

/**
 * Supplies settings. [current] is called on the main thread when the player opens, so it must be cheap
 * (serve a cached value). [save] is called when the user changes style/speed/aspect inside the player.
 */
interface PlayerSettingsProvider {
    fun current(): PlayerSettings
    fun save(settings: PlayerSettings) {}
}

/** How the player activity ended; delivered as activity result and to [PlayerDependencies.onExit]. */
sealed interface PlayerExit {
    /** Normal close (back, or ended with no next episode). */
    data object Closed : PlayerExit
    /** User asked to try another stream (error dialog); the caller should show its streams list. */
    data class TryAnotherStream(val mediaId: String, val episodeId: String?, val positionMs: Long) : PlayerExit
    /** Next-episode overlay finished or "Play now" was pressed. */
    data class PlayNext(val mediaId: String, val nextEpisodeId: String) : PlayerExit
}

/** One stream offered inside the player (L045). */
data class StreamChoice(
    val key: String,
    val title: String,
    val source: String,
    val badges: List<String>,
    val detail: String?,
    val playable: Boolean,
    val resolutionHeight: Int,
    val sizeBytes: Long,
    val url: String?,
)

data class StreamsSnapshot(val choices: List<StreamChoice>, val loading: Boolean, val failedSources: Int)

/**
 * What the player needs from the app to switch streams or episodes without leaving the screen (L044, L045).
 * Null in [PlayerDependencies.gateway] hides those controls.
 */
interface PlaybackGateway {
    fun streams(request: PlaybackRequest): kotlinx.coroutines.flow.Flow<StreamsSnapshot>
    /** The same title and episode through another stream, starting at [startMs]; null if it cannot be played. */
    suspend fun requestForChoice(base: PlaybackRequest, key: String, startMs: Long): PlaybackRequest?
    /** Another episode with a stream picked automatically (same binge group first); null if no stream was found within [waitMs]. */
    suspend fun requestForEpisode(base: PlaybackRequest, episodeId: String, waitMs: Long): PlaybackRequest?
}

/** Remembers the audio and subtitle language chosen for a series (L047). Keys are opaque to the player. */
interface TrackMemoryStore {
    fun get(key: String): com.liuying.app.ui.player.logic.TrackChoice?
    fun put(key: String, choice: com.liuying.app.ui.player.logic.TrackChoice)
}

internal class InMemoryTrackMemory : TrackMemoryStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, com.liuying.app.ui.player.logic.TrackChoice>()
    override fun get(key: String) = map[key]
    override fun put(key: String, choice: com.liuying.app.ui.player.logic.TrackChoice) { map[key] = choice }
}

/** App-level injection point. Defaults are no-ops so the player works standalone. */
object PlayerDependencies {
    @Volatile var progressReporter: ProgressReporter = object : ProgressReporter {
        override suspend fun report(mediaId: String, episodeId: String?, positionMs: Long, durationMs: Long) {}
    }

    @Volatile var settingsProvider: PlayerSettingsProvider = object : PlayerSettingsProvider {
        private var value = PlayerSettings()
        override fun current(): PlayerSettings = value
        override fun save(settings: PlayerSettings) { value = settings }
    }

    @Volatile var gateway: PlaybackGateway? = null

    @Volatile var trackMemory: TrackMemoryStore = InMemoryTrackMemory()

    /** Invoked (main thread) whenever PlayerActivity finishes. */
    @Volatile var onExit: ((PlayerExit) -> Unit)? = null

    /**
     * Invoked (main thread) when a player starts, including when Android recreates the activity after the process died,
     * so the app can re-register whatever it needs to attribute progress reports to the right title.
     */
    @Volatile var onPlaybackStarting: ((PlaybackRequest) -> Unit)? = null

    /** Shared client for stream and subtitle traffic; replace to share a pool/cache with the app. */
    @Volatile var httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Process-wide scope used for final progress reports that must outlive the activity. */
    internal val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val reportQueue = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init {
        // One consumer: reports are applied strictly in the order the player produced them (a late 10 s tick can no
        // longer overwrite the final position, and read-modify-write in the repository never interleaves).
        appScope.launch { for (job in reportQueue) runCatching { job() } }
    }

    internal fun enqueueReport(job: suspend () -> Unit) { reportQueue.trySend(job) }
}

object PlayerLauncher {
    internal const val EXTRA_REQUEST = "com.liuying.app.player.REQUEST"
    internal const val EXTRA_EXIT_KIND = "exit_kind"
    internal const val EXTRA_EXIT_MEDIA = "exit_media"
    internal const val EXTRA_EXIT_EPISODE = "exit_episode"
    internal const val EXTRA_EXIT_POSITION = "exit_position"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun intent(context: Context, request: PlaybackRequest): Intent =
        Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_REQUEST, json.encodeToString(request))

    fun start(context: Context, request: PlaybackRequest) {
        val i = intent(context, request)
        if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    internal fun decode(intent: Intent): PlaybackRequest? =
        intent.getStringExtra(EXTRA_REQUEST)?.let { runCatching { json.decodeFromString<PlaybackRequest>(it) }.getOrNull() }

    /** For `rememberLauncherForActivityResult(PlayerLauncher.Contract) { exit -> ... }`. */
    object Contract : ActivityResultContract<PlaybackRequest, PlayerExit>() {
        override fun createIntent(context: Context, input: PlaybackRequest): Intent = intent(context, input)
        override fun parseResult(resultCode: Int, intent: Intent?): PlayerExit {
            intent ?: return PlayerExit.Closed
            val media = intent.getStringExtra(EXTRA_EXIT_MEDIA) ?: return PlayerExit.Closed
            return when (intent.getStringExtra(EXTRA_EXIT_KIND)) {
                "try_another" -> PlayerExit.TryAnotherStream(
                    media, intent.getStringExtra(EXTRA_EXIT_EPISODE), intent.getLongExtra(EXTRA_EXIT_POSITION, 0L),
                )
                "next" -> PlayerExit.PlayNext(media, intent.getStringExtra(EXTRA_EXIT_EPISODE) ?: return PlayerExit.Closed)
                else -> PlayerExit.Closed
            }
        }
    }
}
