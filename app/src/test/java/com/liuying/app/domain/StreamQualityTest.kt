package com.liuying.app.domain

import com.liuying.app.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {
    @Test fun aTypicalWebRipLineParsesEveryField() {
        val q = StreamQualityParser.parse("1080p WEB-DL DDP5.1 H.264-GROUP")
        assertEquals(Resolution.FHD, q.resolution)
        assertEquals("WEB-DL", q.source)
        assertEquals(VideoCodec.H264, q.codec)
        assertEquals(listOf("DD+"), q.audio)
        assertEquals("5.1", q.channels)
    }

    @Test fun fourKWithDolbyVisionAndAtmos() {
        val q = StreamQualityParser.parse("2160p HDR10+ DV HEVC Atmos 7.1")
        assertEquals(Resolution.UHD_4K, q.resolution)
        assertTrue(q.hdr)
        assertTrue(q.hdr10Plus)
        assertTrue(q.dolbyVision)
        assertEquals(VideoCodec.HEVC, q.codec)
        assertEquals(listOf("Atmos"), q.audio)
        assertEquals("7.1", q.channels)
    }

    @Test fun hdtvAndSevenTwenty() {
        val q = StreamQualityParser.parse("720p HDTV x264 AAC")
        assertEquals(Resolution.HD, q.resolution)
        assertEquals("HDTV", q.source)
        assertEquals(VideoCodec.H264, q.codec)
        assertEquals(listOf("AAC"), q.audio)
        assertNull(q.channels)
    }

    @Test fun lowResolutionsAreRecognisedIncludingABareSdTag() {
        assertEquals(Resolution.SD, StreamQualityParser.parse("480p WEBRip").resolution)
        assertEquals(Resolution.SD, StreamQualityParser.parse("SD CAM").resolution)
        assertEquals("CAM", StreamQualityParser.parse("SD CAM").source)
    }

    @Test fun sizesAreReadFromTheTextOrTheHint() {
        assertEquals((2.5 * 1_073_741_824L).toLong(), StreamQualityParser.parse("1080p 2.5 GB").sizeBytes)
        assertEquals(700L * 1_048_576L, StreamQualityParser.parse("700 MB").sizeBytes)
        assertEquals(4_000_000L, StreamQualityParser.parse("anything", videoSize = 4_000_000L).sizeBytes)
    }

    @Test fun seedsComeFromTheEmojiOrAWord() {
        assertEquals(42, StreamQualityParser.parse("👤 42").seeds)
        assertEquals(7, StreamQualityParser.parse("seeds: 7").seeds)
    }

    @Test fun theReleaseGroupComesFromTheFilename() {
        assertEquals("GROUP", StreamQualityParser.parse("some line", filename = "Movie.2020.1080p-GROUP.mkv").releaseGroup)
        assertEquals("NTb", StreamQualityParser.parse("some line", filename = "Show.S01E01.1080p-NTb.mkv").releaseGroup)
        assertNull(StreamQualityParser.parse("no filename here").releaseGroup)
    }

    @Test fun languageFlagsAreDecodedFromEmoji() {
        assertEquals(listOf("US", "GB"), StreamQualityParser.flags("🇺🇸🇬🇧 Multi"))
        assertEquals(emptyList<String>(), StreamQualityParser.flags("no flags"))
    }

    @Test fun badgesAreShortAndOrdered() {
        val b = StreamQualityParser.parse("1080p HDR HEVC DTS-HD MA 5.1 WEB-DL 2.0 GB").badges
        assertEquals("1080p", b[0])
        assertEquals("HDR", b[1])
        assertEquals("HEVC", b[2])
        assertTrue(b.contains("DTS-HD MA"))
        assertTrue(b.contains("WEB-DL"))
        assertTrue(b.last().endsWith("GB"))
    }

    @Test fun streamKindFollowsTheUrlItHas() {
        assertEquals(StreamKind.Direct, StreamQualityParser.kindOf(Stream(url = "https://example.com/a.mkv")))
        assertEquals(StreamKind.Torrent, StreamQualityParser.kindOf(Stream(infoHash = "abc")))
        assertEquals(StreamKind.Torrent, StreamQualityParser.kindOf(Stream(url = "magnet:?xt=urn:btih:abc")))
        assertEquals(StreamKind.YouTube, StreamQualityParser.kindOf(Stream(ytId = "dQw4w9WgXcQ")))
        assertEquals(StreamKind.External, StreamQualityParser.kindOf(Stream(externalUrl = "https://example.com")))
        assertEquals(StreamKind.Unsupported, StreamQualityParser.kindOf(Stream()))
    }
}
