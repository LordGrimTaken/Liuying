package com.liuying.app.ui.details

import com.liuying.app.AppContainer
import com.liuying.app.data.repo.StreamItem
import com.liuying.app.data.repo.StreamRepository
import com.liuying.app.data.repo.StreamsResult
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.domain.EpisodeLabels
import com.liuying.app.domain.EpisodeLogic
import com.liuying.app.domain.EpisodeState
import com.liuying.app.domain.ProgressRules
import com.liuying.app.domain.StreamKind
import com.liuying.app.domain.StreamPicker
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.model.PlaybackRef
import com.liuying.app.ui.player.ExternalSubtitle
import com.liuying.app.ui.player.NextEpisodeInfo
import com.liuying.app.ui.player.PlaybackGateway
import com.liuying.app.ui.player.PlaybackRequest
import com.liuying.app.ui.player.StreamChoice
import com.liuying.app.ui.player.StreamsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Builds a [PlaybackRequest] for a chosen stream: subtitles from the sources, neighbouring episodes, progress registration. */
class PlaybackBuilder(private val c: AppContainer) {

    suspend fun build(
        type: String, mediaId: String, title: String, target: StreamTarget, item: StreamItem,
        states: List<EpisodeState>, startMs: Long, s: AppSettings,
    ): PlaybackRequest {
        val subs = ArrayList<ExternalSubtitle>()
        item.stream.subtitles.forEach { sub ->
            if (sub.url.isNotBlank()) subs += ExternalSubtitle(sub.url, sub.lang.takeIf { it != "und" }, sub.label, item.source.displayName, item.source.order)
        }
        subs += sourceSubtitles(type, target.videoId, item, s)

        var next: NextEpisodeInfo? = null
        var prev: NextEpisodeInfo? = null
        if (target.season != null) {
            val seq = EpisodeLogic.sequence(states)
            val idx = seq.indexOfFirst { it.videoId == target.videoId }
            if (idx >= 0) {
                seq.getOrNull(idx + 1)?.takeIf { it.released }?.let { next = info(it) }
                seq.getOrNull(idx - 1)?.let { prev = info(it) }
            }
        }
        val ref = if (target.season != null && target.episode != null) PlaybackRef.episode(type, mediaId, target.videoId, target.season, target.episode)
        else PlaybackRef.movie(type, target.videoId)
        val binge = item.stream.behaviorHints.bingeGroup?.takeIf { it.isNotBlank() }
        c.progress.registerPlayback(ref, binge?.let { "binge:$it" })
        return PlaybackRequest(
            mediaId = mediaId, type = type, episodeId = if (target.season != null) target.videoId else null,
            title = title, subtitleLine = target.label.ifBlank { null },
            url = item.url!!, headers = item.requestHeaders, startPositionMs = startMs,
            externalSubtitles = subs.distinctBy { it.url }, nextEpisode = next, previousEpisode = prev,
            videoId = target.videoId, bingeGroup = binge,
        )
    }

    suspend fun sourceSubtitles(type: String, videoId: String, item: StreamItem, s: AppSettings): List<ExternalSubtitle> {
        if (!s.autoFetchSubtitles) return emptyList()
        var offers = emptyList<StreamRepository.SubtitleOffer>()
        val hints = item.stream.behaviorHints
        withTimeoutOrNull(2_500) {
            c.streams.subtitles(type, videoId, hints.videoHash, hints.videoSize, hints.filename).collect { offers = it }
        }
        return offers.map { o ->
            ExternalSubtitle(o.subtitle.url, o.subtitle.lang.takeIf { it != "und" }, o.subtitle.label, o.source.displayName, o.source.order, hashMatched = hints.videoHash != null)
        }
    }

    private fun info(e: EpisodeState) = NextEpisodeInfo(e.videoId, EpisodeLabels.line(e.season, e.episode, e.video.displayTitle))
}

/** What [com.liuying.app.ui.player.PlayerController] uses to switch streams and episodes in place (L044, L045). */
class AppPlaybackGateway(private val c: AppContainer) : PlaybackGateway {
    private val builder = PlaybackBuilder(c)
    private val items = ConcurrentHashMap<String, StreamItem>()

    private fun videoIdOf(r: PlaybackRequest) = r.videoId ?: r.episodeId ?: r.mediaId
    private fun itemKey(videoId: String, key: String) = "$videoId#$key"

    override fun streams(request: PlaybackRequest): Flow<StreamsSnapshot> {
        val vid = videoIdOf(request)
        return c.streams.streams(request.type, vid).map { res ->
            val all = res.items
            all.forEach { items[itemKey(vid, it.key)] = it }
            StreamsSnapshot(all.map(::choice), loading = !res.done, failedSources = res.failures.size)
        }
    }

    private fun choice(i: StreamItem) = StreamChoice(
        key = i.key, title = i.displayName, source = i.source.displayName, badges = i.quality.badges, detail = i.detail,
        playable = i.enabled && i.kind == StreamKind.Direct && !i.url.isNullOrBlank(),
        resolutionHeight = i.quality.resolution?.height ?: 0, sizeBytes = i.quality.sizeBytes ?: 0L, url = i.url,
    )

    override suspend fun requestForChoice(base: PlaybackRequest, key: String, startMs: Long): PlaybackRequest? {
        val vid = videoIdOf(base)
        val item = items[itemKey(vid, key)]?.takeIf { it.kind == StreamKind.Direct && !it.url.isNullOrBlank() } ?: return null
        val s = c.settings.current()
        val subs = ArrayList<ExternalSubtitle>()
        item.stream.subtitles.forEach { sub ->
            if (sub.url.isNotBlank()) subs += ExternalSubtitle(sub.url, sub.lang.takeIf { it != "und" }, sub.label, item.source.displayName, item.source.order)
        }
        subs += builder.sourceSubtitles(base.type, vid, item, s)
        val binge = item.stream.behaviorHints.bingeGroup?.takeIf { it.isNotBlank() }
        // keep the progress registration's stream hint in step with the new stream
        c.progress.registerPlayback(refOf(base), binge?.let { "binge:$it" })
        return base.copy(
            url = item.url!!, headers = item.requestHeaders, startPositionMs = startMs,
            externalSubtitles = subs.distinctBy { it.url }, bingeGroup = binge,
        )
    }

    private fun refOf(r: PlaybackRequest): PlaybackRef =
        if (r.episodeId == null) PlaybackRef.movie(r.type, videoIdOf(r))
        else com.liuying.app.domain.sync.SyncLogic.parseEpisodeId(r.mediaId, r.episodeId)?.let { (se, ep) -> PlaybackRef.episode(r.type, r.mediaId, r.episodeId, se, ep) }
            ?: PlaybackRef.movie(r.type, r.mediaId)

    override suspend fun requestForEpisode(base: PlaybackRequest, episodeId: String, waitMs: Long): PlaybackRequest? {
        val meta = c.metas.getOrFetch(base.type, base.mediaId) ?: return null
        val progress = c.progress.observeForTitle(base.type, base.mediaId).first()
        val states = EpisodeLogic.buildStates(meta.videos, progress.filter { it.isEpisode }, SystemClock.todayEpochDay())
        val e = states.firstOrNull { it.videoId == episodeId } ?: return null
        val target = StreamTarget(e.videoId, e.season, e.episode, e.video.displayTitle)
        val s = c.settings.current()

        var last: StreamsResult? = null
        withTimeoutOrNull(waitMs) {
            c.streams.streams(base.type, episodeId).collect { r ->
                last = r
                // stop early once a stream of the same binge group is in, otherwise wait for every source
                if (base.bingeGroup != null && r.items.any { sameGroup(it, base.bingeGroup) }) return@collect
            }
        }
        val result = last ?: return null
        val playable = result.items.filter { it.enabled && it.kind == StreamKind.Direct && !it.url.isNullOrBlank() }
        val pick = base.bingeGroup?.let { g -> playable.firstOrNull { sameGroup(it, g) } } ?: StreamPicker.pick(playable, s) ?: return null
        val existing = progress.firstOrNull { it.videoId == episodeId || (it.season == e.season && it.episode == e.episode) }
        val resume = existing?.let { ProgressRules.resumePositionMs(it, threshold = s.completionThreshold) }
        val start = ProgressRules.rewound(resume, s.resumeRewindSec)
        return builder.build(base.type, base.mediaId, base.title, target, pick, states, start, s)
    }

    private fun sameGroup(i: StreamItem, g: String?) = g != null && i.enabled && i.kind == StreamKind.Direct && i.stream.behaviorHints.bingeGroup == g
}
