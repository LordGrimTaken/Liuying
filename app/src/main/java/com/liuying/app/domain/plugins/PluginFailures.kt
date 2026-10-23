package com.liuying.app.domain.plugins

/** One line in the stream list for one or more plugins that failed for the same reason. */
data class PluginFailureGroup(val headline: String, val message: String, val names: List<String>)

object PluginFailures {
    private val reach = Regex("""^Could not reach (\S+)""")
    private val androidDns = Regex("""Unable to resolve host "([^"]+)"""")

    /** The host a DNS style failure is about, or null for any other message. */
    fun dnsHost(message: String): String? = reach.find(message)?.groupValues?.get(1) ?: androidDns.find(message)?.groupValues?.get(1)

    /** Groups (plugin name, message) pairs by what went wrong; DNS failures group by host, everything else by identical text. */
    fun group(failures: List<Pair<String, String>>): List<PluginFailureGroup> {
        val byKey = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (f in failures) byKey.getOrPut(dnsHost(f.second)?.let { "dns:$it" } ?: "msg:${f.second.trim()}") { ArrayList() } += f
        return byKey.map { (key, list) ->
            val names = list.map { it.first }.distinct()
            val message = list.first().second
            val headline = when {
                names.size == 1 -> "Plugin ${names[0]} failed: $message"
                key.startsWith("dns:") -> "${names.size} plugins could not reach ${key.removePrefix("dns:")}"
                else -> "${names.size} plugins failed: $message"
            }
            PluginFailureGroup(headline, message, names)
        }
    }
}
