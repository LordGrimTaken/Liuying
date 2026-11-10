package com.liuying.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.data.settings.LibrarySort
import com.liuying.app.data.settings.LibraryView
import com.liuying.app.domain.BulkWatch
import com.liuying.app.domain.LibraryLogic
import com.liuying.app.domain.TypeFilter
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.PlaybackRef
import com.liuying.app.domain.model.ProgressEntry
import com.liuying.app.domain.model.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShelfEntry(val item: LibraryItem, val progress: Float?, val watched: Boolean, val status: com.liuying.app.domain.ShelfStatus = com.liuying.app.domain.ShelfStatus.Planned)
data class ShelfState(val loaded: Boolean = false, val totalCount: Int = 0, val entries: List<ShelfEntry> = emptyList())

class ShelfViewModel(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(TypeFilter.All)
    val query = MutableStateFlow("")
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val state: StateFlow<ShelfState> = combine(
        c.library.observeAll(), c.progress.observeAll(), c.settings.settings, filter, query,
    ) { lib, prog, s, f, q ->
        val shown = LibraryLogic.apply(lib, f, q, s.librarySort, LibraryLogic.lastPlayed(prog), LibraryLogic.watchedTitles(prog))
        val byTitle = prog.groupBy { it.type to it.titleId }
        val entries = shown.map { item ->
            val rows = byTitle[item.type to item.id].orEmpty()
            val inProg = rows.filter { com.liuying.app.domain.ProgressRules.isInProgress(it, s.completionThreshold) }.maxByOrNull(ProgressEntry::updatedAt)
            val cheap = com.liuying.app.domain.ShelfStatuses.of(item.type, rows)
            // Only a series with every row watched is worth a meta lookup, and only to confirm the list is complete.
            val status = if (cheap == com.liuying.app.domain.ShelfStatus.Watching && !item.type.equals("movie", true) &&
                rows.isNotEmpty() && rows.all { it.watched }
            ) {
                val known = runCatching { c.metas.cached(item.type, item.id) }.getOrNull()
                    ?.videos?.count { it.season != null && it.episode != null }
                com.liuying.app.domain.ShelfStatuses.of(item.type, rows, known)
            } else cheap
            ShelfEntry(item, inProg?.fraction?.toFloat(), item.type.equals("movie", true) && rows.any { it.watched }, status)
        }
        // The status knows whether a series is finished, so use it for the sort instead of "has any watched row":
        // a show you started should stay with the unwatched ones.
        val ordered = if (s.librarySort == LibrarySort.UnwatchedFirst) LibraryLogic.completedLast(entries, { it.status == com.liuying.app.domain.ShelfStatus.Completed }) else entries
        ShelfState(true, lib.size, ordered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfState())

    fun setSort(s: LibrarySort) { viewModelScope.launch { c.settings.update { it.copy(librarySort = s) } } }
    fun toggleView() { viewModelScope.launch { c.settings.update { it.copy(libraryView = if (it.libraryView == LibraryView.Grid) LibraryView.List else LibraryView.Grid) } } }
    fun remove(item: LibraryItem) { viewModelScope.launch { c.library.remove(item.type, item.id) } }
    /** L005: bulk actions on the selected titles. */
    fun removeAll(items: List<LibraryItem>) { viewModelScope.launch { items.forEach { c.library.remove(it.type, it.id) } } }
    /**
     * L005: films are marked directly; a series needs its episode list, which comes from the meta cache and,
     * for a small selection, one fetch. Series the app cannot see are reported instead of silently ignored.
     */
    fun markWatched(items: List<LibraryItem>, watched: Boolean = true) {
        viewModelScope.launch {
            val series = items.filter { !it.type.equals("movie", true) }
            val episodes = HashMap<String, List<Video>>()
            for (item in series) {
                val meta = runCatching { c.metas.cached(item.type, item.id) }.getOrNull()
                    ?: if (series.size <= 5) runCatching { c.metas.getOrFetch(item.type, item.id) }.getOrNull() else null
                if (meta != null) episodes[BulkWatch.key(item)] = meta.videos
            }
            val plan = BulkWatch.plan(items, episodes)
            plan.movies.forEach { c.progress.setWatched(PlaybackRef.movie(it.type, it.id), watched) }
            plan.episodes.forEach { c.progress.setWatched(PlaybackRef.episode(it.type, it.titleId, it.videoId, it.season, it.episode), watched) }
            message.value = BulkWatch.summary(plan, watched)
        }
    }

    /** The line the shelf shows after a bulk action. */
    val message = MutableStateFlow<String?>(null)
    fun clearMessage() { message.value = null }
    private var lastPick: String? = null
    /** L010: a random title from the shelf that is not watched. */
    fun surprise(): LibraryItem? {
        val e = state.value.entries
        val pool = e.filter { !it.watched && it.status != com.liuying.app.domain.ShelfStatus.Completed }
        val varied = if (pool.size > 1) pool.filter { it.item.type + "|" + it.item.id != lastPick } else pool
        val pick = varied.randomOrNull()?.item
        lastPick = pick?.let { it.type + "|" + it.id }
        return pick
    }
    fun restore(item: LibraryItem) { viewModelScope.launch { c.library.add(item) } }
}
