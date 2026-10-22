package com.liuying.app.domain

object EpisodeLabels {
    fun code(season: Int?, episode: Int?): String = when {
        season == null || episode == null -> ""
        season == 0 -> "Special $episode"
        else -> "S$season E$episode"
    }

    fun line(season: Int?, episode: Int?, title: String?): String {
        val c = code(season, episode)
        return listOfNotNull(c.takeIf { it.isNotEmpty() }, title?.takeIf { it.isNotBlank() }).joinToString(" - ")
    }

    /** "42 min left" / "1 h 5 min left". */
    fun remaining(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return if (min < 60) "$min min left" else "${min / 60} h ${min % 60} min left"
    }
}
