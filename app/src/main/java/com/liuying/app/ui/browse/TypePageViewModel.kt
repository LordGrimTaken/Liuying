package com.liuying.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.data.repo.HomeRow
import com.liuying.app.data.repo.RowState
import com.liuying.app.data.remote.toUserMessage
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.domain.Ux
import com.liuying.app.domain.model.CatalogEntry
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.genreOptions
import com.liuying.app.domain.model.supportsGenre
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Movies and Series pages: the first catalogs of one type, a genre tab strip (union of their genre options), and a hero. */
@OptIn(ExperimentalCoroutinesApi::class)
class TypePageViewModel(private val c: AppContainer, val type: String) : ViewModel() {
    private val lazily = SharingStarted.Lazily
    val genre = MutableStateFlow<String?>(null)
    private val refreshTick = MutableStateFlow(0)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, lazily, AppSettings())

    /** Same watched set and poster actions as Today and Browse, so the Movies/Series tabs are not a blind spot. */
    val actions = com.liuying.app.ui.common.TitleActions(c, viewModelScope)

    fun toggleWatched(m: com.liuying.app.domain.model.MetaPreview, type: String) = actions.toggleWatched(m, type)
    val hasSources: StateFlow<Boolean?> = c.sources.sources.map { l -> l.any { it.enabled } }.stateIn(viewModelScope, lazily, null)

    private val entries: StateFlow<List<CatalogEntry>?> =
        c.catalogs.browseEntries().map { all -> all.filter { it.catalog.type.equals(type, true) }.take(MAX_ROWS) }.stateIn(viewModelScope, lazily, null)

    val genres: StateFlow<List<String>> = entries.map { l ->
        l.orEmpty().filter { it.catalog.supportsGenre }.flatMap { it.catalog.genreOptions }.distinct()
    }.stateIn(viewModelScope, lazily, emptyList())

    /** Null until the catalog list is known; rows then fill in one by one. */
    val rows: StateFlow<List<HomeRow>?> = combine(entries.filterNotNull(), genre, refreshTick) { e, g, t -> Triple(e, g, t) }
        .flatMapLatest { (list, g, tick) ->
            channelFlow {
                val shown = if (g == null) list else list.filter { it.catalog.supportsGenre }
                val states = LinkedHashMap<String, HomeRow>()
                shown.forEach { states[it.key] = HomeRow(it, RowState.Loading) }
                val mutex = Mutex()
                suspend fun publish() = send(shown.mapNotNull { states[it.key] })
                mutex.withLock { publish() }
                coroutineScope {
                    for (e in shown) launch {
                        val st = c.catalogs.loadPage(e, g, force = tick > 0).fold(
                            onSuccess = { RowState.Loaded(it.distinctBy { m -> m.id }.take(30)) },
                            onFailure = { RowState.Failed(it.toUserMessage()) },
                        )
                        mutex.withLock { states[e.key] = HomeRow(e, st); publish() }
                    }
                }
            }
        }.conflate().stateIn(viewModelScope, lazily, null)

    private val _hero = MutableStateFlow<List<MetaPreview>>(emptyList())
    /** Featured titles; kept while a genre is chosen so the hero does not jump. */
    val hero: StateFlow<List<MetaPreview>> = _hero
    var heroEntry: CatalogEntry? = null; private set

    val savedKeys: StateFlow<Set<String>> = c.library.observeAll().map { l -> l.map { it.type + "|" + it.id }.toSet() }.stateIn(viewModelScope, lazily, emptySet())

    init {
        viewModelScope.launch {
            rows.collect { list ->
                if (genre.value != null && _hero.value.isNotEmpty()) return@collect
                // every catalog of this type is gone (source removed or disabled): do not keep featuring its titles
                if (list != null && list.isEmpty() && genre.value == null) { _hero.value = emptyList(); heroEntry = null }
                val first = list?.firstOrNull() ?: return@collect
                val items = (first.state as? RowState.Loaded)?.items.orEmpty()
                val picked = Ux.pickFeatured(items, 5)
                if (picked.isNotEmpty()) { _hero.value = picked; heroEntry = first.entry }
            }
        }
    }

    fun selectGenre(g: String?) { genre.value = g }
    fun refresh() { refreshTick.value += 1 }

    fun toggleSave(preview: MetaPreview) {
        val t = preview.type.ifBlank { type }
        viewModelScope.launch { if ((t + "|" + preview.id) in savedKeys.value) c.library.remove(t, preview.id) else c.library.add(preview, t) }
    }

    private companion object { const val MAX_ROWS = 6 }
}
