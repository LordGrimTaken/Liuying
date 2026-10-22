package com.liuying.app.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure URL helpers for the Stremio addon protocol. */
object AddonUrls {
    private const val MANIFEST = "manifest.json"
    private val manifestSuffix = Regex("/manifest\\.json$", RegexOption.IGNORE_CASE)

    /**
     * Accepts https/http links, stremio:// install links (converted to https) and bare hosts/paths
     * (https prepended). A link that does not end in manifest.json is rejected, never guessed.
     */
    fun normalizeManifestUrl(input: String): Result<String> {
        var s = input.trim().trim('"', '\'', '<', '>').trim()
        if (s.isEmpty()) return failure(AddonErrorKind.InvalidUrl, "Empty link")
        val lower = s.lowercase()
        s = when {
            lower.startsWith("stremio://") -> "https://" + s.substring("stremio://".length)
            lower.startsWith("stremio:") -> "https://" + s.substring("stremio:".length).trimStart('/')
            lower.startsWith("https://") || lower.startsWith("http://") -> s
            s.contains("://") -> return failure(AddonErrorKind.InvalidUrl, "Unsupported scheme")
            else -> "https://" + s.trimStart('/')
        }
        val url = s.toHttpUrlOrNull() ?: return failure(AddonErrorKind.InvalidUrl, "Malformed link")
        val last = url.pathSegments.lastOrNull().orEmpty()
        if (!last.equals(MANIFEST, ignoreCase = true)) {
            return failure(AddonErrorKind.NotManifestUrl, "Link does not end with manifest.json")
        }
        return Result.success(url.newBuilder().fragment(null).build().toString())
    }

    /** Manifest url -> base url (no trailing slash, no query/fragment, manifest filename removed). */
    fun baseUrl(manifestUrl: String): String =
        manifestUrl.substringBefore('#').substringBefore('?').replace(manifestSuffix, "").trimEnd('/')

    fun host(manifestUrl: String): String =
        manifestUrl.toHttpUrlOrNull()?.host ?: manifestUrl.substringAfter("://").substringBefore('/')

    /** Settings URL for configurable addons: base + /configure. */
    fun configureUrl(manifestUrl: String): String = baseUrl(manifestUrl) + "/configure"

    /**
     * {base}/{resource}/{type}/{id}[/{extras}].json
     * Extras are `key=value` pairs, URL-encoded, joined with '&'. Blank values are omitted.
     * ':' is left readable in ids (series:1:2) as in the protocol examples.
     */
    fun resourceUrl(
        baseUrl: String,
        resource: String,
        type: String,
        id: String,
        extras: Map<String, String> = emptyMap(),
    ): String {
        val sb = StringBuilder(baseUrl.trimEnd('/'))
        sb.append('/').append(encode(resource)).append('/').append(encode(type)).append('/')
            .append(encode(id, keepColon = true))
        val extraSeg = encodeExtras(extras)
        if (extraSeg.isNotEmpty()) sb.append('/').append(extraSeg)
        sb.append(".json")
        return sb.toString()
    }

    fun encodeExtras(extras: Map<String, String>): String =
        extras.filter { it.value.isNotBlank() }
            .entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }

    /** Percent-encoding of everything except RFC 3986 unreserved characters (UTF-8). */
    fun encode(s: String, keepColon: Boolean = false): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if ((ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '.' || ch == '_' || ch == '~' ||
                (keepColon && ch == ':')
            ) sb.append(ch) else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }

    private fun failure(kind: AddonErrorKind, msg: String): Result<String> =
        Result.failure(AddonFailure(kind, msg))
}
