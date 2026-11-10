package com.liuying.app.data.download

import com.liuying.app.domain.DownloadItem
import com.liuying.app.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = DownloadStore(tmp.root)

    private fun item(key: String, state: DownloadState = DownloadState.Queued, size: Long? = 1_000, done: Long = 0) =
        DownloadItem(key = key, title = "Title", sizeBytes = size, state = state, downloadedBytes = done, queuedAt = 1)

    @Test fun anEmptyStoreKnowsNothing() {
        assertEquals(emptyList<DownloadItem>(), store().load())
        assertNull(store().bytesOnDisk(item("movie|tt1|tt1")))
    }

    @Test fun theQueueSurvivesAProcessDeath() {
        val s = store()
        val items = listOf(item("movie|tt1|tt1", DownloadState.Done, done = 1_000), item("series|ttS1|v2", done = 400))
        s.save(items)
        // A new instance, as after a restart, reads the same queue back.
        val loaded = DownloadStore(tmp.root).load()
        assertEquals(items.map { it.key }, loaded.map { it.key })
        assertEquals(DownloadState.Done, loaded[0].state)
        assertEquals(400L, loaded[1].downloadedBytes)
    }

    @Test fun anUnreadableIndexIsTreatedAsEmptyInsteadOfCrashing() {
        val s = store()
        File(tmp.root, "index.json").writeText("{ this is not json")
        assertEquals(emptyList<DownloadItem>(), s.load())
    }

    @Test fun filesLiveUnderTheirTitleAndUseTheSafeName() {
        val s = store()
        val odd = DownloadItem("movie|tt/1|x y", "Title", sizeBytes = 10)
        val part = s.partFile(odd, "mkv")
        assertEquals("x_y.mkv.part", part.name)
        assertTrue(part.parentFile.path.contains("movie"))
        assertEquals("x_y.mkv", s.dataFile(odd, "mkv").name)
    }

    @Test fun completingRenamesThePartFileAndReadsItsRealSize() {
        val s = store()
        val i = item("movie|tt1|tt1", DownloadState.Running, size = 1_000, done = 400)
        val part = s.partFile(i, "mp4")
        part.writeBytes(ByteArray(600))

        val done = s.complete(i, "mp4")
        assertEquals(DownloadState.Done, done.state)
        assertEquals(600L, done.downloadedBytes)   // the bytes on disk, not the size the server promised
        assertFalse(part.exists())
        assertTrue(s.dataFile(i, "mp4").exists())
    }

    @Test fun deletingRemovesTheFileAndTheEmptyTitleDirectory() {
        val s = store()
        val i = item("movie|tt1|tt1", DownloadState.Done, done = 10)
        val file = s.dataFile(i, "mkv")
        file.writeBytes(ByteArray(10))
        assertTrue(s.delete(i))
        assertFalse(file.exists())
        assertFalse(file.parentFile.exists())
    }

    @Test fun bytesOnDiskFindsAPartialFile() {
        val s = store()
        val i = item("series|ttS1|v1", DownloadState.Paused, done = 0)
        s.partFile(i, "mkv").writeBytes(ByteArray(123))
        assertEquals(123L, s.bytesOnDisk(i))
    }

    @Test fun theExtensionComesFromTheFilenameThenTheUrl() {
        assertEquals("mkv", downloadExtension("https://x/y.mkv?token=1"))
        assertEquals("mp4", downloadExtension("https://x/y", filename = "Some.File.MP4"))
        assertEquals("mkv", downloadExtension("https://x/stream", filename = null))
        assertEquals("webm", downloadExtension("https://x/y.webm#t=10"))
        assertEquals("mkv", downloadExtension("https://x/y.exe"))   // not a media extension
    }
}
