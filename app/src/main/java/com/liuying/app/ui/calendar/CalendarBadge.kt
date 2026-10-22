package com.liuying.app.ui.calendar

import com.liuying.app.AppContainer
import com.liuying.app.domain.CalendarFilters
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.Ux
import com.liuying.app.domain.model.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * L115: the number on the Calendar icon. Unwatched episodes of shelf series airing in the next 7 days, counted from
 * metadata already cached on the device (this never goes to the network by itself).
 */
fun AppContainer.calendarBadge(): Flow<Int> =
    combine(library.observeAll(), progress.observeAll()) { lib, prog -> lib to prog }
        .map { (lib, prog) ->
            val series = lib.filter { it.type.equals("series", true) }
            if (series.isEmpty()) return@map 0
            val metas = HashMap<String, Meta>()
            series.forEach { s -> metas.putIfAbsent(s.id, this.metas.cached(s.type, s.id) ?: return@forEach) }
            val agenda = Ux.buildAgenda(
                series.map { Ux.SeriesRef(it.id, it.type, it.name, it.poster) },
                { id -> metas[id]?.let { Ux.AgendaMeta(it.name, it.poster, it.videos) } },
                SystemClock.todayEpochDay(),
            )
            CalendarFilters.weekCount(agenda, CalendarFilters.watchedEpisodeKeys(prog))
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
