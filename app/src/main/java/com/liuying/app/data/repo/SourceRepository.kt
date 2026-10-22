package com.liuying.app.data.repo

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.liuying.app.data.db.CatalogPrefEntity
import com.liuying.app.data.db.SourceDao
import com.liuying.app.data.db.SourceEntity
import com.liuying.app.data.remote.AddonClient
import com.liuying.app.data.remote.AddonErrorKind
import com.liuying.app.data.remote.AddonFailure
import com.liuying.app.data.remote.AddonUrls
import com.liuying.app.data.remote.toUserMessage
import com.liuying.app.domain.Clock
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.model.CatalogPref
import com.liuying.app.domain.model.Manifest
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.StremioJson
import com.liuying.app.domain.model.isAdult
import com.liuying.app.domain.model.isHomeEligible
import com.liuying.app.domain.model.providesCatalogs
import com.liuying.app.domain.model.usableCatalogs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What other repositories need from the source store (also makes them testable without Room). */
interface SourceProvider {
    /** All installed sources, in user order. */
    val sources: Flow<List<Source>>
    val catalogPrefs: Flow<List<CatalogPref>>
}

val SourceProvider.enabledSources: Flow<List<Source>>
    get() = sources.map { list -> list.filter { it.enabled } }

sealed interface InstallWarning {
    /** Plain-text transport: needs an explicit extra confirmation. */
    data object InsecureHttp : InstallWarning
    /** behaviorHints.adult: needs an explicit extra confirmation. */
    data object Adult : InstallWarning
    data object ConfigurationRequired : InstallWarning
    data class DuplicateUrl(val existing: Source) : InstallWarning
    data class DuplicateManifestId(val existing: Source) : InstallWarning
    data class TooManySources(val count: Int) : InstallWarning
}

/** The "what will I get" card shown before confirming an install. */
data class InstallPreview(
    val manifestUrl: String,
    val manifest: Manifest,
    val rawJson: String,
    val host: String,
    val warnings: List<InstallWarning>,
) {
    val existing: Source?
        get() = warnings.firstNotNullOfOrNull {
            when (it) {
                is InstallWarning.DuplicateUrl -> it.existing
                is InstallWarning.DuplicateManifestId -> it.existing
                else -> null
            }
        }
    val isDuplicate: Boolean get() = existing != null
    val requiresExtraConfirmation: Boolean
        get() = warnings.any { it is InstallWarning.InsecureHttp || it is InstallWarning.Adult }
}

class SourceRepository(
    private val db: RoomDatabase,
    private val dao: SourceDao,
    private val client: AddonClient,
    private val clock: Clock = SystemClock,
) : SourceProvider {

    override val sources: Flow<List<Source>> = dao.observeAll().map { list -> list.map { it.toSource() } }

    override val catalogPrefs: Flow<List<CatalogPref>> = dao.observePrefs().map { list ->
        list.map { CatalogPref(it.sourceId, it.type, it.catalogId, it.showOnHome, it.showInBrowse) }
    }

    suspend fun getAll(): List<Source> = dao.getAll().map { it.toSource() }
    suspend fun get(id: Long): Source? = dao.getById(id)?.toSource()

    // ------------------------------------------------------------------------------- install

    /**
     * Fetches and validates the manifest behind a pasted link (https, http, stremio://, bare host) and
     * returns the preview with warnings. Nothing is stored. Failure carries an [AddonFailure].
     */
    suspend fun preview(input: String, allowHttp: Boolean = true): Result<InstallPreview> {
        val normalized = AddonUrls.normalizeManifestUrl(input).getOrElse { return Result.failure(it) }
        if (!allowHttp && normalized.startsWith("http://", ignoreCase = true)) {
            return Result.failure(AddonFailure(AddonErrorKind.InvalidUrl, "Plain HTTP sources are turned off in settings"))
        }
        val fetched = client.fetchManifest(normalized, force = true).getOrElse { return Result.failure(it) }
        val warnings = ArrayList<InstallWarning>()
        if (fetched.manifestUrl.startsWith("http://", ignoreCase = true)) warnings += InstallWarning.InsecureHttp
        if (fetched.manifest.isAdult) warnings += InstallWarning.Adult
        if (fetched.manifest.behaviorHints.configurationRequired) warnings += InstallWarning.ConfigurationRequired
        dao.getByUrl(fetched.manifestUrl)?.let { warnings += InstallWarning.DuplicateUrl(it.toSource()) }
            ?: dao.getByManifestId(fetched.manifest.id)?.let { warnings += InstallWarning.DuplicateManifestId(it.toSource()) }
        val count = dao.getAll().size
        if (count >= SOFT_CAP) warnings += InstallWarning.TooManySources(count)
        return Result.success(
            InstallPreview(fetched.manifestUrl, fetched.manifest, fetched.rawJson, AddonUrls.host(fetched.manifestUrl), warnings),
        )
    }

    /**
     * Installs at the end of the list, enabled. For a duplicate, [replace] must be true: the stored manifest is
     * updated while order and enabled state are kept. Http/adult previews need [confirmRisky] = true.
     */
    suspend fun install(preview: InstallPreview, replace: Boolean = false, confirmRisky: Boolean = false): Result<Source> {
        if (preview.requiresExtraConfirmation && !confirmRisky) {
            return Result.failure(AddonFailure(AddonErrorKind.Unknown, "This source needs an explicit confirmation to install"))
        }
        val existing = preview.existing
        val now = clock.nowMs()
        return runCatchingNonCancel {
            db.withTransaction {
                if (existing != null) {
                    if (!replace) throw AddonFailure(AddonErrorKind.Unknown, "Already installed")
                    val entity = dao.getById(existing.id)!!.copy(
                        manifestUrl = preview.manifestUrl,
                        manifestId = preview.manifest.id,
                        manifestJson = preview.rawJson,
                        fetchedAt = now,
                        lastError = null,
                        lastErrorAt = null,
                    )
                    dao.update(entity)
                    syncCatalogPrefs(entity.id, preview.manifest)
                    entity.toSource()
                } else {
                    val entity = SourceEntity(
                        manifestUrl = preview.manifestUrl,
                        manifestId = preview.manifest.id,
                        manifestJson = preview.rawJson,
                        fetchedAt = now,
                        enabled = true,
                        sortOrder = dao.maxOrder() + 1,
                        installedAt = now,
                    )
                    val id = dao.insert(entity)
                    syncCatalogPrefs(id, preview.manifest)
                    entity.copy(id = id).toSource()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------- management

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    suspend fun setNameOverride(id: Long, name: String?) = dao.setNameOverride(id, name?.takeIf { it.isNotBlank() })

    /** Persists a new full ordering. Unknown ids are ignored; sources not listed keep their relative order after. */
    suspend fun reorder(orderedIds: List<Long>) = db.withTransaction {
        val all = dao.getAll().map { it.id }
        val finalOrder = orderedIds.filter { it in all }.distinct() + all.filter { it !in orderedIds }
        finalOrder.forEachIndexed { i, id -> dao.setOrder(id, i) }
    }

    /** Moves one source to [toIndex] in the current order. */
    suspend fun move(id: Long, toIndex: Int) {
        val ids = dao.getAll().map { it.id }.toMutableList()
        if (!ids.remove(id)) return
        ids.add(toIndex.coerceIn(0, ids.size), id)
        reorder(ids)
    }

    /**
     * Removes the source and its catalog prefs. Library items, watch progress and cached meta are deliberately
     * left alone: they belong to titles, not sources.
     */
    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun setCatalogHome(sourceId: Long, type: String, catalogId: String, show: Boolean) =
        updatePref(sourceId, type, catalogId) { it.copy(showOnHome = show) }

    suspend fun setCatalogBrowse(sourceId: Long, type: String, catalogId: String, show: Boolean) =
        updatePref(sourceId, type, catalogId) { it.copy(showInBrowse = show) }

    private suspend fun updatePref(sourceId: Long, type: String, catalogId: String, f: (CatalogPrefEntity) -> CatalogPrefEntity) {
        val cur = dao.getPrefs(sourceId).firstOrNull { it.type == type && it.catalogId == catalogId }
            ?: CatalogPrefEntity(sourceId, type, catalogId, showOnHome = true, showInBrowse = true)
        dao.upsertPref(f(cur))
    }

    // --------------------------------------------------------------------------- refreshing

    /** Re-fetches one manifest. On failure the stored manifest stays and the error is recorded. */
    suspend fun refreshManifest(id: Long): Result<Source> {
        val entity = dao.getById(id) ?: return Result.failure(AddonFailure(AddonErrorKind.Unknown, "Source not found"))
        val now = clock.nowMs()
        val fetched = client.fetchManifestFromUrl(entity.manifestUrl, force = true)
        return fetched.fold(
            onSuccess = { f ->
                db.withTransaction {
                    val updated = entity.copy(
                        manifestId = f.manifest.id, manifestJson = f.rawJson, fetchedAt = now, lastError = null, lastErrorAt = null,
                    )
                    dao.update(updated)
                    syncCatalogPrefs(id, f.manifest)
                    Result.success(updated.toSource())
                }
            },
            onFailure = { e ->
                dao.setError(id, e.toUserMessage(), now)
                Result.failure(e)
            },
        )
    }

    /** App-start refresh: only sources not refreshed within [maxAgeMs] (default 12 h). Returns per-source results. */
    suspend fun refreshStale(maxAgeMs: Long = 12L * 3600 * 1000): Map<Long, Result<Source>> {
        val now = clock.nowMs()
        val stale = dao.getAll().filter { now - it.fetchedAt >= maxAgeMs }
        // QA #32: refresh in parallel, at most 4 at a time
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        return kotlinx.coroutines.coroutineScope {
            stale.map { e -> async { gate.withPermit { e.id to refreshManifest(e.id) } } }.awaitAll().toMap()
        }
    }

    /**
     * Keeps catalog prefs in line with the manifest: new catalogs appear on Home for the first three
     * home-eligible ones and off beyond; catalogs that vanished are dropped; existing choices are kept.
     */
    internal suspend fun syncCatalogPrefs(sourceId: Long, manifest: Manifest) {
        val existing = dao.getPrefs(sourceId)
        val catalogs = manifest.usableCatalogs
        val keys = catalogs.map { it.type to it.id }.toSet()
        existing.filter { (it.type to it.catalogId) !in keys }.forEach { dao.deletePref(sourceId, it.type, it.catalogId) }
        var eligibleSeen = existing.count { (it.type to it.catalogId) in keys && it.showOnHome }
        for (c in catalogs) {
            if (existing.any { it.type == c.type && it.catalogId == c.id }) continue
            val eligible = c.isHomeEligible
            val home = eligible && eligibleSeen < DEFAULT_HOME_CATALOGS
            if (home) eligibleSeen++
            dao.upsertPref(CatalogPrefEntity(sourceId, c.type, c.id, showOnHome = home, showInBrowse = true))
        }
    }

    companion object {
        const val SOFT_CAP = 50
        const val DEFAULT_HOME_CATALOGS = 3
    }
}

internal inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

internal fun SourceEntity.toSource(): Source {
    val manifest = try {
        StremioJson.decodeFromString(Manifest.serializer(), manifestJson)
    } catch (e: Exception) {
        Manifest(id = manifestId, name = AddonUrls.host(manifestUrl))
    }
    return Source(
        id = id, manifestUrl = manifestUrl, manifest = manifest, enabled = enabled, order = sortOrder,
        installedAt = installedAt, fetchedAt = fetchedAt, lastError = lastError, lastErrorAt = lastErrorAt, nameOverride = nameOverride,
    )
}
