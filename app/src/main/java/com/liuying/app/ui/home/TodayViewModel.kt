package com.liuying.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.repo.HomeRow
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.domain.ContinueItem
import com.liuying.app.domain.ContinueWatchingLogic
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.ProgressEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class TodayViewModel(private val c: AppContainer) : ViewModel() {
    private val refreshTick = MutableStateFlow(0)
    // Lazily: the pipeline (network rows especially) must survive a trip to Details instead of restarting from skeletons.
    private val eager = SharingStarted.Lazily

    val rows: StateFlow<List<HomeRow>?> = c.catalogs.homeRows(refreshTick).stateIn(viewModelScope, eager, null)
    val hasSources: StateFlow<Boolean?> = c.sources.sources.map { l -> l.any { it.enabled } }.stateIn(viewModelScope, eager, null)
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, eager, AppSettings())

    val continueItems: StateFlow<List<ContinueItem>> =
        combine(c.progress.observeAll(), c.progress.observeDismissed(), c.settings.settings) { p, d, s -> Triple(p, d, s) }
            .transformLatest { (progress, dismissed, s) ->
                if (!s.showContinueWatching) { emit(emptyList()); return@transformLatest }
                val keys = progress.sortedByDescending { it.updatedAt }.map { it.type to it.titleId }.distinct().take(30)
                val metas = HashMap<Pair<String, String>, Meta>()
                keys.forEach { k -> c.metas.cached(k.first, k.second)?.let { metas[k] = it } }
                emit(build(progress, dismissed, s, metas))
                val missing = keys.filter { it !in metas }
                if (missing.isNotEmpty()) {
                    coroutineScope { missing.map { k -> async { k to c.metas.getOrFetch(k.first, k.second) } }.awaitAll() }
                        .forEach { (k, m) -> if (m != null) metas[k] = m }
                    emit(build(progress, dismissed, s, metas))
                }
            }
            .stateIn(viewModelScope, eager, emptyList())

    private fun build(progress: List<ProgressEntry>, dismissed: Map<Pair<String, String>, Long>, s: AppSettings, metas: Map<Pair<String, String>, Meta>): List<ContinueItem> {
        val videos = metas.mapValues { it.value.videos }
        return ContinueWatchingLogic.compute(
            progress, dismissed, videos, SystemClock.todayEpochDay(), s.showUpNext, s.completionThreshold,
        ).map { item ->
            val m = metas[item.type to item.titleId] ?: return@map item
            val ep = m.videos.firstOrNull { it.id == item.videoId }
            item.copy(
                title = m.title, poster = m.poster,
                episodeTitle = item.episodeTitle ?: ep?.displayTitle,
                thumbnail = item.thumbnail ?: ep?.thumbnail ?: m.background ?: m.poster,
            )
        }
    }

    /** Saved titles as "type|id", for the quick-save buttons. */
    val savedKeys: StateFlow<Set<String>> = c.library.observeAll().map { l -> l.map { it.type + "|" + it.id }.toSet() }.stateIn(viewModelScope, eager, emptySet())

    fun toggleSave(preview: MetaPreview, fallbackType: String) {
        val type = preview.type.ifBlank { fallbackType }
        viewModelScope.launch {
            if ((type + "|" + preview.id) in savedKeys.value) c.library.remove(type, preview.id) else c.library.add(preview, type)
        }
    }

    val actions = com.liuying.app.ui.common.TitleActions(c, viewModelScope)
    fun toggleWatched(preview: MetaPreview, fallbackType: String) = actions.toggleWatched(preview, fallbackType)

    private var lastPick: String? = null
    /** L010: a random unwatched title from the rows on screen and the shelf. Null when there is nothing to pick. */
    fun surprise(): com.liuying.app.domain.PickCandidate? {
        val fromRows = rows.value.orEmpty().flatMap { r ->
            val items = (r.state as? com.liuying.app.data.repo.RowState.Loaded)?.items.orEmpty()
            items.map { com.liuying.app.domain.PickCandidate(it.copy(type = it.type.ifBlank { r.entry.catalog.type }), it.type.ifBlank { r.entry.catalog.type }, r.entry.source.id) }
        }
        val pick = com.liuying.app.domain.SurprisePick.pick(fromRows, actions.watchedKeys.value, avoid = lastPick)
        lastPick = pick?.let { com.liuying.app.domain.WatchedIndex.key(it.type, it.preview.id) }
        return pick
    }

    fun refresh() { refreshTick.value += 1 }
    fun dismissGuide() { viewModelScope.launch { c.settings.update { it.copy(firstRunGuideDismissed = true) } } }
    fun retry(key: String) = c.catalogs.retryRow(key)
    fun dismiss(item: ContinueItem) { viewModelScope.launch { c.progress.dismissContinue(item.type, item.titleId) } }
}
