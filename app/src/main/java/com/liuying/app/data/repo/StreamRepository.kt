package com.liuying.app.data.repo

import com.liuying.app.data.remote.AddonClient
import com.liuying.app.data.remote.AddonErrorKind
import com.liuying.app.data.remote.AddonFailure
import com.liuying.app.data.remote.toUserMessage
import com.liuying.app.data.settings.StreamSort
import com.liuying.app.domain.QualityInfo
import com.liuying.app.domain.StreamKind
import com.liuying.app.domain.StreamQualityParser
import com.liuying.app.domain.model.Resources
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.Stream
import com.liuying.app.domain.model.Subtitle
import com.liuying.app.domain.model.declares
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** A stream as presented in the picker. */
data class StreamItem(
    val source: Source,
    val stream: Stream,
    /** Position within the source's own answer (preserves source-provided order). */
    val index: Int,
    val kind: StreamKind,
    val quality: QualityInfo,
    /** False for torrent-hash-only (until P2) and unsupported kinds: shown disabled with [disabledReason]. */
    val enabled: Boolean,
    val disabledReason: String?,
    /** Primary line: the source's name, line breaks removed. */
    val displayName: String,
    /** Secondary text: the source's description (else title); never hidden by badge parsing. */
    val detail: String?,
) {
    val key: String get() = "${source.id}:$index"
    val url: String? get() = stream.url
    val requestHeaders: Map<String, String> get() = stream.requestHeaders
}

sealed interface SourceStreamsState {
    data object Loading : SourceStreamsState
    data class Loaded(val items: List<StreamItem>) : SourceStreamsState
    data class Failed(val message: String, val kind: AddonErrorKind?) : SourceStreamsState
}

data class SourceStreams(val source: Source, val state: SourceStreamsState)

/** Progressive stream results grouped by source (in source order). */
data class StreamsResult(val groups: List<SourceStreams>, val pending: Int) {
    val done: Boolean get() = pending == 0
    val items: List<StreamItem> get() = groups.flatMap { (it.state as? SourceStreamsState.Loaded)?.items.orEmpty() }
    val failures: List<SourceStreams> get() = groups.filter { it.state is SourceStreamsState.Failed }
    val isEmpty: Boolean get() = done && items.isEmpty()
    /** Every contacted source failed (as opposed to answering with nothing). */
    val allFailed: Boolean get() = done && groups.isNotEmpty() && groups.all { it.state is SourceStreamsState.Failed }
    fun playable(): List<StreamItem> = items.filter { it.enabled }
}

class StreamRepository(
    private val sources: SourceProvider,
    private val client: AddonClient,
) {
    private val sessionStamp = HashMap<String, Long>()

    /** Session-only cache: "type|videoId" -> sourceId -> items. Never persisted. */
    private val session = object : LinkedHashMap<String, ConcurrentHashMap<Long, List<StreamItem>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConcurrentHashMap<Long, List<StreamItem>>>?) = size > 40
    }

    internal fun candidates(all: List<Source>, type: String, videoId: String): List<Source> =
        all.filter { it.enabled && it.manifest.declares(Resources.STREAM, type, videoId) }

    /**
     * Queries every matching enabled source in parallel and emits a snapshot per arrival. Sources that already
     * answered this session are served from memory (so calling again after a failure retries only the failed
     * sources); [force] re-queries all. Grouping order = user source order; unhealthy sources are queried last.
     */
    fun streams(type: String, videoId: String, force: Boolean = false): Flow<StreamsResult> = channelFlow {
        val cands = candidates(sources.sources.first(), type, videoId)
        val cacheKey = "$type|$videoId"
        val cache = synchronized(session) {
            val nowMs = System.currentTimeMillis()
            // links from a stream source can expire (debrid, signed urls): an old answer is asked for again
            if (force || nowMs - (sessionStamp[cacheKey] ?: nowMs) > SESSION_TTL_MS) { session.remove(cacheKey); sessionStamp.remove(cacheKey) }
            sessionStamp.putIfAbsent(cacheKey, nowMs)
            session.getOrPut(cacheKey) { ConcurrentHashMap() }
        }
        val states = LinkedHashMap<Long, SourceStreamsState>()
        cands.forEach { s -> states[s.id] = cache[s.id]?.let { SourceStreamsState.Loaded(it) } ?: SourceStreamsState.Loading }
        val mutex = Mutex()
        suspend fun publish() = send(
            StreamsResult(
                groups = cands.map { SourceStreams(it, states.getValue(it.id)) },
                pending = states.values.count { it is SourceStreamsState.Loading },
            ),
        )
        mutex.withLock { publish() }
        val toQuery = cands.filter { states[it.id] is SourceStreamsState.Loading }
            .sortedBy { if (client.health.isUnhealthy(it.baseUrl)) 1 else 0 }
        coroutineScope {
            for (s in toQuery) launch {
                val r = client.streams(s.baseUrl, type, videoId, force)
                val st: SourceStreamsState = r.fold(
                    onSuccess = { resp ->
                        val items = resp.streams.mapIndexed { i, st -> toItem(s, st, i) }
                        cache[s.id] = items
                        SourceStreamsState.Loaded(items)
                    },
                    onFailure = { SourceStreamsState.Failed(it.toUserMessage(), (it as? AddonFailure)?.kind) },
                )
                mutex.withLock { states[s.id] = st; publish() }
            }
        }
    }.conflate()

    /** Subtitles offered by sources for a video, with the source they came from. */
    data class SubtitleOffer(val source: Source, val subtitle: Subtitle)

    /**
     * External subtitles from every enabled source that declares `subtitles` for this type/id, appended as each
     * source answers. [videoHash]/[videoSize]/[filename] are sent as extras when known (from stream behaviorHints).
     */
    fun subtitles(
        type: String,
        videoId: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): Flow<List<SubtitleOffer>> = channelFlow {
        val cands = sources.sources.first().filter { it.enabled && it.manifest.declares(Resources.SUBTITLES, type, videoId) }
        val extras = LinkedHashMap<String, String>()
        videoHash?.let { extras["videoHash"] = it }
        videoSize?.let { extras["videoSize"] = it.toString() }
        filename?.let { extras["filename"] = it }
        val results = arrayOfNulls<List<SubtitleOffer>>(cands.size)
        val mutex = Mutex()
        send(emptyList())
        coroutineScope {
            cands.forEachIndexed { i, s ->
                launch {
                    val r = client.subtitles(s.baseUrl, type, videoId, extras)
                    mutex.withLock {
                        results[i] = r.getOrNull()?.subtitles?.filter { it.url.isNotBlank() }?.map { SubtitleOffer(s, it) }.orEmpty()
                        send(results.filterNotNull().flatten())
                    }
                }
            }
        }
    }.conflate()

    companion object {
        private const val SESSION_TTL_MS = 10 * 60 * 1000L

        fun toItem(source: Source, stream: Stream, index: Int): StreamItem {
            val kind = StreamQualityParser.kindOf(stream)
            val enabled = kind != StreamKind.Torrent && kind != StreamKind.Unsupported
            val reason = when (kind) {
                StreamKind.Torrent -> "Needs a torrent or debrid service, which is not available yet."
                StreamKind.Unsupported -> "This kind of stream is not supported."
                else -> null
            }
            val rawName = stream.name?.takeIf { it.isNotBlank() }
            val rawDetail = stream.description?.takeIf { it.isNotBlank() } ?: stream.title?.takeIf { it.isNotBlank() }
            val name = clean(rawName ?: rawDetail?.lineSequence()?.firstOrNull() ?: "Stream")
            val detail = if (rawName == null && rawDetail != null) rawDetail.lines().drop(1).joinToString("\n").ifBlank { null } else rawDetail
            return StreamItem(
                source = source, stream = stream, index = index, kind = kind, quality = StreamQualityParser.parse(stream),
                enabled = enabled, disabledReason = reason, displayName = name, detail = detail,
            )
        }

        private fun clean(s: String) = s.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()

        /** Sorting inside the flat view or a group. Stable: ties keep the source-provided order. */
        fun sort(items: List<StreamItem>, sort: StreamSort): List<StreamItem> = when (sort) {
            StreamSort.SourceOrder -> items
            StreamSort.Resolution -> items.sortedByDescending { it.quality.resolution?.height ?: 0 }
            StreamSort.Size -> items.sortedByDescending { it.quality.sizeBytes ?: 0L }
            StreamSort.Name -> items.sortedBy { it.displayName.lowercase() }
            StreamSort.ResolutionThenSize -> items.sortedWith(
                compareByDescending<StreamItem> { it.quality.resolution?.height ?: 0 }.thenByDescending { it.quality.sizeBytes ?: 0L },
            )
        }
    }
}
