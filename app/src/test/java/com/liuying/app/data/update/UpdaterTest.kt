package com.liuying.app.data.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdaterTest {
    private lateinit var server: MockWebServer
    private lateinit var updater: Updater
    private val apk = ByteArray(200_000) { (it * 31).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val tmp = File(System.getProperty("java.io.tmpdir"), "liuying-updater-test").also { it.mkdirs() }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        updater = Updater(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }
    @After fun tearDown() { server.shutdown(); tmp.deleteRecursively() }

    private fun manifestJson(sha: String = apkSha, url: String = server.url("/downloads/Liuying-0.3.0.apk").toString(), size: Long = apk.size.toLong()) =
        """{"versionName":"0.3.0","versionCode":3,"apkUrl":"$url","sha256":"$sha","sizeBytes":$size,"releasedAt":"2026-10-22T00:00:00Z","notes":"Fixes"}"""

    // ---- pure logic
    @Test fun parsesManifestAndIgnoresUnknownKeys() {
        val m = UpdateManifest.parse(manifestJson().dropLast(1) + ""","extra":1}""")!!
        assertEquals(3L, m.versionCode); assertEquals("0.3.0", m.versionName); assertEquals("Fixes", m.notes); assertEquals(apkSha, m.sha256)
    }
    @Test fun rejectsMalformedManifests() {
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse("[]"))
        assertNull(UpdateManifest.parse(manifestJson(sha = "abc")))
        assertNull(UpdateManifest.parse("""{"versionName":"1","apkUrl":"x","sha256":"$apkSha"}"""))
        assertNull(UpdateManifest.parse(manifestJson().replace("\"versionCode\":3", "\"versionCode\":0")))
    }
    @Test fun versionComparisonIsStrict() {
        val m = UpdateManifest.parse(manifestJson())!!
        assertTrue(UpdatePolicy.isNewer(2, m)); assertFalse(UpdatePolicy.isNewer(3, m)); assertFalse(UpdatePolicy.isNewer(4, m))
    }
    @Test fun autoCheckThrottle() {
        val h = 60 * 60 * 1000L
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 100 * h, 0))
        assertFalse(UpdatePolicy.shouldAutoCheck(true, 100 * h, 90 * h))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 100 * h, 87 * h))
        assertFalse(UpdatePolicy.shouldAutoCheck(false, 100 * h, 0))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 10 * h, 50 * h)) // clock went backwards
    }

    // ---- network
    @Test fun fetchesManifest() = runBlocking {
        server.enqueue(MockResponse().setBody(manifestJson()))
        val m = updater.fetchManifest()
        assertEquals(3L, m.versionCode)
        assertEquals("/downloads/latest.json", server.takeRequest().path)
    }
    @Test fun fetchManifestFailsOnHttpErrorAndGarbage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        try { updater.fetchManifest(); fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("500")) }
        server.enqueue(MockResponse().setBody("<html>"))
        try { updater.fetchManifest(); fail() } catch (e: UpdateException) { assertNotNull(e.message) }
    }
    @Test fun downloadsAndVerifiesHashWithProgress() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val m = UpdateManifest.parse(manifestJson())!!
        var last = 0f
        val f = updater.download(m, File(tmp, "a.apk")) { last = it }
        assertEquals(1f, last, 0f); assertTrue(f.readBytes().contentEquals(apk)); assertFalse(File(tmp, "a.apk.part").exists())
    }
    @Test fun refusesAndDeletesOnHashMismatch() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val m = UpdateManifest.parse(manifestJson(sha = "0".repeat(64)))!!
        try { updater.download(m, File(tmp, "b.apk")); fail() } catch (e: UpdateException) { assertTrue(e.message!!.contains("checksum")) }
        assertFalse(File(tmp, "b.apk").exists()); assertFalse(File(tmp, "b.apk.part").exists())
    }
    @Test fun refusesTruncatedDownload() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apk.copyOf(1000))))
        val m = UpdateManifest.parse(manifestJson())!!
        try { updater.download(m, File(tmp, "c.apk")); fail() } catch (e: UpdateException) { /* ok */ }
        assertFalse(File(tmp, "c.apk").exists())
    }
    @Test fun refusesApkOnAnotherHost() {
        val m = UpdateManifest.parse(manifestJson(url = "http://evil.example/x.apk"))!!
        try { updater.apkRequest(m); fail() } catch (e: UpdateException) { /* ok */ }
    }
}
