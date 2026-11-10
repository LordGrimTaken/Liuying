package com.liuying.app.data.download

import com.liuying.app.domain.DownloadItem
import com.liuying.app.domain.DownloadState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadTransferTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun up() { server = MockWebServer().also { it.start() } }
    @After fun down() { runCatching { server.shutdown() } }

    private fun item(done: Long = 0, size: Long? = null) =
        DownloadItem("movie|tt1|tt1", "Film", sizeBytes = size, state = DownloadState.Running, downloadedBytes = done, queuedAt = 1)

    @Test fun aWholeFileIsWrittenAndCompleted() = runBlocking {
        val bytes = ByteArray(100_000) { it.toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val store = DownloadStore(tmp.root)
        val progress = ArrayList<Long>()
        val done = DownloadTransfer(client, store).run(item(), server.url("/film.mkv").toString(), "mkv") { d, _ -> progress += d }

        assertEquals(DownloadState.Done, done.state)
        assertEquals(100_000L, done.downloadedBytes)
        assertTrue(progress.isNotEmpty() && progress.last() == 100_000L)
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })   // monotonic
        assertTrue(store.dataFile(item(), "mkv").exists())
    }

    @Test fun anExistingPartFileResumesWithARangeRequest() = runBlocking {
        val store = DownloadStore(tmp.root)
        val partial = store.partFile(item(), "mkv")
        partial.parentFile.mkdirs()
        partial.writeBytes(ByteArray(40_000))
        server.enqueue(MockResponse().setResponseCode(206).setBody(okio.Buffer().write(ByteArray(60_000))))

        val done = DownloadTransfer(client, store).run(item(done = 40_000), server.url("/film.mkv").toString(), "mkv")

        assertEquals("bytes=40000-", server.takeRequest().getHeader("Range"))
        assertEquals(100_000L, done.downloadedBytes)
    }

    @Test fun aServerThatIgnoresTheRangeRestartsTheFileInsteadOfAppending() = runBlocking {
        val store = DownloadStore(tmp.root)
        val partial = store.partFile(item(), "mkv")
        partial.parentFile.mkdirs()
        partial.writeBytes(ByteArray(40_000))
        // 200 (not 206) means the server sent the whole file again.
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(ByteArray(30_000))))

        val done = DownloadTransfer(client, store).run(item(done = 40_000), server.url("/film.mkv").toString(), "mkv")
        assertEquals(30_000L, done.downloadedBytes)
    }

    @Test fun aServerErrorIsReportedAndKeepsWhatWasDownloaded() = runBlocking {
        val store = DownloadStore(tmp.root)
        val partial = store.partFile(item(), "mkv")
        partial.parentFile.mkdirs()
        partial.writeBytes(ByteArray(12_345))
        server.enqueue(MockResponse().setResponseCode(503))

        val e = runCatching { DownloadTransfer(client, store).run(item(), server.url("/film.mkv").toString(), "mkv") }.exceptionOrNull()
        assertTrue(e is DownloadTransferException && e.message!!.contains("503"))
        assertEquals(12_345L, partial.length())     // the partial file is untouched
    }

    @Test fun aConnectionDropIsReportedAsATransferFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val store = DownloadStore(tmp.root)
        val e = runCatching { DownloadTransfer(client, store).run(item(), server.url("/film.mkv").toString(), "mkv") }.exceptionOrNull()
        assertTrue(e is DownloadTransferException)
    }

    @Test fun aShortBodyIsAnErrorRatherThanAFinishedFile() = runBlocking {
        // The server promises 1 000 bytes by header but sends 10: the file must not look complete.
        server.enqueue(
            MockResponse().setBody(okio.Buffer().write(ByteArray(10))).setHeader("Content-Length", "1000"),
        )
        val store = DownloadStore(tmp.root)
        val e = runCatching { DownloadTransfer(client, store).run(item(), server.url("/film.mkv").toString(), "mkv") }.exceptionOrNull()
        assertTrue("expected a failure, got $e", e is DownloadTransferException)
    }
}
