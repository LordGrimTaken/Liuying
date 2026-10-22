package com.liuying.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.db.RecentSearchEntity
import com.liuying.app.data.repo.SearchResults
import com.liuying.app.domain.Ux
import com.liuying.app.domain.model.MetaPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    /** "all" | "movie" | "series" | any other catalog type (see Ux.typeFilters). */
    val typeFilter = MutableStateFlow("all")
    private val eager = SharingStarted.WhileSubscribed(5_000)

    /** The query the results below belong to (after the typing pause). */
    private val settled: StateFlow<String> = query.map { it.trim() }.debounce(350).distinctUntilChanged().stateIn(viewModelScope, eager, "")

    /** Null while there is nothing to search for. */
    val results: StateFlow<SearchResults?> = settled
        .flatMapLatest { q -> if (q.length < 2) flowOf(null) else c.catalogs.search(q) }
        .stateIn(viewModelScope, eager, null)

    val recent: StateFlow<List<String>> = combine(c.recentSearches.observe(8), c.settings.settings) { l, s ->
        if (s.recentSearchesEnabled) l.map { it.text } else emptyList()
    }.stateIn(viewModelScope, eager, emptyList())

    /** Quick completions while typing: recent searches, shelf titles and titles seen in loaded catalogs. */
    val suggestions: StateFlow<List<String>> = combine(query, settled, recent, c.library.observeAll(), c.catalogs.titleVersion) { q, done, rec, lib, _ ->
        val typed = q.trim()
        if (typed.isEmpty() || typed.equals(done, true)) emptyList()
        else Ux.suggest(typed, listOf(rec, lib.map { it.name }, c.catalogs.knownTitles()), 5)
    }.stateIn(viewModelScope, eager, emptyList())

    val savedKeys: StateFlow<Set<String>> = c.library.observeAll().map { l -> l.map { it.type + "|" + it.id }.toSet() }.stateIn(viewModelScope, eager, emptySet())

    fun setQuery(q: String) { query.value = q; typeFilter.value = "all" }
    fun setType(t: String) { typeFilter.value = t }

    /** Remembers the current query (when enabled in settings). Called on search/IME action and when a result is opened. */
    fun commitRecent(q: String = query.value) {
        val text = q.trim()
        if (text.length < 2) return
        viewModelScope.launch {
            if (!c.settings.current().recentSearchesEnabled) return@launch
            c.recentSearches.upsert(RecentSearchEntity(text, System.currentTimeMillis()))
            c.recentSearches.trim(20)
        }
    }

    fun removeRecent(t: String) { viewModelScope.launch { c.recentSearches.delete(t) } }
    fun clearRecent() { viewModelScope.launch { c.recentSearches.deleteAll() } }

    fun toggleSave(preview: MetaPreview, fallbackType: String) {
        val type = preview.type.ifBlank { fallbackType }
        viewModelScope.launch {
            if ((type + "|" + preview.id) in savedKeys.value) c.library.remove(type, preview.id) else c.library.add(preview, type)
        }
    }
}
