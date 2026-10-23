package com.liuying.app.domain.plugins

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which URLs a plugin may reach through the host `fetch`. Pure rules (unit tested); the same address rule is applied
 * again after DNS resolution so a hostname that points at a private address cannot slip through.
 */
object PluginUrlSafety {
    const val MAX_REDIRECTS = 5
    const val MAX_URL_LENGTH = 4096

    sealed interface Verdict {
        data class Allowed(val url: HttpUrl) : Verdict
        data class Blocked(val reason: String) : Verdict
    }

    fun check(raw: String): Verdict {
        if (raw.length > MAX_URL_LENGTH) return Verdict.Blocked("The address is too long.")
        val url = raw.trim().toHttpUrlOrNull() ?: return Verdict.Blocked("Only http and https addresses are allowed.")
        // HttpUrl only parses http/https, so file:, javascript:, content:, ftp: etc. all end up here
        if (url.scheme != "http" && url.scheme != "https") return Verdict.Blocked("Only http and https addresses are allowed.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return Verdict.Blocked("Addresses with embedded credentials are not allowed.")
        return if (isBlockedHost(url.host)) Verdict.Blocked("That address points at this device or a private network.") else Verdict.Allowed(url)
    }

    /** Resolves a redirect `Location` against the current URL and applies [check] to it. */
    fun redirect(current: HttpUrl, location: String): Verdict {
        val next = current.resolve(location.trim()) ?: return Verdict.Blocked("The redirect address is not valid.")
        return check(next.toString())
    }

    /** Host-name level rule (literal addresses and names that can only mean "local"). */
    fun isBlockedHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") ||
            h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".intranet") || h.endsWith(".corp")
        ) return true
        if (h.contains(':')) { // IPv6 literal (HttpUrl strips the brackets)
            val a = parseLiteral(h) ?: return true
            return isBlockedAddress(a)
        }
        if (!h.contains('.')) return true // single-label names only resolve on a local network
        if (h.all { it.isDigit() || it == '.' }) {
            val parts = h.split('.')
            // anything numeric that is not a plain dotted quad (2130706433, 0177.0.0.1 ...) is ambiguous to the resolver
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it.startsWith("0")) || it.toInt() > 255 }) return true
            val a = parseLiteral(h) ?: return true
            return isBlockedAddress(a)
        }
        // hex forms such as 0x7f.1
        if (h.split('.').any { it.startsWith("0x") }) return true
        return false
    }

    private fun parseLiteral(h: String): InetAddress? = try {
        // only literals reach here, so this never performs a DNS lookup
        InetAddress.getByName(h)
    } catch (e: Exception) { null }

    /** True for loopback, wildcard, link-local, private, multicast, carrier-grade NAT, benchmarking, reserved and unique-local addresses. */
    fun isBlockedAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address.map { it.toInt() and 0xff }
        return when (a) {
            is Inet4Address -> b[0] == 0 || b[0] >= 240 ||
                (b[0] == 100 && b[1] in 64..127) || (b[0] == 192 && b[1] == 0 && b[2] == 0) ||
                (b[0] == 198 && b[1] in 18..19) || (b[0] == 192 && b[1] == 0 && b[2] == 2) ||
                (b[0] == 198 && b[1] == 51 && b[2] == 100) || (b[0] == 203 && b[1] == 0 && b[2] == 113)
            is Inet6Address -> (b[0] and 0xfe) == 0xfc || // fc00::/7 unique local
                (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) || // documentation
                (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b) // NAT64: could embed a private v4
            else -> true
        }
    }
}
