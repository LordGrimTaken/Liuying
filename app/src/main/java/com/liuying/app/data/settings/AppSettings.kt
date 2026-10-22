package com.liuying.app.data.settings

enum class ThemeMode { System, Dark, Light, PureBlack }
enum class PosterSize { Small, Medium, Large }
enum class ResumeBehavior { Auto, Ask, StartOver }
enum class StreamSelectionMode { ShowList, AutoSelect }
enum class PreferredResolution(val height: Int?) { Any(null), UHD4K(2160), FHD1080(1080), HD720(720), SD480(480) }
enum class StreamGrouping { BySource, FlatSorted }
enum class StreamSort { SourceOrder, Resolution, Size, Name }
enum class AspectMode { Fit, Fill, Stretch, Ratio16x9, Ratio4x3 }
enum class OrientationMode { LandscapeLocked, AutoRotate }
enum class HardwareDecoding { Auto, PreferHardware, SoftwareOnly }
enum class ExternalPlayerMode { Never, Ask, Always }
enum class SubtitleMode { OffUnlessNeeded, AlwaysOn, ForcedOnly }
enum class TimeoutProfile(val multiplier: Double) { Normal(1.0), Patient(2.0) }
enum class LibrarySort { RecentlyAdded, TitleAZ, Year, LastWatched, UnwatchedFirst }
enum class LibraryView { Grid, List }
enum class RowItemCount(val count: Int) { Short(12), Normal(24), Long(40) }

/** All user preferences with the defaults from spec 07. */
data class AppSettings(
    // Appearance
    val theme: ThemeMode = ThemeMode.Dark,
    val accent: String = "gold",
    /** "system", "en" or "zh-Hans". */
    val language: String = "system",
    val posterSize: PosterSize = PosterSize.Medium,
    val titlesUnderPosters: Boolean = true,
    val showBadges: Boolean = true,
    val reduceMotion: Boolean = false,
    // Home layout
    val showContinueWatching: Boolean = true,
    val showUpNext: Boolean = true,
    val heroEnabled: Boolean = true,
    /** CatalogEntry.key feeding the hero; null = first eligible catalog. */
    val heroCatalogKey: String? = null,
    val hideAdultCatalogs: Boolean = true,
    val rowItemCount: RowItemCount = RowItemCount.Normal,
    // Playback
    val resumeBehavior: ResumeBehavior = ResumeBehavior.Auto,
    /** 0.80 - 0.98 */
    val completionThreshold: Double = 0.90,
    val autoPlayNext: Boolean = true,
    val nextEpisodeCountdownSec: Int = 10,
    val askBeforeNext: Boolean = false,
    val streamSelection: StreamSelectionMode = StreamSelectionMode.ShowList,
    val preferredResolution: PreferredResolution = PreferredResolution.Any,
    val preferredLanguageWords: List<String> = emptyList(),
    val excludedWords: List<String> = emptyList(),
    /** null = no limit */
    val maxSizeGb: Double? = null,
    val preferHdr: Boolean = false,
    val autoSelectWaitSec: Int = 3,
    val preferredSourceId: Long? = null,
    val streamGrouping: StreamGrouping = StreamGrouping.BySource,
    val streamSort: StreamSort = StreamSort.SourceOrder,
    val showRawStreamDescription: Boolean = true,
    val hideNonPlayableStreams: Boolean = false,
    val seekStepSec: Int = 10,
    val gestureBrightness: Boolean = true,
    val gestureVolume: Boolean = true,
    val gestureSpeedHold: Boolean = true,
    val gesturePinchZoom: Boolean = true,
    val defaultAspect: AspectMode = AspectMode.Fit,
    val defaultSpeed: Float = 1.0f,
    val resetSpeedEachTime: Boolean = false,
    val orientation: OrientationMode = OrientationMode.LandscapeLocked,
    val keepScreenOn: Boolean = true,
    val pauseOnHeadphoneUnplug: Boolean = true,
    val hardwareDecoding: HardwareDecoding = HardwareDecoding.Auto,
    val allowMobileData: Boolean = true,
    val externalPlayer: ExternalPlayerMode = ExternalPlayerMode.Never,
    // Audio and subtitles (empty = follow device language)
    val preferredAudioLanguages: List<String> = emptyList(),
    val preferredSubtitleLanguages: List<String> = emptyList(),
    val subtitleMode: SubtitleMode = SubtitleMode.OffUnlessNeeded,
    val autoFetchSubtitles: Boolean = true,
    val subtitleSizeScale: Float = 1.0f,
    val subtitleColor: Long = 0xFFFFFFFF,
    val subtitleOutline: Boolean = true,
    val subtitleBackground: Boolean = false,
    /** 0 (top) .. 100 (bottom) */
    val subtitleBottomPercent: Int = 8,
    // Sources and content
    val timeoutProfile: TimeoutProfile = TimeoutProfile.Normal,
    val allowInsecureHttp: Boolean = true,
    val hideTitlesWithoutPoster: Boolean = false,
    val trailerEnabled: Boolean = true,
    val trailerAutoplayHero: Boolean = false,
    val spoilerProtection: Boolean = false,
    val metadataApiKey: String = "",
    // Library and history
    val saveWatchHistory: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.RecentlyAdded,
    val libraryView: LibraryView = LibraryView.Grid,
    val recentSearchesEnabled: Boolean = true,
    // Scraper plugins: global off switch (default off)
    val pluginsEnabled: Boolean = false,
    // Storage
    val imageCacheLimitMb: Int = 512,
    val schemaVersion: Int = CURRENT_SCHEMA,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}
