package com.liuying.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuying.app.AppContainer
import com.liuying.app.domain.CalendarFilters
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.Ux
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.Meta
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

data class CalendarUi(val loading: Boolean = true, val seriesOnShelf: Int = 0, val agenda: Ux.Agenda? = null)

/** Upcoming and recently aired episodes of the series on the shelf, from the metadata the sources report. */
class CalendarViewModel(private val c: AppContainer) : ViewModel() {
    private val tick = MutableStateFlow(0)

    val ui: StateFlow<CalendarUi> = kotlinx.coroutines.flow.combine(c.library.observeAll(), tick) { lib, _ -> lib }
        .transformLatest { lib ->
            val series = lib.filter { it.type.equals("series", true) }
            if (series.isEmpty()) { emit(CalendarUi(false, 0, null)); return@transformLatest }
            val metas = HashMap<String, Meta>()
            series.forEach { s -> c.metas.cached(s.type, s.id)?.let { metas[s.id] = it } }
            emit(CalendarUi(true, series.size, agenda(series, metas)))
            val missing = series.filter { it.id !in metas }
            if (missing.isNotEmpty()) {
                coroutineScope { missing.map { s -> async { s to c.metas.getOrFetch(s.type, s.id) } }.awaitAll() }
                    .forEach { (s, m) -> if (m != null) metas[s.id] = m }
            }
            emit(CalendarUi(false, series.size, agenda(series, metas)))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUi())

    private fun agenda(series: List<LibraryItem>, metas: Map<String, Meta>): Ux.Agenda =
        Ux.buildAgenda(
            series.map { Ux.SeriesRef(it.id, it.type, it.name, it.poster) },
            { id -> metas[id]?.let { Ux.AgendaMeta(it.name, it.poster, it.videos) } },
            SystemClock.todayEpochDay(),
        )

    /** L115 */
    val hideWatched: StateFlow<Boolean> = c.settings.settings.map { it.calendarHideWatched }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val watchedEpisodes: StateFlow<Set<String>> = c.progress.observeAll().map { CalendarFilters.watchedEpisodeKeys(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    fun setHideWatched(on: Boolean) { viewModelScope.launch { c.settings.update { it.copy(calendarHideWatched = on) } } }

    fun refresh() { tick.value += 1 }
}
