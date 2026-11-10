package com.liuying.app.data.repo

import com.liuying.app.data.db.MetaCacheDao
import com.liuying.app.data.db.MetaCacheEntity
import com.liuying.app.data.remote.AddonClient
import com.liuying.app.data.remote.AddonErrorKind
import com.liuying.app.data.remote.AddonFailure
import com.liuying.app.data.remote.toUserMessage
import com.liuying.app.domain.Clock
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.mergedWith
import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.Resources
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.SourceError
import com.liuying.app.domain.model.StremioJson
import com.liuying.app.domain.model.declares
import com.liuying.app.domain.model.declaresAny
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CachedMeta(val meta: Meta, val fetchedAt: Long)

interface MetaStore {
    suspend fun get(type: String, id: String): CachedMeta?
    suspend fun put(type: String, id: String, meta: Meta, fetchedAt: Long)
    /** Keeps the cache from growing without bound (series metas with thousands of episodes are large). */
    suspend fun prune() {}
    /** A signal that changes when anything is written, so derived state (badges) can recompute. */
    fun observeChanges(): Flow<Int> = flowOf(0)
}

class RoomMetaStore(private val dao: MetaCacheDao) : MetaStore {
    override suspend fun get(type: String, id: String): CachedMeta? {
        val e = dao.get(type, id) ?: return null
        return try {
            CachedMeta(StremioJson.decodeFromString(Meta.serializer(), e.json), e.fetchedAt)
        } catch (ex: Exception) {
            null
        }
    }

    override suspend fun put(type: String, id: String, meta: Meta, fetchedAt: Long) {
        dao.upsert(MetaCacheEntity(type, id, StremioJson.encodeToString(Meta.serializer(), meta), fetchedAt))
    }

    override suspend fun prune() = dao.prune(KEEP)

    override fun observeChanges(): Flow<Int> = dao.observeCount()

    private companion object { const val KEEP = 400 }
}

/**
 * State of a title-details load. [meta] is the best available data (merged fresh answers, else cache, else the
 * list item's basic info) so the UI can render immediately and fill in as sources answer.
 */
data class MetaLoad(
    val meta: Meta?,
    val loading: Boolean,
    /** [meta] came (at least partly) from the local cache or list hint rather than a live answer. */
    val fromCache: Boolean = false,
    val errors: List<SourceError> = emptyList(),
    /** Finished, no source had anything and nothing is cached. */
    val notFound: Boolean = false,
) {
    /** Finished and every source that could have answered failed (offline-ish) rather than said "not found". */
    val allFailed: Boolean get() = !loading && meta != null && fromCache && errors.isNotEmpty()
}

class MetaRepository(
    private val sources: SourceProvider,
    private val client: AddonClient,
    private val store: MetaStore,
    private val clock: Clock = SystemClock,
) {
    /**
     * Sources that may answer: enabled, declare `meta` for this type + id prefix, in user order. If the title
     * came from [originSourceId] and that source declares meta at all, it is asked too even when the id prefix
     * does not match (spec edge case).
     */
    internal fun candidates(all: List<Source>, type: String, id: String, originSourceId: Long?): List<Source> =
        all.filter { it.enabled }.filter { s ->
            s.manifest.declares(Resources.META, type, id) ||
                (s.id == originSourceId && s.manifest.declaresAny(Resources.META))
        }

    /**
     * Cache first, then live answers from every candidate source in parallel. Primary data comes from the first
     * answering source in source order; blanks are filled from later ones ("merge, don't overwrite"). Emits after each
     * arrival; the last emission has loading = false. The merged result is cached.
     */
    fun meta(
        type: String,
        id: String,
        hint: MetaPreview? = null,
        originSourceId: Long? = null,
        force: Boolean = false,
    ): Flow<MetaLoad> = channelFlow {
        val cached = store.get(type, id)?.meta
        val hintMeta = hint?.let { Meta(id = it.id, type = it.type.ifBlank { type }, name = it.name, poster = it.poster, background = it.background,
            logo = it.logo, description = it.description, releaseInfo = it.releaseInfo, imdbRating = it.imdbRating, genres = it.genres) }
        val fallback = cached?.mergedWith(hintMeta) ?: hintMeta
        val cands = candidates(sources.sources.first(), type, id, originSourceId)

        send(MetaLoad(fallback, loading = cands.isNotEmpty(), fromCache = fallback != null))
        if (cands.isEmpty()) {
            send(MetaLoad(fallback, loading = false, fromCache = fallback != null, notFound = fallback == null))
            return@channelFlow
        }

        val slots = arrayOfNulls<Meta>(cands.size)
        val errors = ArrayList<SourceError>()
        var pending = cands.size
        val mutex = Mutex()
        coroutineScope {
            cands.forEachIndexed { i, s ->
                launch {
                    val r = client.meta(s.baseUrl, type, id, force)
                    mutex.withLock {
                        pending--
                        r.onSuccess { slots[i] = it }.onFailure { e ->
                            if ((e as? AddonFailure)?.kind != AddonErrorKind.Empty) errors += SourceError(s.id, s.displayName, e.toUserMessage())
                        }
                        val live = slots.filterNotNull().reduceOrNull { a, b -> a.mergedWith(b) }
                        val merged = live?.mergedWith(fallback)
                        if (pending == 0 && live != null) {
                            // a full disk or a locked database must not take the details screen down with it
                            try { store.put(type, id, live.mergedWith(cached), clock.nowMs()); store.prune() }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { /* the cache is best effort */ }
                        }
                        send(
                            MetaLoad(
                                meta = merged ?: fallback,
                                loading = pending > 0,
                                fromCache = live == null && fallback != null,
                                errors = errors.toList(),
                                notFound = pending == 0 && merged == null && fallback == null,
                            ),
                        )
                    }
                }
            }
        }
    }.conflate()

    /** Cached meta only (no network). */
    suspend fun cached(type: String, id: String): Meta? = store.get(type, id)?.meta

    /** Recomputed whenever the cache gains or loses an entry. */
    fun observeChanges(): Flow<Int> = store.observeChanges()

    /**
     * Meta for background use (Continue Watching cards): cache if younger than [maxAgeMs], else one live fetch
     * (merged like [meta]); falls back to stale cache on failure.
     */
    suspend fun getOrFetch(type: String, id: String, maxAgeMs: Long = 24L * 3600 * 1000): Meta? {
        val c = store.get(type, id)
        if (c != null && clock.nowMs() - c.fetchedAt < maxAgeMs) return c.meta
        var last: MetaLoad? = null
        meta(type, id).collect { last = it }
        return last?.meta ?: c?.meta
    }
}
