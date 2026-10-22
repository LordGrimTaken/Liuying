package com.liuying.app.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.repo.MetaLoad
import com.liuying.app.data.repo.StreamItem
import com.liuying.app.data.repo.StreamsResult
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.data.settings.ResumeBehavior
import com.liuying.app.data.settings.StreamSelectionMode
import com.liuying.app.domain.EpisodeLabels
import com.liuying.app.domain.EpisodeLogic
import com.liuying.app.domain.EpisodeState
import com.liuying.app.domain.PlayKind
import com.liuying.app.domain.PlayTarget
import com.liuying.app.domain.ProgressRules
import com.liuying.app.domain.StreamKind
import com.liuying.app.domain.StreamPicker
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.PlaybackRef
import com.liuying.app.domain.model.ProgressEntry
import com.liuying.app.ui.player.ExternalSubtitle
import com.liuying.app.ui.player.NextEpisodeInfo
import com.liuying.app.ui.player.PlaybackRequest
import com.liuying.app.ui.player.PlayerExit
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the stream list is for: a movie, or one episode of a series. */
data class StreamTarget(val videoId: String, val season: Int?, val episode: Int?, val episodeTitle: String?) {
    val label: String get() = EpisodeLabels.line(season, episode, episodeTitle)
}

data class DetailsUi(
    val load: MetaLoad? = null,
    val inLibrary: Boolean = false,
    val states: List<EpisodeState> = emptyList(),
    val seasons: List<Int> = emptyList(),
    val season: Int? = null,
    val playTarget: PlayTarget? = null,
    /** Movie / single-video progress. */
    val movieProgress: ProgressEntry? = null,
    val settings: AppSettings = AppSettings(),
) {
    val meta: Meta? get() = load?.meta
    val isSeries: Boolean get() = states.isNotEmpty()
    val episodes: List<EpisodeState> get() = states.filter { it.season == season }
}

data class StreamsSheet(
    val target: StreamTarget,
    val result: StreamsResult? = null,
    /** Auto-select mode is waiting for sources before picking. */
    val autoPending: Boolean = false,
)

data class ResumeAsk(val item: StreamItem, val positionMs: Long)

sealed interface DetailsEvent {
    data class Launch(val request: PlaybackRequest) : DetailsEvent
    data class OpenUrl(val url: String) : DetailsEvent
    data class Message(val text: String) : DetailsEvent
}

class DetailsViewModel(
    private val c: AppContainer,
    val type: String,
    val id: String,
    private val initialPlay: String?,
) : ViewModel() {
    private val hint = c.hint(type, id)
    private val reloadTick = MutableStateFlow(0)
    private val selectedSeason = MutableStateFlow<Int?>(null)

    private val metaFlow = reloadTick.flatMapLatest { t ->
        c.metas.meta(type, id, hint?.first, hint?.second, force = t > 0)
    }

    val ui: StateFlow<DetailsUi> = combine(
        metaFlow, c.library.observeContains(type, id), c.progress.observeForTitle(type, id), c.settings.settings, selectedSeason,
    ) { load, inLib, prog, s, sel ->
        val meta = load.meta
        val states = if (meta != null) EpisodeLogic.buildStates(meta.videos, prog.filter { it.isEpisode }, SystemClock.todayEpochDay()) else emptyList()
        val seasons = EpisodeLogic.seasons(states)
        DetailsUi(
            load = load, inLibrary = inLib, states = states, seasons = seasons,
            season = sel?.takeIf { it in seasons } ?: EpisodeLogic.defaultSeason(states),
            playTarget = if (states.isNotEmpty()) EpisodeLogic.playTarget(states) else null,
            movieProgress = prog.firstOrNull { !it.isEpisode }, settings = s,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUi())

    private val _sheet = MutableStateFlow<StreamsSheet?>(null)
    val sheet: StateFlow<StreamsSheet?> = _sheet
    private val _ask = MutableStateFlow<ResumeAsk?>(null)
    val ask: StateFlow<ResumeAsk?> = _ask
    private val events = Channel<DetailsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var streamsJob: Job? = null
    private var autoJob: Job? = null
    private var launched = false

    init {
        viewModelScope.launch {
            c.playerExits.collect { exit ->
                when (exit) {
                    is PlayerExit.PlayNext -> if (exit.mediaId == id) openStreams(targetFor(exit.nextEpisodeId))
                    is PlayerExit.TryAnotherStream -> if (exit.mediaId == id) openStreams(targetFor(exit.episodeId ?: movieVideoId()), auto = false)
                    PlayerExit.Closed -> Unit
                }
            }
        }
        if (initialPlay != null) viewModelScope.launch {
            ui.first { it.meta != null && (it.states.isNotEmpty() || it.load?.loading == false) }
            openStreams(targetFor(initialPlay))
        }
    }

    // ------------------------------------------------------------------------------ simple actions

    fun reload() { reloadTick.value += 1 }
    fun selectSeason(s: Int) { selectedSeason.value = s }

    fun toggleLibrary() {
        val u = ui.value
        val meta = u.meta ?: return
        viewModelScope.launch {
            if (u.inLibrary) c.library.remove(type, id)
            else c.library.add(
                LibraryItem(type, id, meta.title, meta.poster, meta.background, meta.displayYear, meta.allGenres, System.currentTimeMillis()),
            )
        }
    }

    fun setEpisodeWatched(e: EpisodeState, watched: Boolean) {
        viewModelScope.launch { c.progress.setWatched(PlaybackRef.episode(type, id, e.videoId, e.season, e.episode), watched) }
    }

    fun setMovieWatched(watched: Boolean) {
        viewModelScope.launch { c.progress.setWatched(PlaybackRef.movie(type, movieVideoId()), watched) }
    }

    private fun movieVideoId(): String = ui.value.meta?.behaviorHints?.defaultVideoId?.takeIf { it.isNotBlank() } ?: id

    fun targetFor(videoId: String): StreamTarget {
        val e = ui.value.states.firstOrNull { it.videoId == videoId }
        return if (e != null) StreamTarget(videoId, e.season, e.episode, e.video.displayTitle) else StreamTarget(videoId, null, null, null)
    }

    fun playPrimary() {
        val u = ui.value
        val t = u.playTarget?.episode
        openStreams(if (t != null) StreamTarget(t.videoId, t.season, t.episode, t.video.displayTitle) else StreamTarget(movieVideoId(), null, null, null))
    }

    fun playEpisode(e: EpisodeState) = openStreams(StreamTarget(e.videoId, e.season, e.episode, e.video.displayTitle), auto = false)

    // ------------------------------------------------------------------------------ streams

    fun openStreams(target: StreamTarget, auto: Boolean = true, force: Boolean = false) {
        streamsJob?.cancel(); autoJob?.cancel()
        launched = false
        val s0 = ui.value.settings
        val autoMode = auto && s0.streamSelection == StreamSelectionMode.AutoSelect
        _sheet.value = StreamsSheet(target, null, autoPending = autoMode)
        streamsJob = viewModelScope.launch {
            c.streams.streams(type, target.videoId, force).collect { r ->
                _sheet.update { cur -> if (cur?.target == target) cur.copy(result = r) else cur }
                if (autoMode && r.done) tryAuto(target, r)
            }
        }
        if (autoMode) autoJob = viewModelScope.launch {
            delay(s0.autoSelectWaitSec * 1000L)
            val r = _sheet.value?.result
            if (r != null) tryAuto(target, r) else _sheet.update { it?.copy(autoPending = false) }
        }
    }

    private fun tryAuto(target: StreamTarget, r: StreamsResult) {
        if (launched || _sheet.value?.target != target) return
        val pick = StreamPicker.pick(r.items, ui.value.settings)
        if (pick == null) {
            if (r.done) _sheet.update { it?.copy(autoPending = false) }
            return
        }
        onStreamClick(pick)
    }

    fun retryStreams() { _sheet.value?.target?.let { openStreams(it, auto = false) } }
    fun refreshStreams() { _sheet.value?.target?.let { openStreams(it, auto = false, force = true) } }

    fun dismissSheet() {
        streamsJob?.cancel(); autoJob?.cancel()
        _sheet.value = null
    }

    fun onStreamClick(item: StreamItem) {
        when {
            !item.enabled -> events.trySend(DetailsEvent.Message(item.disabledReason ?: "This stream cannot be played."))
            item.kind == StreamKind.Direct && !item.url.isNullOrBlank() -> startPlayback(item, null)
            item.kind == StreamKind.External && !item.stream.externalUrl.isNullOrBlank() -> events.trySend(DetailsEvent.OpenUrl(item.stream.externalUrl!!))
            item.kind == StreamKind.YouTube && !item.stream.ytId.isNullOrBlank() ->
                events.trySend(DetailsEvent.OpenUrl("https://www.youtube.com/watch?v=${item.stream.ytId}"))
            else -> events.trySend(DetailsEvent.Message("This stream cannot be played."))
        }
    }

    fun resumeChoice(resume: Boolean) {
        val a = _ask.value ?: return
        _ask.value = null
        startPlayback(a.item, if (resume) a.positionMs else 0L)
    }

    fun cancelAsk() { _ask.value = null }

    /** [forcedStart] null = decide from the resume setting. */
    private fun startPlayback(item: StreamItem, forcedStart: Long?) {
        val target = _sheet.value?.target ?: return
        if (launched) return
        viewModelScope.launch {
            val u = ui.value
            val s = u.settings
            val meta = u.meta
            val ref = if (target.season != null && target.episode != null) PlaybackRef.episode(type, id, target.videoId, target.season, target.episode)
            else PlaybackRef.movie(type, target.videoId)
            val existing = c.progress.get(ref)
            val resumeMs = existing?.let { ProgressRules.resumePositionMs(it) } ?: 0L
            val start = when {
                forcedStart != null -> forcedStart
                s.resumeBehavior == ResumeBehavior.StartOver -> 0L
                s.resumeBehavior == ResumeBehavior.Ask && resumeMs > 0 -> { _ask.value = ResumeAsk(item, resumeMs); return@launch }
                else -> resumeMs
            }
            launched = true
            autoJob?.cancel()

            val subs = ArrayList<ExternalSubtitle>()
            item.stream.subtitles.forEach { sub ->
                if (sub.url.isNotBlank()) subs += ExternalSubtitle(sub.url, sub.lang.takeIf { it != "und" }, sub.label, item.source.displayName, item.source.order)
            }
            if (s.autoFetchSubtitles) {
                var offers = emptyList<com.liuying.app.data.repo.StreamRepository.SubtitleOffer>()
                val hints = item.stream.behaviorHints
                withTimeoutOrNull(2_500) {
                    c.streams.subtitles(type, target.videoId, hints.videoHash, hints.videoSize, hints.filename).collect { offers = it }
                }
                offers.forEach { o -> subs += ExternalSubtitle(o.subtitle.url, o.subtitle.lang.takeIf { it != "und" }, o.subtitle.label, o.source.displayName, o.source.order, hashMatched = hints.videoHash != null) }
            }

            val next = if (target.season != null) {
                val seq = EpisodeLogic.sequence(u.states)
                val idx = seq.indexOfFirst { it.videoId == target.videoId }
                seq.getOrNull(idx + 1)?.takeIf { idx >= 0 && it.released }?.let {
                    NextEpisodeInfo(it.videoId, EpisodeLabels.line(it.season, it.episode, it.video.displayTitle))
                }
            } else null

            c.progress.registerPlayback(ref)
            events.send(
                DetailsEvent.Launch(
                    PlaybackRequest(
                        mediaId = id, type = type, episodeId = if (target.season != null) target.videoId else null,
                        title = meta?.title ?: hint?.first?.title ?: id, subtitleLine = target.label.ifBlank { null },
                        url = item.url!!, headers = item.requestHeaders, startPositionMs = start,
                        externalSubtitles = subs.distinctBy { it.url }, nextEpisode = next,
                    ),
                ),
            )
            _sheet.value = null
        }
    }
}
