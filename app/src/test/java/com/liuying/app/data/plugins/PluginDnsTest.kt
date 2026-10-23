package com.liuying.app.data.plugins

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PluginDnsTest {
    private val pub = InetAddress.getByAddress(byteArrayOf(140.toByte(), 82, 121, 3))
    private val priv = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5))
    private var now = 0L
    private val slept = ArrayList<Long>()

    private fun dns(primary: Dns, fallback: Dns? = null) = ResilientDns(primary, fallback, clock = { now }, sleep = { slept += it })
    private class Fake(val answers: (Int) -> List<InetAddress>) : Dns {
        val calls = AtomicInteger()
        override fun lookup(hostname: String): List<InetAddress> = answers(calls.incrementAndGet())
    }
    private fun fail(): Nothing = throw UnknownHostException("Unable to resolve host \"h\": No address associated with hostname")

    @Test fun retriesTransientFailuresWithBackoff() {
        val p = Fake { n -> if (n < 3) fail() else listOf(pub) }
        assertEquals(listOf(pub), dns(p).lookup("raw.example.com"))
        assertEquals(3, p.calls.get()); assertEquals(listOf(150L, 300L), slept)
    }

    @Test fun cachesPositiveAnswersForFiveMinutesAndThenAsksAgain() {
        val p = Fake { listOf(pub) }; val d = dns(p)
        d.lookup("A.example.com"); d.lookup("a.example.com."); d.lookup("a.example.com")
        assertEquals(1, p.calls.get())
        now += 5 * 60_000L + 1
        d.lookup("a.example.com")
        assertEquals(2, p.calls.get())
    }

    @Test fun concurrentLookupsOfOneHostShareOneResolution() {
        val gate = CountDownLatch(1)
        val p = object : Dns { val calls = AtomicInteger(); override fun lookup(hostname: String): List<InetAddress> { calls.incrementAndGet(); gate.await(); return listOf(pub) } }
        val d = dns(p); val pool = Executors.newFixedThreadPool(8)
        val fs = List(8) { pool.submit<List<InetAddress>> { d.lookup("same.example.com") } }
        Thread.sleep(100); gate.countDown()
        fs.forEach { assertEquals(listOf(pub), it.get()) }
        assertEquals(1, p.calls.get()); pool.shutdownNow()
    }

    @Test fun fallsBackToTheSecondResolverAfterThreeFailures() {
        val p = Fake { fail() }; val f = Fake { listOf(pub) }
        assertEquals(listOf(pub), dns(p, f).lookup("x.example.com"))
        assertEquals(3, p.calls.get()); assertEquals(1, f.calls.get())
    }

    @Test fun aDeadHostIsNotRetriedForThirtySecondsAndMessageNamesTheHost() {
        val p = Fake { fail() }; val f = Fake { fail() }; val d = dns(p, f)
        val e = try { d.lookup("dead.example.com"); null } catch (e: UnknownHostException) { e }!!
        assertTrue(e.message!!, e.message!!.startsWith("Could not reach dead.example.com"))
        val before = p.calls.get() + f.calls.get()
        now += 29_000; try { d.lookup("dead.example.com"); fail("should fail") } catch (_: UnknownHostException) {}
        assertEquals(before, p.calls.get() + f.calls.get())
        now += 2_000; try { d.lookup("dead.example.com"); fail("should fail") } catch (_: UnknownHostException) {}
        assertTrue(p.calls.get() + f.calls.get() > before)
    }

    @Test fun clearFailuresLetsARetryThrough() {
        val p = Fake { n -> if (n <= 3) fail() else listOf(pub) }; val d = dns(p)
        try { d.lookup("flaky.example.com"); fail("should fail") } catch (_: UnknownHostException) {}
        d.clearFailures()
        assertEquals(listOf(pub), d.lookup("flaky.example.com"))
    }

    @Test fun privateAnswersAreBlockedFromEverySourceAndNeverRetriedOrCached() {
        val p = Fake { listOf(pub, priv) }; val f = Fake { listOf(pub) }; val d = dns(p, f)
        try { d.lookup("evil.example.com"); fail("must block") } catch (e: BlockedAddressException) { assertTrue(e.message!!.startsWith("blocked")) }
        assertEquals(1, p.calls.get()); assertEquals(0, f.calls.get()); assertTrue(slept.isEmpty())
        try { d.lookup("evil.example.com"); fail("must stay blocked") } catch (_: BlockedAddressException) {}
        assertEquals(1, p.calls.get())
    }

    @Test fun theFallbackAnswerIsBlockedToo() {
        val p = Fake { fail() }; val f = Fake { listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
        try { dns(p, f).lookup("rebind.example.com"); fail("must block") } catch (_: BlockedAddressException) {}
    }

    @Test fun emptyAnswersCountAsFailures() {
        val p = Fake { emptyList() }
        try { dns(p).lookup("empty.example.com"); fail("must fail") } catch (e: UnknownHostException) { assertFalse(e is BlockedAddressException) }
        assertEquals(3, p.calls.get())
    }
}
