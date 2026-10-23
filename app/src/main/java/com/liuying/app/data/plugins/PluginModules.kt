package com.liuying.app.data.plugins

/**
 * The only two npm modules Nuvio-style plugins `require`: cheerio-without-node-native (HTML parsing) and crypto-js, shipped as
 * unmodified esbuild bundles in app resources (both MIT, see docs/plugins.md). They are handed to the sandbox as source text on
 * the first `require` and nothing else can be loaded this way.
 */
object PluginModules {
    private val files = mapOf("cheerio-without-node-native" to "cheerio.js", "crypto-js" to "crypto-js.js")
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun source(name: String?): String? {
        val file = files[name ?: return null] ?: return null
        cache[file]?.let { return it }
        val text = PluginModules::class.java.getResourceAsStream("/liuying-plugin-modules/$file")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        cache[file] = text
        return text
    }

    /** Characters outside the BMP become `🎬`-style escapes: the engine's UTF-8 bridge is only safe for BMP text. */
    fun escapeAstral(s: String): String {
        if (s.none { it.isSurrogate() }) return s
        val sb = StringBuilder(s.length + 32)
        for (c in s) if (c.isSurrogate()) sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        return sb.toString()
    }

    /** Non-ASCII characters of a JSON document (they can only sit inside strings) as `\uXXXX`. */
    fun asciiJson(s: String): String {
        if (s.all { it.code < 0x80 }) return s
        val sb = StringBuilder(s.length + 32)
        for (c in s) if (c.code >= 0x80) sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        return sb.toString()
    }
}
