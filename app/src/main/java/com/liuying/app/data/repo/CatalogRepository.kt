package com.liuying.app.data.repo

import com.liuying.app.data.remote.AddonClient
import com.liuying.app.data.remote.AddonErrorKind
import com.liuying.app.data.remote.AddonFailure
import com.liuying.app.data.remote.toUserMessage
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.domain.Ux
import com.liuying.app.domain.model.CatalogEntry
import com.liuying.app.domain.model.CatalogPref
import com.liuying.app.domain.model.Extras
import com.liuying.app.domain.model.ManifestCatalog
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.isAdult
import com.liuying.app.domain.model.isHomeEligible
import com.liuying.app.domain.model.requiredExtraDefaults
import com.liuying.app.domain.model.supportsGenre
import com.liuying.app.domain.model.supportsSearch
import com.liuying.app.domain.model.supportsSkip
import com.liuying.app.domain.model.usableCatalogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Loading state of one catalog row (home) or one search group. Rows are independent of each other. */
sealed interface RowState {
    data object Loading : RowState
    /** Zero items = empty (UI hides the row unless pinned). */
    data class Loaded(val items: List<MetaPreview>) : RowState
    data class Failed(val message: String, val kind: AddonErrorKind? = null) : RowState
}

data class HomeRow(val entry: CatalogEntry, val state: RowState, val refreshing: Boolean = false) {
    val key: String get() = entry.key
}

data class SearchGroup(val entry: CatalogEntry, val state: RowState) {
    val hasItems: Boolean get() = (state as? RowState.Loaded)?.items?.isNotEmpty() == true
}

/** Progressive search output. [totalCatalogs] == 0 means no installed source supports search. */
data class SearchResults(val groups: List<SearchGroup>, val pending: Int, val totalCatalogs: Int) {
    val done: Boolean get() = pending == 0
    val hasAnyResults: Boolean get() = groups.any { it.hasItems }
}

data class PagerState(
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = false,
    /** Set when the last page request failed; call loadNext() again to retry. */
    val error: String? = null,
    val endReached: Boolean = false,
    val firstPageLoaded: Boolean = false,
)

/**
 * Infinite paging over one catalog via the protocol's `skip` extra. Skip is the number of raw items received so
 * far. Ends when a page is empty, adds nothing new (duplicates only), or the catalog does not support skip.
 */
class CatalogPager(
    private val canPage: Boolean,
    private val fetch: suspend (skip: Int) -> Result<List<MetaPreview>>,
) {
    private val mutex = Mutex()
    private val seen = HashSet<String>()
    private var raw = 0
    private val _state = MutableStateFlow(PagerState())
    val state: StateFlow<PagerState> = _state.asStateFlow()

    suspend fun loadNext() {
        if (!mutex.tryLock()) return
        try {
            if (_state.value.endReached) return
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val result = fetch(raw)
                result.fold(
                    onSuccess = { page ->
                        raw += page.size
                        val fresh = page.filter { seen.add(it.id) }
                        val end = page.isEmpty() || fresh.isEmpty() || !canPage
                        _state.value = PagerState(_state.value.items + fresh, false, null, end, true)
                    },
                    onFailure = { _state.value = _state.value.copy(loading = false, error = it.toUserMessage()) },
                )
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(loading = false)
                throw e
            }
        } finally {
            mutex.unlock()
        }
    }
}

class CatalogRepository(
    private val sources: SourceProvider,
    private val client: AddonClient,
    private val settings: Flow<AppSettings>,
) {
    private val retryRequests = MutableSharedFlow<String>(extraBufferCapacity = 16)

    // ----------------------------------------------------------------------------- layout

    private fun browseFlow(): Flow<List<CatalogEntry>> =
        combine(sources.enabledSources, sources.catalogPrefs, settings.distinctUntilChanged { a, b -> a.hideAdultCatalogs == b.hideAdultCatalogs }) { srcs, prefs, s ->
            catalogEntries(srcs, prefs, s.hideAdultCatalogs, true)
        }.distinctUntilChanged()

    /** Every Today-eligible catalog in the user's saved order, hidden ones included (the layout editor lists these). */
    fun allHomeEntries(): Flow<List<CatalogEntry>> =
        combine(sources.enabledSources, settings.distinctUntilChanged { a, b -> a.hideAdultCatalogs == b.hideAdultCatalogs && a.homeOrder == b.homeOrder }) { srcs, s ->
            Ux.orderRows(catalogEntries(srcs, emptyList(), s.hideAdultCatalogs, false), s.homeOrder) { it.webKey }
        }.distinctUntilChanged()

    /** Catalog rows for Today: saved order, minus the ones the user hid. */
    fun homeEntries(): Flow<List<CatalogEntry>> =
        combine(allHomeEntries(), settings.map { it.hiddenCatalogs }.distinctUntilChanged()) { all, hidden ->
            all.filter { hidden[it.webKey] != true }
        }.distinctUntilChanged()

    /** Catalogs for Browse/See all. */
    fun browseEntries(): Flow<List<CatalogEntry>> = browseFlow()

    /** Catalogs that can be searched, as (source, catalog) pairs, optionally filtered to one type. */
    suspend fun searchEntries(type: String? = null): List<CatalogEntry> {
        val hideAdult = settings.first().hideAdultCatalogs
        return sources.enabledSources.first()
            .filter { !(hideAdult && it.manifest.isAdult) }
            .flatMap { s -> s.manifest.usableCatalogs.filter { it.supportsSearch && it.requiredExtraDefaults(setOf(Extras.SEARCH)) != null }.map { CatalogEntry(s, it) } }
            .filter { type == null || it.catalog.type.equals(type, true) }
    }

    // ------------------------------------------------------------------------------- home

    /**
     * Home rows with independent state per row. Emits a full snapshot (layout order) every time any row changes.
     * Emit on [refresh] to force-refetch everything while old content stays visible (`refreshing = true`).
     */
    fun homeRows(refresh: Flow<Int> = emptyFlow()): Flow<List<HomeRow>> = channelFlow {
        val rows = LinkedHashMap<String, HomeRow>()
        val mutex = Mutex()
        var order: List<String> = emptyList()
        var lastTick = Int.MIN_VALUE

        suspend fun publish() = send(order.mapNotNull { rows[it] })

        launch {
            retryRequests.collect { key ->
                val entry = mutex.withLock { rows[key]?.entry } ?: return@collect
                launch {
                    mutex.withLock { rows[key] = HomeRow(entry, RowState.Loading); publish() }
                    val st = fetchRow(entry, force = true)
                    mutex.withLock { if (rows.containsKey(key)) { rows[key] = HomeRow(entry, st); publish() } }
                }
            }
        }

        combine(homeEntries(), refresh.onStart { emit(0) }.distinctUntilChanged()) { e, t -> e to t }
            .collectLatest { (entries, tick) ->
                val force = lastTick != Int.MIN_VALUE && tick != lastTick
                lastTick = tick
                val toFetch = ArrayList<CatalogEntry>()
                mutex.withLock {
                    order = entries.map { it.key }
                    rows.keys.retainAll(order.toSet())
                    for (e in entries) {
                        val cur = rows[e.key]
                        val reusable = cur != null && !force && !cur.refreshing && cur.state is RowState.Loaded &&
                            cur.entry.source.manifestUrl == e.source.manifestUrl && cur.entry.source.fetchedAt == e.source.fetchedAt
                        if (reusable) { rows[e.key] = cur!!.copy(entry = e); continue }
                        rows[e.key] = if (cur == null) HomeRow(e, RowState.Loading) else cur.copy(entry = e, refreshing = true)
                        toFetch += e
                    }
                    publish()
                }
                coroutineScope {
                    for (e in toFetch) launch {
                        val st = fetchRow(e, force)
                        mutex.withLock { if (rows.containsKey(e.key)) { rows[e.key] = HomeRow(e, st); publish() } }
                    }
                }
            }
    }.conflate()

    /** Re-fetch a single failed row inside an active [homeRows] collection. */
    fun retryRow(key: String) {
        retryRequests.tryEmit(key)
    }

    private suspend fun fetchRow(entry: CatalogEntry, force: Boolean): RowState {
        val cap = settings.first().rowItemCount.count
        val extras = entry.catalog.requiredExtraDefaults() ?: return RowState.Failed("This catalog needs input")
        return client.catalog(entry.source.baseUrl, entry.catalog.type, entry.catalog.id, extras, force).fold(
            onSuccess = { RowState.Loaded(it.metas.distinctBy { m -> m.id }.take(cap)) },
            onFailure = { e -> RowState.Failed(e.toUserMessage(), (e as? AddonFailure)?.kind) },
        )
    }

    // ------------------------------------------------------------------------------ browse

    /**
     * Builds the extra map for a catalog request: required extras (first option), then [genre] (if the catalog
     * supports it), then [skip] (if supported and > 0). [more] allows other fixed-option extras chosen in the UI.
     */
    fun buildExtras(catalog: ManifestCatalog, genre: String? = null, skip: Int = 0, more: Map<String, String> = emptyMap()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        catalog.requiredExtraDefaults()?.let { out.putAll(it) }
        out.putAll(more)
        if (genre != null && catalog.supportsGenre) out[Extras.GENRE] = genre
        if (skip > 0 && catalog.supportsSkip) out[Extras.SKIP] = skip.toString()
        return out
    }

    /** One page of a catalog (the See all grid uses [pager] instead). */
    suspend fun loadPage(entry: CatalogEntry, genre: String? = null, skip: Int = 0, more: Map<String, String> = emptyMap(), force: Boolean = false): Result<List<MetaPreview>> {
        if (skip > 0 && !entry.catalog.supportsSkip) return Result.success(emptyList())
        return client.catalog(entry.source.baseUrl, entry.catalog.type, entry.catalog.id, buildExtras(entry.catalog, genre, skip, more), force)
            .map { r -> r.metas }
    }

    /** Create one per See-all screen / genre selection; collect [CatalogPager.state] and call loadNext(). */
    fun pager(entry: CatalogEntry, genre: String? = null, more: Map<String, String> = emptyMap(), force: Boolean = false): CatalogPager =
        CatalogPager(entry.catalog.supportsSkip) { skip -> loadPage(entry, genre, skip, more, force) }

    // ------------------------------------------------------------------------------ search

    /**
     * Searches every enabled, search-capable catalog in parallel. Debounce / minimum length is the caller's job;
     * cancelling the collection (flatMapLatest on new input) cancels in-flight requests, so no stale results.
     */
    fun search(query: String, type: String? = null, force: Boolean = false): Flow<SearchResults> = channelFlow {
        val q = query.trim()
        if (q.isEmpty()) { send(SearchResults(emptyList(), 0, 0)); return@channelFlow }
        val entries = searchEntries(type)
        val states = LinkedHashMap<String, RowState>()
        entries.forEach { states[it.key] = RowState.Loading }
        val mutex = Mutex()
        suspend fun publish() = send(
            SearchResults(
                groups = entries.map { SearchGroup(it, states.getValue(it.key)) },
                pending = states.values.count { it is RowState.Loading },
                totalCatalogs = entries.size,
            ),
        )
        mutex.withLock { publish() }
        coroutineScope {
            for (e in entries) launch {
                val extras = LinkedHashMap<String, String>()
                e.catalog.requiredExtraDefaults(setOf(Extras.SEARCH))?.let { extras.putAll(it) }
                extras[Extras.SEARCH] = q
                val st = client.catalog(e.source.baseUrl, e.catalog.type, e.catalog.id, extras, force).fold(
                    onSuccess = { RowState.Loaded(it.metas.distinctBy { m -> m.id }) },
                    onFailure = { err -> RowState.Failed(err.toUserMessage(), (err as? AddonFailure)?.kind) },
                )
                mutex.withLock { states[e.key] = st; publish() }
            }
        }
    }.conflate()
}

internal fun catalogEntries(srcs: List<Source>, prefs: List<CatalogPref>, hideAdult: Boolean, browse: Boolean): List<CatalogEntry> {
    val prefMap = prefs.associateBy { Triple(it.sourceId, it.type, it.catalogId) }
    val out = ArrayList<CatalogEntry>()
    for (s in srcs) {
        if (hideAdult && s.manifest.isAdult) continue
        for (c in s.manifest.usableCatalogs) {
            val eligible = c.isHomeEligible
            val pref = prefMap[Triple(s.id, c.type, c.id)]
            // Without a stored pref (e.g. not yet synced) fall back to the default: first three eligible on Home.
            // Today shows every eligible catalog; visibility and order are the user's layout settings, not per-catalog prefs.
            val visible = if (browse) (pref?.showInBrowse ?: true) && eligible else eligible
            if (visible) out += CatalogEntry(s, c)
        }
    }
    return out
}
