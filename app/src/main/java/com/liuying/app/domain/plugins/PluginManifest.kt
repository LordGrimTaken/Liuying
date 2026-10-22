package com.liuying.app.domain.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI

data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    /** Absolute http(s) URL of the script. It is stored, never fetched or run by this version. */
    val scriptUrl: String,
    /** Addon types the plugin answers for (empty = any). */
    val types: List<String>,
    /** Declared permissions, e.g. "network:example.com". Anything undeclared is denied. */
    val permissions: List<String>,
)

data class PluginRepoManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String?,
    val plugins: List<PluginInfo>,
)

/**
 * Repository manifest (JSON):
 * `{"id","name","version","description","author","plugins":[{"id","name","version","description","scriptUrl",
 * "types":[],"permissions":[]}]}`. Parsing is tolerant: bad plugin entries are skipped, not fatal.
 */
object PluginManifestParser {
    const val MAX_PLUGINS = 200
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(manifestUrl: String, body: String): Result<PluginRepoManifest> {
        val root = try { json.parseToJsonElement(body) } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("The repository did not return valid JSON."))
        } as? JsonObject ?: return Result.failure(IllegalArgumentException("The repository manifest should be a JSON object."))
        val id = root.str("id")?.takeIf { it.isNotBlank() }
        val name = root.str("name")?.takeIf { it.isNotBlank() }
        if (id == null || name == null) return Result.failure(IllegalArgumentException("The manifest is missing an id or a name."))
        val seen = HashSet<String>()
        val plugins = (root["plugins"] as? JsonArray).orEmpty().mapNotNull { parsePlugin(manifestUrl, it) }
            .filter { seen.add(it.id) }.take(MAX_PLUGINS)
        return Result.success(PluginRepoManifest(id, name, root.str("version").orEmpty(), root.str("description").orEmpty(), root.str("author"), plugins))
    }

    private fun parsePlugin(manifestUrl: String, el: JsonElement): PluginInfo? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val script = resolve(manifestUrl, o.str("scriptUrl") ?: o.str("script") ?: return null) ?: return null
        return PluginInfo(
            id = id, name = o.str("name")?.takeIf { it.isNotBlank() } ?: id, version = o.str("version").orEmpty(),
            description = o.str("description").orEmpty(), scriptUrl = script,
            types = o.strings("types"), permissions = o.strings("permissions").distinct(),
        )
    }

    internal fun resolve(base: String, ref: String): String? = try {
        val u = URI(base).resolve(ref.trim())
        if (u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrEmpty()) u.toString() else null
    } catch (e: Exception) { null }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.strings(k: String): List<String> =
        (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
}

object PluginPermissions {
    /** Plain-language line for the consent screen. */
    fun describe(p: String): String = when {
        p == "network" -> "Connect to any website"
        p.startsWith("network:") -> "Connect to ${p.removePrefix("network:")}"
        p == "storage" -> "Keep a small amount of its own data"
        else -> "Unknown permission \"$p\" (will be denied)"
    }

    val always = listOf(
        "Run for at most a few seconds and use limited memory",
        "Cannot read your files, accounts or other apps",
        "Cannot see your library, progress or other sources",
    )
}

data class PluginRunRequest(val type: String, val videoId: String, val title: String?)
data class PluginStreamCandidate(val name: String, val url: String, val description: String? = null)

/** Seam for the future sandboxed runner. Implementations must isolate failures and enforce time/memory/network limits. */
interface PluginRunner {
    val runnable: Boolean
    suspend fun run(plugin: PluginInfo, request: PluginRunRequest): Result<List<PluginStreamCandidate>>
}

object NoopPluginRunner : PluginRunner {
    override val runnable = false
    override suspend fun run(plugin: PluginInfo, request: PluginRunRequest): Result<List<PluginStreamCandidate>> = Result.success(emptyList())
}
