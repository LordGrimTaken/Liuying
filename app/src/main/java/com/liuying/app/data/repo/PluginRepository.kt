package com.liuying.app.data.repo

import com.liuying.app.data.db.PluginDao
import com.liuying.app.data.db.PluginEntity
import com.liuying.app.data.db.PluginRepoEntity
import com.liuying.app.domain.Clock
import com.liuying.app.domain.SystemClock
import com.liuying.app.domain.plugins.PluginInfo
import com.liuying.app.domain.plugins.PluginManifestParser
import com.liuying.app.domain.plugins.PluginRepoManifest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class PluginRepo(
    val id: Long, val manifestUrl: String, val name: String, val version: String, val description: String,
    val author: String?, val fetchedAt: Long, val consentedAt: Long?, val lastError: String?, val plugins: List<InstalledPlugin>,
) { val consented: Boolean get() = consentedAt != null }

data class InstalledPlugin(val repoId: Long, val info: PluginInfo, val enabled: Boolean)

/**
 * Plugin repositories: manifests are fetched and stored; scripts are never downloaded or executed here (see
 * PluginRunner). Plugins start disabled, and enabling needs the repository's consent first.
 */
class PluginRepository(
    private val dao: PluginDao,
    private val fetch: suspend (String) -> Result<String>,
    private val clock: Clock = SystemClock,
) {
    val repos: Flow<List<PluginRepo>> = combine(dao.observeRepos(), dao.observePlugins()) { r, p ->
        r.map { repo ->
            PluginRepo(repo.id, repo.manifestUrl, repo.name, repo.version, repo.description, repo.author, repo.fetchedAt, repo.consentedAt, repo.lastError,
                p.filter { it.repoId == repo.id }.map { it.toPlugin() })
        }
    }

    private suspend fun load(url: String): Result<PluginRepoManifest> {
        val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        if (u.length > 2048) return Result.failure(IllegalArgumentException("That link is too long."))
        return fetch(u).mapCatching { PluginManifestParser.parse(u, it).getOrThrow() }
    }

    suspend fun add(url: String): Result<Long> {
        val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        dao.getRepoByUrl(u)?.let { return Result.failure(IllegalStateException("This repository is already added.")) }
        val m = load(u).getOrElse { return Result.failure(it) }
        val id = dao.insertRepo(PluginRepoEntity(manifestUrl = u, repoId = m.id, name = m.name, version = m.version, description = m.description,
            author = m.author, fetchedAt = clock.nowMs(), consentedAt = null, lastError = null))
        m.plugins.forEach { dao.upsertPlugin(it.toEntity(id, false)) }
        return Result.success(id)
    }

    /** Re-fetches the manifest. Existing enabled flags are kept; plugins that vanished are removed. */
    suspend fun refresh(id: Long): Result<Unit> {
        val repo = dao.getRepo(id) ?: return Result.failure(IllegalStateException("Repository not found."))
        val m = load(repo.manifestUrl).getOrElse { dao.setRepoError(id, it.message); return Result.failure(it) }
        val existing = dao.getPlugins(id).associateBy { it.pluginId }
        dao.updateRepo(repo.copy(repoId = m.id, name = m.name, version = m.version, description = m.description, author = m.author, fetchedAt = clock.nowMs(), lastError = null))
        val keep = m.plugins.map { it.id }.toSet()
        existing.keys.filter { it !in keep }.forEach { dao.deletePlugin(id, it) }
        m.plugins.forEach { p ->
            val old = existing[p.id]
            // If an update asks for different permissions the earlier consent no longer covers it.
            val enabled = old?.enabled == true && old.permissions == p.permissions.joinToString("\n")
            dao.upsertPlugin(p.toEntity(id, enabled))
        }
        return Result.success(Unit)
    }

    suspend fun remove(id: Long) = dao.deleteRepo(id)

    suspend fun recordConsent(id: Long) = dao.setConsent(id, clock.nowMs())

    /** Returns false (and changes nothing) when enabling without consent. */
    suspend fun setEnabled(repoId: Long, pluginId: String, enabled: Boolean): Boolean {
        if (enabled && dao.getRepo(repoId)?.consentedAt == null) return false
        dao.setPluginEnabled(repoId, pluginId, enabled)
        return true
    }
}

private fun PluginEntity.toPlugin() = InstalledPlugin(
    repoId, PluginInfo(pluginId, name, version, description, scriptUrl, types.lines().filter { it.isNotBlank() }, permissions.lines().filter { it.isNotBlank() }), enabled,
)

private fun PluginInfo.toEntity(repoId: Long, enabled: Boolean) =
    PluginEntity(repoId, id, name, version, description, scriptUrl, types.joinToString("\n"), permissions.joinToString("\n"), enabled)
