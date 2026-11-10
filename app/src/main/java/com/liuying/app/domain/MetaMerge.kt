package com.liuying.app.domain

import com.liuying.app.domain.model.Meta

/** "Merge, don't overwrite": [this] wins; blanks are filled from [other] and lists are unioned. */
fun Meta.mergedWith(other: Meta?): Meta {
    if (other == null) return this
    fun s(a: String?, b: String?) = if (a.isNullOrBlank()) b else a

    /** Both sides, [this] first, ignoring case so "Drama" does not appear twice. */
    fun unionCi(a: List<String>, b: List<String>): List<String> {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val seen = HashSet<String>(a.size + b.size)
        return (a + b).filter { seen.add(it.trim().lowercase()) }
    }

    /**
     * Episodes from both sources, keyed by season/episode (or by id when unnumbered). One source often carries a
     * partial list — a second source can add the seasons the first is missing — so first-wins would hide them.
     */
    fun unionVideos(a: List<com.liuying.app.domain.model.Video>, b: List<com.liuying.app.domain.model.Video>): List<com.liuying.app.domain.model.Video> {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val seen = HashSet<Pair<Int, Any>>(a.size + b.size)
        return (a + b).filter { v ->
            val key: Pair<Int, Any> = if (v.season != null && v.episode != null) v.season!! to v.episode!! else -1 to v.id
            seen.add(key)
        }
    }
    return copy(
        type = type.ifBlank { other.type },
        name = name.ifBlank { other.name },
        poster = s(poster, other.poster),
        posterShape = s(posterShape, other.posterShape),
        background = s(background, other.background),
        logo = s(logo, other.logo),
        description = s(description, other.description),
        releaseInfo = s(releaseInfo, other.releaseInfo),
        released = s(released, other.released),
        year = s(year, other.year),
        runtime = s(runtime, other.runtime),
        imdbRating = s(imdbRating, other.imdbRating),
        genres = unionCi(genres, other.genres),
        genre = unionCi(genre, other.genre),
        cast = unionCi(cast, other.cast),
        director = unionCi(director, other.director),
        directors = unionCi(directors, other.directors),
        writer = unionCi(writer, other.writer),
        writers = unionCi(writers, other.writers),
        country = s(country, other.country),
        language = s(language, other.language),
        awards = s(awards, other.awards),
        website = s(website, other.website),
        trailers = trailers.ifEmpty { other.trailers },
        trailerStreams = trailerStreams.ifEmpty { other.trailerStreams },
        links = links.ifEmpty { other.links },
        videos = unionVideos(videos, other.videos),
        behaviorHints = behaviorHints.copy(
            defaultVideoId = behaviorHints.defaultVideoId ?: other.behaviorHints.defaultVideoId,
            hasScheduledVideos = behaviorHints.hasScheduledVideos || other.behaviorHints.hasScheduledVideos,
        ),
    )
}
