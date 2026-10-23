package com.liuying.app.data.plugins

import com.liuying.app.domain.plugins.PluginInfo
import com.liuying.app.domain.plugins.PluginLimits
import com.liuying.app.domain.plugins.PluginRunException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads plugin scripts through the same guarded client the plugins use, with an in-memory cache (never on disk) that every
 * run shares. At most [MAX_DOWNLOADS] downloads run at once (66 plugins asking for one host together would otherwise flood it and
 * the resolver), the same script is never fetched twice concurrently, and a failed host is not tried again for 30 s
 * (that part lives in [ResilientDns], so it covers the plugins' own requests too).
 */
class PluginScripts(private val http: PluginHttp, private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(val at: Long, val text: String)
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0L
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val downloads = Semaphore(MAX_DOWNLOADS)

    private fun cached(key: String): String? = synchronized(cache) { cache[key]?.takeIf { now() - it.at < TTL_MS }?.text }

    suspend fun load(plugin: PluginInfo): String {
        val key = plugin.scriptUrl + "#" + plugin.version
        cached(key)?.let { return it }
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            cached(key)?.let { return it }
            val r = downloads.withPermit { http.newSession(maxRequests = 6).fetch(PluginHttp.FetchSpec(plugin.scriptUrl)) }
            if (r.status !in 200..299) throw PluginRunException("The script could not be downloaded (HTTP ${r.status}).")
            if (r.body.toByteArray().size > PluginLimits.MAX_SCRIPT_BYTES) throw PluginRunException("The script is larger than 2 MB.")
            if (r.body.isBlank()) throw PluginRunException("The script is empty.")
            synchronized(cache) {
                cache.remove(key)?.let { bytes -= it.text.length }
                cache[key] = Entry(now(), r.body); bytes += r.body.length
                val it = cache.entries.iterator()
                while ((cache.size > MAX_ENTRIES || bytes > MAX_CHARS) && cache.size > 1 && it.hasNext()) { val e = it.next(); bytes -= e.value.text.length; it.remove() }
            }
            r.body
        }
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_DOWNLOADS = 4
        const val MAX_ENTRIES = 80
        const val MAX_CHARS = 12L * 1024 * 1024
    }
}
