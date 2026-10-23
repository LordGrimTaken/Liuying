package com.liuying.app.domain.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginContractTest {
    // ---- targets
    @Test fun movieTarget() {
        val t = PluginTargets.parse("movie", "tt0133093")!!
        assertEquals(PluginTarget("tt0133093", "movie", null, null), t); assertTrue(t.isImdb)
    }

    @Test fun seriesTargetNeedsSeasonAndEpisode() {
        assertEquals(PluginTarget("tt0944947", "tv", 2, 5), PluginTargets.parse("series", "tt0944947:2:5"))
        assertNull(PluginTargets.parse("series", "tt0944947"))
        assertNull(PluginTargets.parse("series", "tt0944947:x:1"))
    }

    @Test fun tmdbPrefixedIdsPassThrough() {
        assertEquals(PluginTarget("603", "movie", null, null), PluginTargets.parse("movie", "tmdb:603"))
        assertEquals(PluginTarget("1396", "tv", 1, 2), PluginTargets.parse("series", "tmdb:1396:1:2"))
    }

    @Test fun unknownIdSchemesAndTypesAreSkipped() {
        assertNull(PluginTargets.parse("movie", "kitsu:1"))
        assertNull(PluginTargets.parse("movie", ""))
        assertNull(PluginTargets.parse("tv", "tt0133093"))
        assertNull(PluginTargets.parse("movie", "tt1"))
    }

    @Test fun declaredTypesMapToMediaTypes() {
        assertTrue(PluginTargets.supports(emptyList(), "tv"))
        assertTrue(PluginTargets.supports(listOf("movie", "tv"), "tv"))
        assertTrue(PluginTargets.supports(listOf("series"), "tv"))
        assertFalse(PluginTargets.supports(listOf("tv"), "movie"))
        assertFalse(PluginTargets.supports(listOf("movie"), "tv"))
    }

    // ---- the call the host makes
    @Test fun invocationQuotesArgumentsAsJsonLiterals() {
        assertEquals("""__liuyingInvoke("603", "movie", undefined, undefined);""", PluginContract.invocation(PluginRunRequest("603", "movie")))
        assertEquals("""__liuyingInvoke("tt1", "tv", 2, 5);""", PluginContract.invocation(PluginRunRequest("tt1", "tv", 2, 5)))
        val hostile = PluginContract.invocation(PluginRunRequest("\");alert(1);//", "movie"))
        assertTrue(hostile, hostile.startsWith("""__liuyingInvoke("\");alert(1);//", "movie""""))
    }

    // ---- result parsing
    @Test fun parsesAnArrayOfStreams() {
        val r = PluginContract.parseResult(
            """[{"name":"Server 1","title":"Movie (2020)","url":"https://cdn.example.com/a.m3u8","quality":"1080p","headers":{"Referer":"https://x.example.com/","X-N":"1"}},
                {"url":"https://cdn.example.com/b.mp4"}]""", "MyPlugin",
        ).getOrThrow()
        assertEquals(2, r.size)
        assertEquals("Server 1", r[0].name); assertEquals("1080p", r[0].quality); assertEquals(mapOf("Referer" to "https://x.example.com/", "X-N" to "1"), r[0].headers)
        assertEquals("MyPlugin", r[1].name) // no name: falls back to the plugin's own
        assertNull(r[1].quality)
    }

    @Test fun acceptsStreamsWrapperAndNull() {
        assertEquals(1, PluginContract.parseResult("""{"streams":[{"url":"https://a.example.com/x"}]}""", "P").getOrThrow().size)
        assertEquals(0, PluginContract.parseResult("null", "P").getOrThrow().size)
        assertEquals(0, PluginContract.parseResult("[]", "P").getOrThrow().size)
    }

    @Test fun dropsUnsafeOrMalformedEntries() {
        val r = PluginContract.parseResult(
            """[{"url":"javascript:alert(1)"},{"url":"file:///etc/passwd"},{"url":"http://127.0.0.1/x"},{"url":"magnet:?xt=urn:btih:abc"},{"name":"no url"},5,"x",null,{"url":"https://ok.example.com/v"}]""", "P",
        ).getOrThrow()
        assertEquals(listOf("https://ok.example.com/v"), r.map { it.url })
    }

    @Test fun dropsBadHeadersAndCapsLengths() {
        val r = PluginContract.parseResult(
            """[{"url":"https://a.example.com/x","name":"${"n".repeat(500)}","headers":{"Good":"v","Bad:Name":"v","Line":"a\nb","Num":5}}]""", "P",
        ).getOrThrow()
        assertEquals(200, r[0].name.length)
        assertEquals(mapOf("Good" to "v"), r[0].headers)
    }

    @Test fun capsTheNumberOfStreams() {
        val many = (1..300).joinToString(",", "[", "]") { """{"url":"https://a.example.com/$it"}""" }
        assertEquals(PluginLimits.MAX_STREAMS, PluginContract.parseResult(many, "P").getOrThrow().size)
    }

    @Test fun rejectsWrongShapes() {
        assertTrue(PluginContract.parseResult("not json", "P").isFailure)
        assertTrue(PluginContract.parseResult("42", "P").isFailure)
        assertTrue(PluginContract.parseResult("\"hello\"", "P").isFailure)
        assertTrue(PluginContract.parseResult("""{"nope":1}""", "P").isFailure)
    }

    // ---- guard: timeout and failure isolation
    @Test fun guardTurnsAHangIntoATimeoutFailure() = runTest {
        val r = async { PluginRunGuard.run(20_000) { awaitCancellation() } }
        runCurrent(); advanceTimeBy(19_999); runCurrent()
        assertFalse(r.isCompleted)
        advanceTimeBy(2); runCurrent()
        val e = r.await().exceptionOrNull() as PluginRunException
        assertEquals(PluginRunException.Kind.Timeout, e.kind)
        assertTrue(e.message!!.contains("20 seconds"))
    }

    @Test fun guardReturnsValuesAndWrapsThrowables() = runTest {
        assertEquals(7, PluginRunGuard.run { delay(100); 7 }.getOrThrow())
        assertEquals("boom", PluginRunGuard.run<Int> { throw IllegalStateException("boom") }.exceptionOrNull()!!.message)
        assertEquals("x", (PluginRunGuard.run<Int> { throw PluginRunException("x") }.exceptionOrNull() as PluginRunException).message)
        assertTrue(PluginRunGuard.run<Int> { throw OutOfMemoryError("heap") }.isFailure)
    }

    @Test fun defaultsMatchTheDocumentedLimits() {
        assertEquals(20_000L, PluginLimits.RUN_TIMEOUT_MS); assertEquals(30, PluginLimits.MAX_REQUESTS)
        assertEquals(5L * 1024 * 1024, PluginLimits.MAX_RESPONSE_BYTES); assertEquals(15_000L, PluginLimits.REQUEST_TIMEOUT_MS)
    }
}
