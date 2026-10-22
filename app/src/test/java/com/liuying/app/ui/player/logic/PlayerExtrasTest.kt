package com.liuying.app.ui.player.logic

import android.view.KeyEvent
import com.liuying.app.ui.player.PlayerSettings
import com.liuying.app.ui.player.SubtitleBackground
import com.liuying.app.ui.player.SubtitlePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class PlayerExtrasTest {
    // ---- L055 -------------------------------------------------------------------------------------------
    @Test fun mediaKeys() {
        assertEquals(MediaKeyAction.TogglePlay, MediaKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(MediaKeyAction.TogglePlay, MediaKeys.actionFor(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(MediaKeyAction.Play, MediaKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(MediaKeyAction.Pause, MediaKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(MediaKeyAction.SeekForward, MediaKeys.actionFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(MediaKeyAction.SeekBack, MediaKeys.actionFor(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(MediaKeyAction.Next, MediaKeys.actionFor(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(MediaKeyAction.Previous, MediaKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(MediaKeyAction.Stop, MediaKeys.actionFor(KeyEvent.KEYCODE_MEDIA_STOP))
        assertEquals(MediaKeyAction.None, MediaKeys.actionFor(KeyEvent.KEYCODE_A))
    }

    // ---- L052 -------------------------------------------------------------------------------------------
    @Test fun sleepTimerMinutes() {
        val t = SleepTimer()
        assertFalse(t.tick(0, 0, 100))
        t.start(SleepChoice.Minutes(15), 1_000)
        assertEquals(15 * 60_000L, t.remainingMs(1_000))
        assertFalse(t.tick(1_000 + 14 * 60_000, 0, 0))
        assertEquals("Sleep in 1:00", SleepTimer.status(t.active, t.remainingMs(1_000 + 14 * 60_000)))
        assertTrue(t.tick(1_000 + 15 * 60_000, 0, 0))
        assertFalse(t.tick(1_000 + 16 * 60_000, 0, 0)) // fires once
        assertNull(t.active)
    }

    @Test fun sleepTimerEndOfEpisodeAndCancel() {
        val t = SleepTimer()
        t.start(SleepChoice.EndOfEpisode, 0)
        assertFalse(t.tick(1, 50_000, 3_600_000))
        assertTrue(t.tick(2, 3_599_500, 3_600_000))
        t.start(SleepChoice.Minutes(30), 0); t.cancel()
        assertFalse(t.tick(99_999_999, 0, 0))
        assertEquals("Sleep at end of episode", SleepTimer.status(SleepChoice.EndOfEpisode, null))
        assertNull(SleepTimer.status(null, null))
        assertEquals(listOf("15 min", "30 min", "60 min", "End of episode"), SleepTimer.Choices.map { SleepTimer.label(it) })
    }

    // ---- L048 -------------------------------------------------------------------------------------------
    @Test fun stallOfferedOncePerStall() {
        val d = StallDetector(15_000)
        assertFalse(d.tick(0, true)); assertFalse(d.tick(14_999, true))
        assertTrue(d.tick(15_000, true))
        assertFalse(d.tick(20_000, true)) // not repeated
        assertFalse(d.tick(21_000, false)) // recovered
        assertFalse(d.tick(22_000, true)); assertTrue(d.tick(37_000, true)) // a new stall
    }

    // ---- L049 -------------------------------------------------------------------------------------------
    @Test fun statsFormatting() {
        val s = PlaybackStats(1920, 1080, 5_200_000, 23.976f, "H.264", "AAC", 3, 12_340, 18_000_000)
        val m = s.lines().toMap()
        assertEquals("1920x1080 @ 23.98 fps", m["Resolution"]); assertEquals("H.264 · 5.2 Mbps", m["Video"])
        assertEquals("3", m["Dropped frames"]); assertEquals("12.3 s", m["Buffer ahead"]); assertEquals("18.0 Mbps", m["Network"])
        assertEquals("unknown", PlaybackStats().lines().toMap()["Resolution"])
        assertEquals("640 kbps", PlaybackStats.formatBitrate(640_000)); assertEquals("24", PlaybackStats.formatFps(24f))
    }

    // ---- L047 -------------------------------------------------------------------------------------------
    @Test fun trackChoiceRoundTripAndMerge() {
        val c = TrackChoice("ja", "en", false)
        assertEquals(c, TrackChoice.decode(c.encode()))
        assertEquals(TrackChoice(subtitleOff = true), TrackChoice.decode(TrackChoice(subtitleOff = true).encode()))
        assertNull(TrackChoice.decode("||0")); assertNull(TrackChoice.decode(null)); assertNull(TrackChoice.decode("junk"))
        val merged = c.withSubtitle(null, true)
        assertEquals("ja", merged.audioLanguage); assertTrue(merged.subtitleOff); assertNull(merged.subtitleLanguage)
        assertEquals("fr", (null as TrackChoice?).withAudio("fr").audioLanguage)
        val tracks = listOf(TrackInfo("0:0", TrackKind.AUDIO, "en", null, 2, null, true), TrackInfo("0:1", TrackKind.AUDIO, "jpn", null, 2, null, false))
        assertEquals("0:1", RememberedAudio.pick(tracks, "ja"))
        assertNull(RememberedAudio.pick(tracks, "de")); assertNull(RememberedAudio.pick(tracks, null))
    }

    // ---- L070 -------------------------------------------------------------------------------------------
    @Test fun presets() {
        val base = PlayerSettings()
        val hc = SubtitlePreset.HIGH_CONTRAST.apply(base)
        assertTrue(hc.subtitleBold); assertEquals(SubtitleBackground.BOX, hc.subtitleBackground); assertEquals(100, hc.subtitleBackgroundOpacityPercent)
        assertEquals(0xFFFFFFFF.toInt(), hc.subtitleColor)
        val y = SubtitlePreset.YELLOW_ON_BLACK.apply(base)
        assertEquals(0xFFFFE600.toInt(), y.subtitleColor); assertEquals(100, y.subtitleBackgroundOpacityPercent)
        assertEquals(SubtitlePreset.HIGH_CONTRAST, SubtitlePreset.current(hc))
        assertEquals(SubtitlePreset.CLASSIC, SubtitlePreset.current(base))
        assertNull(SubtitlePreset.current(base.copy(subtitleSizePercent = 77)))
        assertEquals(base.subtitleBottomOffsetPercent, hc.subtitleBottomOffsetPercent) // position is left alone
    }

    // ---- L069 -------------------------------------------------------------------------------------------
    private val zhText = "1\n00:00:01,000 --> 00:00:03,000\n你好，我们不是一个人。这是他的家，我有一个好的想法。\n\n2\n00:00:04,000 --> 00:00:06,000\n你们都知道这个人，他是我们的老师。\n"
    private val zhHant = "1\n00:00:01,000 --> 00:00:03,000\n你好，我們不是一個人。這是他的家，我有一個好的想法。\n\n2\n00:00:04,000 --> 00:00:06,000\n你們都知道這個人，他是我們的老師。\n"
    private val jaText = "1\n00:00:01,000 --> 00:00:03,000\nこんにちは、私たちは友達です。これはテストです。\n\n2\n00:00:04,000 --> 00:00:06,000\n君の名前は何ですか。よろしくお願いします。\n"
    private val esText = "1\n00:00:01,000 --> 00:00:03,000\n¿Cómo estás? Está muy lejos de aquí, señor.\n\n2\n00:00:04,000 --> 00:00:06,000\nNo sé qué pasó anoche, pero mañana será mejor.\n"
    private val koText = "1\n00:00:01,000 --> 00:00:03,000\n안녕하세요 여러분 오늘도 좋은 하루 되세요\n"

    private fun enc(text: String, cs: String) = text.toByteArray(Charset.forName(cs))

    @Test fun detectsBomEncodings() {
        val u8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo 你好".toByteArray(Charsets.UTF_8)
        assertEquals("héllo 你好", SubtitleCharset.decode(u8).text)
        val u16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + zhText.toByteArray(Charsets.UTF_16LE)
        assertEquals(zhText, SubtitleCharset.decode(u16le).text)
        val u16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + zhText.toByteArray(Charsets.UTF_16BE)
        assertEquals(zhText, SubtitleCharset.decode(u16be).text)
    }

    @Test fun detectsUtf16WithoutBomAndPlainUtf8() {
        assertEquals(esText, SubtitleCharset.decode(esText.toByteArray(Charsets.UTF_16LE)).text)
        assertEquals(esText, SubtitleCharset.decode(esText.toByteArray(Charsets.UTF_16BE)).text)
        assertEquals(zhText, SubtitleCharset.decode(zhText.toByteArray(Charsets.UTF_8)).text)
        assertEquals("plain ascii", SubtitleCharset.decode("plain ascii".toByteArray()).text)
        assertEquals("", SubtitleCharset.decode(ByteArray(0)).text)
    }

    @Test fun detectsGbkBig5ShiftJisEucKrAndWindows1252() {
        assertEquals(zhText, SubtitleCharset.decode(enc(zhText, "GBK")).text)
        assertEquals(zhText, SubtitleCharset.decode(enc(zhText, "GBK"), "zh").text)
        assertEquals(zhHant, SubtitleCharset.decode(enc(zhHant, "Big5")).text)
        assertEquals(zhHant, SubtitleCharset.decode(enc(zhHant, "Big5"), "zh").text)
        assertEquals(jaText, SubtitleCharset.decode(enc(jaText, "Shift_JIS")).text)
        assertEquals(jaText, SubtitleCharset.decode(enc(jaText, "Shift_JIS"), "ja").text)
        assertEquals(koText, SubtitleCharset.decode(enc(koText, "EUC-KR"), "ko").text)
        assertEquals(esText, SubtitleCharset.decode(enc(esText, "windows-1252")).text)
        assertEquals(esText, SubtitleCharset.decode(enc(esText, "windows-1252"), "es").text)
    }

    @Test fun toUtf8DropsTheBomAndReencodes() {
        val out = SubtitleCharset.toUtf8(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "你好".toByteArray(Charsets.UTF_16LE))
        assertEquals("你好", String(out, Charsets.UTF_8))
    }
}
