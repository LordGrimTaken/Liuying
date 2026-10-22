package com.liuying.app.domain

import com.liuying.app.data.repo.StreamRepository
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.data.settings.PreferredResolution
import com.liuying.app.domain.model.Manifest
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamPickerTest {
    private fun src(id: Long, order: Int) = Source(id, "https://s$id.example/manifest.json", Manifest(id = "s$id", name = "S$id"), true, order, 0, 0)
    private fun item(source: Source, i: Int, name: String, url: String? = "https://x/v$i.mp4") =
        StreamRepository.toItem(source, Stream(url = url, name = name), i)

    private val s1 = src(1, 0)
    private val s2 = src(2, 1)

    @Test fun prefersHigherResolutionByDefault() {
        val a = item(s1, 0, "Movie 720p"); val b = item(s1, 1, "Movie 1080p")
        assertEquals(b, StreamPicker.pick(listOf(a, b), AppSettings()))
    }

    @Test fun honoursPreferredResolution() {
        val a = item(s1, 0, "Movie 720p"); val b = item(s1, 1, "Movie 4K")
        assertEquals(a, StreamPicker.pick(listOf(a, b), AppSettings(preferredResolution = PreferredResolution.HD720)))
    }

    @Test fun excludedWordsAndPreferredSource() {
        val a = item(s1, 0, "Movie 1080p CAM"); val b = item(s2, 0, "Movie 1080p")
        assertEquals(b, StreamPicker.pick(listOf(a, b), AppSettings(excludedWords = listOf("cam"))))
        assertEquals(b, StreamPicker.pick(listOf(a.copy(), b), AppSettings(preferredSourceId = 2)))
    }

    @Test fun skipsDisabledAndHandlesEmpty() {
        val torrent = StreamRepository.toItem(s1, Stream(infoHash = "abc", name = "T 1080p"), 0)
        assertNull(StreamPicker.pick(listOf(torrent), AppSettings()))
        assertNull(StreamPicker.pick(emptyList(), AppSettings()))
    }
}
