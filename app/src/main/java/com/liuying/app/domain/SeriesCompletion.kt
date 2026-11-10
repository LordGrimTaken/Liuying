package com.liuying.app.domain

import com.liuying.app.domain.model.ProgressEntry

/**
 * "Is this series finished?" — the single rule used by the shelf status, the poster badge and hide-watched.
 *
 * A series can only be called finished when the app actually knows its episode list (from the meta cache or a
 * successful fetch) and every episode in it is watched. Guessing from the rows on hand would call a
 * half-watched show done, which is worse than saying nothing.
 */
object SeriesCompletion {
    fun isComplete(rows: List<ProgressEntry>, knownEpisodes: Int?): Boolean {
        if (knownEpisodes == null || knownEpisodes <= 0) return false
        if (rows.isEmpty() || !rows.all { it.watched }) return false
        val watched = rows.filter { it.isEpisode && it.watched }.map { it.season to it.episode }.distinct().size
        return watched >= knownEpisodes
    }
}
