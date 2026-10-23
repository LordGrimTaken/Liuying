package com.liuying.app.data.plugins

import com.liuying.app.domain.plugins.PluginInfo
import com.liuying.app.domain.plugins.PluginLimits
import com.liuying.app.domain.plugins.PluginRunException

/** Downloads plugin scripts through the same guarded client the plugins use, with a small in-memory cache (never on disk). */
class PluginScripts(private val http: PluginHttp, private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(val at: Long, val text: String)
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 30
    }

    suspend fun load(plugin: PluginInfo): String {
        val key = plugin.scriptUrl + "#" + plugin.version
        synchronized(cache) { cache[key]?.takeIf { now() - it.at < TTL_MS }?.let { return it.text } }
        val r = http.newSession(maxRequests = 6).fetch(PluginHttp.FetchSpec(plugin.scriptUrl))
        if (r.status !in 200..299) throw PluginRunException("The script could not be downloaded (HTTP ${r.status}).")
        if (r.body.toByteArray().size > PluginLimits.MAX_SCRIPT_BYTES) throw PluginRunException("The script is larger than 2 MB.")
        if (r.body.isBlank()) throw PluginRunException("The script is empty.")
        synchronized(cache) { cache[key] = Entry(now(), r.body) }
        return r.body
    }

    private companion object { const val TTL_MS = 10 * 60 * 1000L }
}
