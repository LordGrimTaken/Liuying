package com.liuying.app.data.repo

import com.liuying.app.data.plugins.TmdbResolver
import com.liuying.app.domain.model.Manifest
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.Stream
import com.liuying.app.domain.model.StreamBehaviorHints
import com.liuying.app.domain.plugins.PluginInfo
import com.liuying.app.domain.plugins.PluginRunRequest
import com.liuying.app.domain.plugins.PluginRunner
import com.liuying.app.domain.plugins.PluginStream
import com.liuying.app.domain.plugins.PluginTargets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What [StreamRepository] needs to list plugin results next to addon results. */
interface PluginStreamHost {
    /** Plugins to ask for this title: master switch on, repository consented, plugin enabled, type supported. */
    suspend fun sources(type: String, videoId: String): List<Source>
    suspend fun run(source: Source, type: String, videoId: String): Result<List<Stream>>
}

data class PluginTestResult(val ok: Boolean, val streamCount: Int, val error: String?, val elapsedMs: Long)

class PluginStreams(
    private val plugins: PluginRepository,
    private val runner: PluginRunner,
    private val masterSwitch: suspend () -> Boolean,
    private val tmdb: TmdbResolver,
    private val clock: () -> Long = System::currentTimeMillis,
) : PluginStreamHost {
    private val gate = Semaphore(3)
    private val byId = java.util.concurrent.ConcurrentHashMap<Long, InstalledPlugin>()

    override suspend fun sources(type: String, videoId: String): List<Source> {
        if (!runner.runnable || !masterSwitch()) return emptyList()
        val target = PluginTargets.parse(type, videoId) ?: return emptyList()
        val out = LinkedHashMap<Long, Source>()
        var order = 10_000
        for (repo in plugins.repos.first()) {
            if (!repo.consented) continue
            for (p in repo.plugins) {
                if (!p.enabled || !PluginTargets.supports(p.info.types, target.mediaType)) continue
                val id = sourceId(repo.id, p.info.id)
                if (id in out) continue
                byId[id] = p
                out[id] = Source(
                    id = id, manifestUrl = p.info.scriptUrl, manifest = Manifest(id = "plugin:${repo.id}/${p.info.id}", name = p.info.name, version = p.info.version),
                    enabled = true, order = order++, installedAt = 0, fetchedAt = 0, isPlugin = true,
                )
            }
        }
        return out.values.toList()
    }

    override suspend fun run(source: Source, type: String, videoId: String): Result<List<Stream>> {
        val p = byId[source.id] ?: return Result.failure(IllegalStateException("The plugin is no longer available."))
        val target = PluginTargets.parse(type, videoId) ?: return Result.success(emptyList())
        val id = if (target.isImdb) tmdb.resolve(target.rawId, target.mediaType) else target.rawId
        return gate.withPermit { runner.run(p.info, PluginRunRequest(id, target.mediaType, target.season, target.episode)) }
            .map { list -> list.map(::toStream) }
    }

    /** The "Test plugin" button: one run with a well-known sample title, regardless of the plugin being enabled. */
    suspend fun test(plugin: PluginInfo): PluginTestResult {
        val t0 = clock()
        if (!masterSwitch()) return PluginTestResult(false, 0, "Turn on Run plugins first.", 0)
        val tvOnly = plugin.types.isNotEmpty() && "movie" !in plugin.types.map { it.lowercase() }
        val req = if (tvOnly) PluginRunRequest("1396", "tv", 1, 1) else PluginRunRequest("603", "movie")
        val r = gate.withPermit { runner.run(plugin, req) }
        return r.fold(
            { PluginTestResult(true, it.size, null, clock() - t0) },
            { PluginTestResult(false, 0, it.message ?: "The plugin failed.", clock() - t0) },
        )
    }

    companion object {
        /** Negative, so it can never collide with an addon source id (those come from Room's positive autoincrement). */
        fun sourceId(repoId: Long, pluginId: String): Long = -(1L + ((repoId * 31 + pluginId.hashCode()).toLong() and 0x3fffffffL))

        fun toStream(s: PluginStream): Stream = Stream(
            url = s.url, name = s.name, title = s.title,
            description = listOfNotNull(s.title, s.quality).joinToString("\n").ifBlank { null },
            behaviorHints = StreamBehaviorHints(headers = s.headers, notWebReady = s.headers.isNotEmpty()),
        )
    }
}
