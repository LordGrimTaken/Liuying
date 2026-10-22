package com.liuying.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.db.RecentSearchEntity
import com.liuying.app.data.repo.SearchResults
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val typeFilter = MutableStateFlow<String?>(null)
    private val eager = SharingStarted.WhileSubscribed(5_000)

    /** Null while there is nothing to search for. */
    val results: StateFlow<SearchResults?> = query.map { it.trim() }.debounce(350).distinctUntilChanged()
        .flatMapLatest { q -> if (q.length < 2) flowOf(null) else c.catalogs.search(q) }
        .stateIn(viewModelScope, eager, null)

    val recent: StateFlow<List<String>> = c.recentSearches.observe(8).map { l -> l.map { it.text } }.stateIn(viewModelScope, eager, emptyList())

    fun setQuery(q: String) { query.value = q; typeFilter.value = null }
    fun setType(t: String?) { typeFilter.value = t }

    /** Remembers the current query (when enabled in settings). Called on search/IME action and when a result is opened. */
    fun commitRecent() {
        val q = query.value.trim()
        if (q.length < 2) return
        viewModelScope.launch {
            if (!c.settings.current().recentSearchesEnabled) return@launch
            c.recentSearches.upsert(RecentSearchEntity(q, System.currentTimeMillis()))
            c.recentSearches.trim(20)
        }
    }

    fun removeRecent(t: String) { viewModelScope.launch { c.recentSearches.delete(t) } }
    fun clearRecent() { viewModelScope.launch { c.recentSearches.deleteAll() } }
}
