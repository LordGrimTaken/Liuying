package com.liuying.app.data.plugins

import com.liuying.app.domain.plugins.PluginUrlSafety
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A name that resolved to a private, loopback or link-local address. Permanent: never retried and never sent to the fallback. */
class BlockedAddressException(message: String) : UnknownHostException(message)

/**
 * The plugin client's resolver. Android's system resolver sometimes answers "No address associated with hostname" for a
 * host that resolved a moment ago, so a lookup is: positive cache (5 min, shared by every concurrent plugin run) ->
 * negative cache (30 s, so a dead host is not hammered) -> [attempts] tries of [primary] with a short backoff -> [fallback]
 * (DNS over HTTPS). EVERY answer, from any of these, goes through [isBlocked] before it is cached or returned.
 */
class ResilientDns(
    private val primary: Dns,
    private val fallback: Dns? = null,
    private val isBlocked: (InetAddress) -> Boolean = PluginUrlSafety::isBlockedAddress,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val attempts: Int = 3,
    private val backoffMs: Long = 150,
    private val positiveTtlMs: Long = 5 * 60_000L,
    private val negativeTtlMs: Long = 30_000L,
) : Dns {
    private class Hit(val at: Long, val addresses: List<InetAddress>)
    private class Miss(val at: Long, val message: String, val blocked: Boolean)

    private val hits = ConcurrentHashMap<String, Hit>()
    private val misses = ConcurrentHashMap<String, Miss>()
    private val locks = ConcurrentHashMap<String, Any>()

    override fun lookup(hostname: String): List<InetAddress> {
        val key = hostname.trim().trimEnd('.').lowercase()
        cached(key)?.let { return it }
        // one resolution per host at a time: concurrent plugin runs wait for it and then read the cache
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            cached(key)?.let { return it }
            return resolve(hostname, key)
        }
    }

    /** Forget recorded failures (the user pressed "Retry failed plugins"); answers that worked stay cached. */
    fun clearFailures() { misses.clear() }

    fun clear() { hits.clear(); misses.clear() }

    private fun cached(key: String): List<InetAddress>? {
        val now = clock()
        hits[key]?.let { if (now - it.at < positiveTtlMs) return it.addresses else hits.remove(key) }
        misses[key]?.let {
            if (now - it.at < negativeTtlMs) throw if (it.blocked) BlockedAddressException(it.message) else UnknownHostException(it.message) else misses.remove(key)
        }
        return null
    }

    private fun accept(host: String, answer: List<InetAddress>): List<InetAddress> {
        if (answer.isEmpty()) throw UnknownHostException("Unable to resolve host \"$host\": empty answer")
        if (answer.any(isBlocked)) throw BlockedAddressException("blocked: $host points at a private address")
        return answer
    }

    private fun resolve(host: String, key: String): List<InetAddress> {
        var last: Exception? = null
        for (i in 1..attempts) {
            try {
                return accept(host, primary.lookup(host)).also { hits[key] = Hit(clock(), it) }
            } catch (e: BlockedAddressException) {
                misses[key] = Miss(clock(), e.message.orEmpty(), blocked = true); throw e
            } catch (e: Exception) {
                last = e
                if (i < attempts) sleep(backoffMs * i)
            }
        }
        if (fallback != null) {
            try {
                return accept(host, fallback.lookup(host)).also { hits[key] = Hit(clock(), it) }
            } catch (e: BlockedAddressException) {
                misses[key] = Miss(clock(), e.message.orEmpty(), blocked = true); throw e
            } catch (e: Exception) { last = last ?: e }
        }
        val msg = "Could not reach $host (DNS lookup failed${last?.message?.let { ": " + it.take(120) } ?: ""})"
        misses[key] = Miss(clock(), msg, blocked = false)
        throw UnknownHostException(msg)
    }
}

/** The resolver the plugin client uses. Same name and contract as before: private addresses are refused. */
object SafeDns : Dns {
    private val delegate: ResilientDns by lazy { ResilientDns(Dns.SYSTEM, fallback = dohFallback()) }
    override fun lookup(hostname: String): List<InetAddress> = delegate.lookup(hostname)
    fun clearFailures() = delegate.clearFailures()

    /** Cloudflare, then Google, each reached by a fixed bootstrap IP so the fallback does not itself need system DNS. */
    private fun dohFallback(): Dns {
        val boot = OkHttpClient.Builder().cache(null).retryOnConnectionFailure(false)
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
        fun doh(url: String, vararg ips: String) = DnsOverHttps.Builder().client(boot).url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).includeIPv6(true).build()
        val providers = listOf(
            doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
        )
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                var err: Exception? = null
                for (p in providers) { try { return p.lookup(hostname) } catch (e: Exception) { err = err ?: e } }
                throw (err as? UnknownHostException) ?: UnknownHostException(err?.message ?: hostname)
            }
        }
    }
}
