package com.liuying.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.data.settings.LibrarySort
import com.liuying.app.data.settings.LibraryView
import com.liuying.app.domain.LibraryLogic
import com.liuying.app.domain.TypeFilter
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.ProgressEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShelfEntry(val item: LibraryItem, val progress: Float?, val watched: Boolean)
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
        ShelfState(true, lib.size, shown.map { item ->
            val rows = byTitle[item.type to item.id].orEmpty()
            val inProg = rows.filter { com.liuying.app.domain.ProgressRules.isInProgress(it, s.completionThreshold) }.maxByOrNull(ProgressEntry::updatedAt)
            ShelfEntry(item, inProg?.fraction?.toFloat(), item.type.equals("movie", true) && rows.any { it.watched })
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfState())

    fun setSort(s: LibrarySort) { viewModelScope.launch { c.settings.update { it.copy(librarySort = s) } } }
    fun toggleView() { viewModelScope.launch { c.settings.update { it.copy(libraryView = if (it.libraryView == LibraryView.Grid) LibraryView.List else LibraryView.Grid) } } }
    fun remove(item: LibraryItem) { viewModelScope.launch { c.library.remove(item.type, item.id) } }
}
