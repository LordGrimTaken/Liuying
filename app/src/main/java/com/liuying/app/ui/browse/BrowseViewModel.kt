package com.liuying.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.repo.CatalogPager
import com.liuying.app.data.repo.PagerState
import com.liuying.app.domain.model.CatalogEntry
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.supportsGenre
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUi(
    val resolved: Boolean = false,
    val entry: CatalogEntry? = null,
    val genre: String? = null,
    /** Other fixed-option extras chosen in the UI (name to value). */
    val more: Map<String, String> = emptyMap(),
    val pager: PagerState = PagerState(),
)

/** "See all" for one catalog: the protocol's skip paging behind a grid, with genre and fixed-option extra filters. */
class BrowseViewModel(private val c: AppContainer, private val key: String, private val initialGenre: String? = null) : ViewModel() {
    private val _ui = MutableStateFlow(BrowseUi())
    val ui: StateFlow<BrowseUi> = _ui.asStateFlow()
    private var pager: CatalogPager? = null
    private var collectJob: Job? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val entry = c.catalogs.entryByKey(key)
            _ui.value = BrowseUi(resolved = true, entry = entry, genre = initialGenre?.takeIf { entry?.catalog?.supportsGenre == true })
            if (entry != null) restart()
        }
    }

    private fun restart(force: Boolean = false) {
        val entry = _ui.value.entry ?: return
        collectJob?.cancel(); loadJob?.cancel()
        val p = c.catalogs.pager(entry, _ui.value.genre, _ui.value.more, force)
        pager = p
        _ui.update { it.copy(pager = PagerState()) }
        collectJob = viewModelScope.launch { p.state.collect { s -> _ui.update { it.copy(pager = s) } } }
        loadMore()
    }

    fun loadMore() {
        val p = pager ?: return
        val s = p.state.value
        if (s.loading || s.endReached) return
        loadJob = viewModelScope.launch { p.loadNext() }
    }

    fun retry() = loadMore()
    fun refresh() = restart(force = true)

    fun selectGenre(genre: String?) {
        if (genre == _ui.value.genre) return
        _ui.update { it.copy(genre = genre) }
        restart()
    }

    fun selectExtra(name: String, value: String) {
        if (_ui.value.more[name] == value) return
        _ui.update { it.copy(more = it.more + (name to value)) }
        restart()
    }

    fun toggleSave(preview: MetaPreview) {
        val type = preview.type.ifBlank { _ui.value.entry?.catalog?.type.orEmpty() }
        viewModelScope.launch {
            if ((type + "|" + preview.id) in savedKeys.value) c.library.remove(type, preview.id) else c.library.add(preview, type)
        }
    }

    val savedKeys: StateFlow<Set<String>> = c.library.observeAll().let { f ->
        val out = MutableStateFlow<Set<String>>(emptySet())
        viewModelScope.launch { f.collect { l -> out.value = l.map { it.type + "|" + it.id }.toSet() } }
        out.asStateFlow()
    }
}
