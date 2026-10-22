package com.liuying.app.domain.model

import com.liuying.app.data.remote.AddonUrls

/** An installed source (addon). */
data class Source(
    val id: Long,
    val manifestUrl: String,
    val manifest: Manifest,
    val enabled: Boolean,
    val order: Int,
    val installedAt: Long,
    val fetchedAt: Long,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    val nameOverride: String? = null,
) {
    val displayName: String get() = nameOverride?.takeIf { it.isNotBlank() } ?: manifest.name.ifBlank { host }
    val baseUrl: String get() = AddonUrls.baseUrl(manifestUrl)
    val host: String get() = AddonUrls.host(manifestUrl)
    val isHttp: Boolean get() = manifestUrl.startsWith("http://", ignoreCase = true)
    /** Source supplies something the app can use. */
    val needsConfiguration: Boolean get() = manifest.behaviorHints.configurationRequired
}

data class CatalogPref(
    val sourceId: Long,
    val type: String,
    val catalogId: String,
    val showOnHome: Boolean,
    val showInBrowse: Boolean,
)

/** One catalog of one source. [key] is stable across restarts and used for layout/preferences. */
data class CatalogEntry(val source: Source, val catalog: ManifestCatalog) {
    val key: String get() = catalogKey(source.id, catalog.type, catalog.id)
    /** Key shared with the web player's layout settings. */
    val webKey: String get() = com.liuying.app.domain.Ux.webCatalogKey(source.manifestUrl, catalog.type, catalog.id)
    val title: String get() = "${catalog.displayName} - ${catalog.type.replaceFirstChar { it.uppercase() }}"
}

fun catalogKey(sourceId: Long, type: String, catalogId: String) = "$sourceId:$type:$catalogId"

data class LibraryItem(
    val type: String,
    val id: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val year: String?,
    val genres: List<String>,
    val addedAt: Long,
)

/** Progress record. Movies use [NO_EPISODE] for season/episode. */
data class ProgressEntry(
    val type: String,
    val titleId: String,
    val season: Int,
    val episode: Int,
    /** The exact video id sent to stream sources (e.g. tt123:1:2, or the movie id). */
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long,
    val streamHint: String? = null,
) {
    val isEpisode: Boolean get() = season != NO_EPISODE && episode != NO_EPISODE
    val fraction: Double get() = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    companion object {
        const val NO_EPISODE = -1
    }
}

/** Identifies what is being played, for saving progress. */
data class PlaybackRef(
    val type: String,
    val titleId: String,
    val videoId: String,
    val season: Int = ProgressEntry.NO_EPISODE,
    val episode: Int = ProgressEntry.NO_EPISODE,
) {
    companion object {
        fun movie(type: String, id: String) = PlaybackRef(type, id, id)
        fun episode(type: String, titleId: String, videoId: String, season: Int, episode: Int) =
            PlaybackRef(type, titleId, videoId, season, episode)
    }
}

/** A source-attributed failure, never thrown. */
data class SourceError(val sourceId: Long, val sourceName: String, val message: String)
