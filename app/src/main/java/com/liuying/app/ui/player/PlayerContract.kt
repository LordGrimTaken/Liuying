package com.liuying.app.ui.player

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val autoPlayNext: Boolean = true,
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

    /** Invoked (main thread) whenever PlayerActivity finishes. */
    @Volatile var onExit: ((PlayerExit) -> Unit)? = null

    /** Shared client for stream and subtitle traffic; replace to share a pool/cache with the app. */
    @Volatile var httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Process-wide scope used for final progress reports that must outlive the activity. */
    internal val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
