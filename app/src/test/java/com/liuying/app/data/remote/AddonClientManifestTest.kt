package com.liuying.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AddonClientManifestTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AddonClient

    private val fixture: String =
        javaClass.getResourceAsStream("/scplus-manifest.json")!!.readBytes().toString(Charsets.UTF_8)

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = AddonClient(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/manifest.json").toString()

    @Test fun parsesStreamingCatalogsPlusManifest() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(fixture))
        val r = client.fetchManifest(url())
        assertTrue("failure: ${r.exceptionOrNull()}", r.isSuccess)
        val m = r.getOrThrow().manifest
        assertEquals("Streaming Catalogs Plus", m.name)
        assertEquals(46, m.catalogs.size)
        assertTrue(m.behaviorHints.configurationRequired)
        assertTrue(m.resources.isNotEmpty())
        assertTrue(m.catalogs.any { c -> c.extra.any { it.isRequired } })
    }

    @Test fun parsesFixtureWithCorsAndGzipLikeHeaders() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
                .setHeader("Access-Control-Allow-Origin", "*").setHeader("Cache-Control", "max-age=0, no-cache").setBody(fixture),
        )
        assertTrue(client.fetchManifest(url()).isSuccess)
    }

    @Test fun nonAddonJsonGetsDistinctNotAddonError() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"hello":"world"}"""))
        val f = client.fetchManifest(url()).exceptionOrNull() as AddonFailure
        assertEquals(AddonErrorKind.MissingIdentity, f.kind)
    }

    @Test fun serializationErrorsMapToNotAddonMessage() {
        val r = client.parseManifest("http://x/manifest.json", """{"id":["not","a","string"],"name":{"a":1}}""")
        val f = r.exceptionOrNull() as AddonFailure
        assertTrue(f.kind == AddonErrorKind.NotJson || f.kind == AddonErrorKind.MissingIdentity)
        assertFalse(f.userMessage.isBlank())
    }

    @Test fun unexpectedExceptionNeverShowsBareUnexpectedError() {
        val f = AddonFailure.fromUnexpected(IllegalStateException())
        assertEquals(AddonErrorKind.Unknown, f.kind)
        assertTrue(f.userMessage, f.userMessage.contains("IllegalStateException"))
        val n = AddonFailure.fromUnexpected(NullPointerException("boom"))
        assertTrue(n.userMessage, n.userMessage.contains("NullPointerException") && n.userMessage.contains("boom"))
        val s = AddonFailure.fromUnexpected(kotlinx.serialization.SerializationException("bad"))
        assertEquals(AddonErrorKind.NotJson, s.kind)
        assertTrue(s.userMessage.contains("add-on manifest") || s.userMessage.contains("add-on"))
    }
}
