package com.liuying.app.domain

import com.liuying.app.domain.model.Meta

/** "Merge, don't overwrite": [this] wins; blanks and empty lists are filled from [other]. */
fun Meta.mergedWith(other: Meta?): Meta {
    if (other == null) return this
    fun s(a: String?, b: String?) = if (a.isNullOrBlank()) b else a
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
        genres = genres.ifEmpty { other.genres },
        genre = genre.ifEmpty { other.genre },
        cast = cast.ifEmpty { other.cast },
        director = director.ifEmpty { other.director },
        directors = directors.ifEmpty { other.directors },
        writer = writer.ifEmpty { other.writer },
        writers = writers.ifEmpty { other.writers },
        country = s(country, other.country),
        language = s(language, other.language),
        awards = s(awards, other.awards),
        website = s(website, other.website),
        trailers = trailers.ifEmpty { other.trailers },
        trailerStreams = trailerStreams.ifEmpty { other.trailerStreams },
        links = links.ifEmpty { other.links },
        videos = videos.ifEmpty { other.videos },
        behaviorHints = behaviorHints.copy(
            defaultVideoId = behaviorHints.defaultVideoId ?: other.behaviorHints.defaultVideoId,
            hasScheduledVideos = behaviorHints.hasScheduledVideos || other.behaviorHints.hasScheduledVideos,
        ),
    )
}
