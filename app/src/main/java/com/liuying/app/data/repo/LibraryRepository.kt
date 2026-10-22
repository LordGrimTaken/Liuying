package com.liuying.app.data.repo

import com.liuying.app.data.db.LibraryDao
import com.liuying.app.data.db.LibraryEntity
import com.liuying.app.domain.Clock
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.model.LibraryItem
import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.MetaPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The saved titles (watchlist). Snapshots keep name and artwork so the shelf works offline. */
class LibraryRepository(private val dao: LibraryDao, private val clock: Clock = SystemClock) {
    /** Called after a change made by the user (not by sync), so the sync layer can push. */
    @Volatile var onLocalChange: (() -> Unit)? = null

    fun observeAll(): Flow<List<LibraryItem>> = dao.observeAll().map { l -> l.map { it.toItem() } }
    fun observeContains(type: String, id: String): Flow<Boolean> = dao.observeContains(type, id)
    suspend fun snapshot(): List<LibraryItem> = dao.getAll().map { it.toItem() }

    suspend fun add(meta: Meta) = add(
        LibraryItem(
            type = meta.type, id = meta.id, name = meta.title, poster = meta.poster, background = meta.background,
            year = meta.displayYear, genres = meta.allGenres, addedAt = clock.nowMs(),
        ),
    )

    suspend fun add(preview: MetaPreview, type: String = preview.type) = add(
        LibraryItem(type, preview.id, preview.title, preview.poster, preview.background, preview.displayYear, preview.genres, clock.nowMs()),
    )

    suspend fun add(item: LibraryItem) {
        dao.upsert(item.toEntity())
        onLocalChange?.invoke()
    }

    suspend fun remove(type: String, id: String) {
        dao.delete(type, id)
        onLocalChange?.invoke()
    }

    // Used by sync: no change callback, so applying remote state never triggers another push.
    suspend fun putFromSync(items: List<LibraryItem>) = items.forEach { dao.upsert(it.toEntity()) }
    suspend fun removeIdsFromSync(ids: Collection<String>) = ids.forEach { dao.deleteById(it) }
}

internal fun LibraryEntity.toItem() = LibraryItem(
    type = type, id = id, name = name, poster = poster, background = background, year = year,
    genres = genres.split('|').filter { it.isNotBlank() }, addedAt = addedAt,
)

internal fun LibraryItem.toEntity() = LibraryEntity(
    type = type, id = id, name = name, poster = poster, background = background, year = year,
    genres = this.genres.joinToString("|"), addedAt = addedAt,
)
