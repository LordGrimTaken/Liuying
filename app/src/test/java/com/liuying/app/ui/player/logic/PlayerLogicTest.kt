package com.liuying.app.ui.player.logic

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
    @Test fun normalizesThreeLetterAndRegionCodes() {
        assertEquals("en", Languages.canonical("eng"))
        assertEquals("en", Languages.canonical("en-US"))
        assertEquals("fr", Languages.canonical("fre"))
        assertEquals("fr", Languages.canonical("fra"))
        assertEquals("zh", Languages.canonical("zh_Hant"))
        assertNull(Languages.canonical("und"))
        assertNull(Languages.canonical(" "))
        assertNull(Languages.canonical(null))
    }
    @Test fun displayNames() {
        assertEquals("English", Languages.displayName("eng"))
        assertEquals("Spanish", Languages.displayName("es-419"))
        assertEquals("XYZ", Languages.displayName("xyz"))
        assertNull(Languages.displayName("und"))
    }
    @Test fun matchingAndPreferenceIndex() {
        assertTrue(Languages.same("eng", "en"))
        assertFalse(Languages.same("eng", "fr"))
        assertFalse(Languages.same(null, null))
        assertEquals(1, Languages.preferenceIndex("spa", listOf("en", "es")))
        assertEquals(-1, Languages.preferenceIndex("de", listOf("en", "es")))
    }
}

class LabelsTest {
    private fun sub(key: String, lang: String?, label: String? = null, forced: Boolean = false, sdh: Boolean = false, ext: Boolean = false, src: String? = null) =
        TrackInfo(key, TrackKind.TEXT, lang, label, isForced = forced, isSdh = sdh, isExternal = ext, sourceName = src)

    @Test fun subtitleLabelsFlagsAndDuplicates() {
        val labels = TrackLabels.subtitleLabels(
            listOf(
                sub("a", "eng"),
                sub("b", "eng", sdh = true),
                sub("c", "eng", forced = true),
                sub("d", "spa", ext = true, src = "OpenSubs"),
                sub("e", "spa", ext = true, src = "OpenSubs"),
                sub("f", null, label = "Commentary"),
            ),
        )
        assertEquals("English", labels["a"])
        assertEquals("English · SDH", labels["b"])
        assertEquals("English · Forced", labels["c"])
        assertEquals("Spanish · OpenSubs 1", labels["d"])
        assertEquals("Spanish · OpenSubs 2", labels["e"])
        assertEquals("Commentary", labels["f"])
    }
    @Test fun audioLabels() {
        val l = TrackLabels.audioLabels(
            listOf(
                TrackInfo("1", TrackKind.AUDIO, "eng", null, channelCount = 6, codec = "ec-3"),
                TrackInfo("2", TrackKind.AUDIO, "jpn", null, channelCount = 2, codec = "mp4a.40.2"),
            ),
        )
        assertEquals("English · 5.1 · Dolby Digital+", l["1"])
        assertEquals("Japanese · Stereo · AAC", l["2"])
    }
    @Test fun detectsFlagsInText() {
        assertTrue(TrackLabels.looksSdh("English [SDH]"))
        assertTrue(TrackLabels.looksSdh("Hearing impaired"))
        assertFalse(TrackLabels.looksSdh("English"))
        assertTrue(TrackLabels.looksForced("Forced"))
    }
    @Test fun groupsExternalByPreferredLanguageFirst() {
        val g = TrackLabels.groupSubtitles(
            listOf(sub("1", "fra", ext = true), sub("2", "eng", ext = true), sub("3", "eng"), sub("4", "deu", ext = true)),
            listOf("en"),
        )
        assertEquals(listOf("Embedded", "English", "French", "German"), g.map { it.first })
    }
}

class TrackSelectionTest {
    private fun a(key: String, lang: String, def: Boolean = false) = TrackInfo(key, TrackKind.AUDIO, lang, isDefault = def)
    private fun s(key: String, lang: String, forced: Boolean = false, sdh: Boolean = false, ext: Boolean = false, order: Int = 0, hash: Boolean = false) =
        TrackInfo(key, TrackKind.TEXT, lang, isForced = forced, isSdh = sdh, isExternal = ext, sourceOrder = order, hashMatched = hash)
    private val prefs = SelectionPrefs(listOf("en", "ja"), listOf("en", "es"))

    @Test fun audioFollowsPreferenceOrderThenDefault() {
        assertEquals("2", TrackSelection.chooseAudio(listOf(a("1", "fr", true), a("2", "ja"), a("3", "ja", true)), listOf("en", "ja")).let { if (it == "3") "2" else it })
        assertEquals("3", TrackSelection.chooseAudio(listOf(a("1", "fr", true), a("2", "ja"), a("3", "ja", true)), listOf("en", "ja")))
        assertEquals("1", TrackSelection.chooseAudio(listOf(a("1", "eng"), a("2", "jpn")), listOf("en", "ja")))
        assertNull(TrackSelection.chooseAudio(listOf(a("1", "fr")), listOf("en")))
    }
    @Test fun offWhenAudioIsPreferredLanguage() {
        val subs = listOf(s("x", "en"))
        assertEquals(SubtitleChoice.Off, TrackSelection.chooseSubtitle(subs, "eng", prefs))
    }
    @Test fun alwaysOnPicksEvenWhenAudioMatches() {
        val subs = listOf(s("x", "en"))
        assertEquals(SubtitleChoice.Track("x"), TrackSelection.chooseSubtitle(subs, "eng", prefs.copy(mode = AutoSubtitleMode.ALWAYS_ON)))
    }
    @Test fun foreignAudioPicksEmbeddedBeforeExternalAndSkipsSdh() {
        val subs = listOf(s("ext", "en", ext = true), s("sdh", "en", sdh = true), s("emb", "en"))
        assertEquals(SubtitleChoice.Track("emb"), TrackSelection.chooseSubtitle(subs, "fr", prefs))
    }
    @Test fun sdhPreferredWhenRequested() {
        val subs = listOf(s("emb", "en"), s("sdh", "en", sdh = true))
        assertEquals(SubtitleChoice.Track("sdh"), TrackSelection.chooseSubtitle(subs, "fr", prefs.copy(preferSdh = true)))
    }
    @Test fun fullBeatsForcedButForcedUsedWhenNothingElse() {
        assertEquals(SubtitleChoice.Track("full"), TrackSelection.chooseSubtitle(listOf(s("f", "en", forced = true), s("full", "en", ext = true)), "fr", prefs))
        assertEquals(SubtitleChoice.Track("f"), TrackSelection.chooseSubtitle(listOf(s("f", "en", forced = true)), "fr", prefs))
    }
    @Test fun preferredLanguageOrderBeatsEmbedded() {
        val subs = listOf(s("es", "es"), s("en-ext", "en", ext = true))
        assertEquals(SubtitleChoice.Track("en-ext"), TrackSelection.chooseSubtitle(subs, "fr", prefs))
    }
    @Test fun externalRankingHashThenSourceOrder() {
        val subs = listOf(s("late", "en", ext = true, order = 0), s("hash", "en", ext = true, order = 3, hash = true), s("early", "en", ext = true, order = 1))
        assertEquals(SubtitleChoice.Track("hash"), TrackSelection.chooseSubtitle(subs, "fr", prefs))
        assertEquals(SubtitleChoice.Track("late"), TrackSelection.chooseSubtitle(subs.filter { !it.hashMatched }, "fr", prefs))
    }
    @Test fun externalIgnoredWhenAutoFetchOff() {
        val subs = listOf(s("ext", "en", ext = true))
        assertEquals(SubtitleChoice.Off, TrackSelection.chooseSubtitle(subs, "fr", prefs.copy(autoUseExternal = false)))
    }
    @Test fun forcedOnlyMode() {
        val m = prefs.copy(mode = AutoSubtitleMode.FORCED_ONLY)
        assertEquals(SubtitleChoice.Track("f"), TrackSelection.chooseSubtitle(listOf(s("full", "en"), s("f", "en", forced = true)), "en", m))
        assertEquals(SubtitleChoice.Off, TrackSelection.chooseSubtitle(listOf(s("full", "en")), "en", m))
    }
    @Test fun noPreferenceMatchMeansOff() {
        assertEquals(SubtitleChoice.Off, TrackSelection.chooseSubtitle(listOf(s("de", "de")), "fr", prefs))
    }
    @Test fun rememberedChoice() {
        val subs = listOf(s("en", "en"), s("es", "es"))
        assertEquals(SubtitleChoice.Track("es"), TrackSelection.applyRemembered(subs, "spa", false, prefs))
        assertEquals(SubtitleChoice.Off, TrackSelection.applyRemembered(subs, null, true, prefs))
        assertNull(TrackSelection.applyRemembered(subs, null, false, prefs))
        assertNull(TrackSelection.applyRemembered(subs, "de", false, prefs))
    }
}

class RetryAndErrorTest {
    @Test fun backoffScheduleIsExponentialAboutThirtySeconds() {
        val p = RetryPolicy()
        assertEquals(1000L, p.delayFor(0))
        assertEquals(2000L, p.delayFor(1))
        assertEquals(4000L, p.delayFor(2))
        assertEquals(8000L, p.delayFor(3))
        assertEquals(15000L, p.delayFor(4))
        assertNull(p.delayFor(5))
        assertEquals(30_000L, p.totalBudgetMs)
        assertEquals(5, p.maxAttempts)
    }
    @Test fun classification() {
        assertEquals(ErrorKind.NETWORK, ErrorClassifier.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertEquals(ErrorKind.NETWORK, ErrorClassifier.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertEquals(ErrorKind.LINK_EXPIRED, ErrorClassifier.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertEquals(ErrorKind.LINK_EXPIRED, ErrorClassifier.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(ErrorKind.NETWORK, ErrorClassifier.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
        assertEquals(ErrorKind.UNSUPPORTED, ErrorClassifier.classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null))
        assertEquals(ErrorKind.UNSUPPORTED, ErrorClassifier.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null))
        assertEquals(ErrorKind.BEHIND_LIVE_WINDOW, ErrorClassifier.classify(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null))
        assertEquals(ErrorKind.MALFORMED, ErrorClassifier.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
        assertEquals(ErrorKind.OTHER, ErrorClassifier.classify(PlaybackException.ERROR_CODE_UNSPECIFIED, null))
    }
}

class TimingTest {
    private val min = 60_000L
    @Test fun nextEpisodeHiddenUntilLastWindow() {
        val d = 45 * min
        assertEquals(NextEpisodeTiming.Phase.Hidden, NextEpisodeTiming.phase(d - 60_000, d, true, false, true))
        assertEquals(NextEpisodeTiming.Phase.Offer, NextEpisodeTiming.phase(d - 40_000, d, true, false, true))
        assertEquals(NextEpisodeTiming.Phase.Offer, NextEpisodeTiming.phase(d - 40_000, d, true, false, false))
        assertEquals(NextEpisodeTiming.Phase.Countdown(10), NextEpisodeTiming.phase(d - 10_000, d, true, false, true))
        assertEquals(NextEpisodeTiming.Phase.Countdown(3), NextEpisodeTiming.phase(d - 2_500, d, true, false, true))
        assertEquals(NextEpisodeTiming.Phase.Offer, NextEpisodeTiming.phase(d - 5_000, d, true, false, false))
    }
    @Test fun nextEpisodeHiddenWhenNotApplicable() {
        val d = 45 * min
        assertEquals(NextEpisodeTiming.Phase.Hidden, NextEpisodeTiming.phase(d - 5_000, d, false, false, true))
        assertEquals(NextEpisodeTiming.Phase.Hidden, NextEpisodeTiming.phase(d - 5_000, d, true, true, true))
        assertEquals(NextEpisodeTiming.Phase.Hidden, NextEpisodeTiming.phase(d - 5_000, 0, true, false, true))
        assertEquals(NextEpisodeTiming.Phase.Hidden, NextEpisodeTiming.phase(20_000, 60_000, true, false, true)) // clip too short
    }
    @Test fun progressThrottle() {
        val t = ProgressThrottle(10_000)
        assertTrue(t.shouldReport(0, 1000, false))
        assertFalse(t.shouldReport(5_000, 6000, false))
        assertTrue(t.shouldReport(10_000, 11_000, false))
        assertTrue(t.shouldReport(12_000, 13_000, true)) // pause/seek forces
        assertFalse(t.shouldReport(12_100, 13_000, true)) // duplicate suppressed
        assertFalse(t.shouldReport(30_000, -1, false))
    }
    @Test fun timeFormats() {
        assertEquals("0:05", TimeFormat.clock(5_400))
        assertEquals("1:02:03", TimeFormat.clock(3_723_000))
        assertEquals("0:00", TimeFormat.clock(-5))
        assertEquals("+0.5 s", TimeFormat.delay(500))
        assertEquals("-1.2 s", TimeFormat.delay(-1200))
        assertEquals("0.0 s", TimeFormat.delay(0))
    }
}

class GestureAndHeaderTest {
    @Test fun scrub() {
        assertEquals(70_000L, GestureMath.scrubTarget(10_000, 500f, 1000f, 3_600_000)) // half width = 60 s
        assertEquals(0L, GestureMath.scrubTarget(10_000, -5000f, 1000f, 3_600_000))
        assertEquals(100_000L, GestureMath.scrubTarget(90_000, 5000f, 1000f, 100_000))
    }
    @Test fun levels() {
        assertEquals(1f, GestureMath.levelAfterDrag(0.9f, -500f, 1000f), 0.0001f)
        assertEquals(0f, GestureMath.levelAfterDrag(0.1f, 900f, 1000f), 0.0001f)
        assertEquals(0.5f - 0.11f, GestureMath.levelAfterDrag(0.5f, 100f, 1000f), 0.0001f)
    }
    @Test fun zones() {
        assertEquals(GestureMath.Zone.LEFT, GestureMath.zoneOf(100f, 1200f))
        assertEquals(GestureMath.Zone.CENTER, GestureMath.zoneOf(600f, 1200f))
        assertEquals(GestureMath.Zone.RIGHT, GestureMath.zoneOf(1100f, 1200f))
        assertTrue(GestureMath.isLeftHalf(10f, 100f))
    }
    @Test fun headersNotSentToSubtitleOnlyHosts() {
        val subs = setOf("subs.example.org")
        assertTrue(HeaderPolicy.shouldApply("cdn.example.com", "https", "stream.example.com", subs))
        assertTrue(HeaderPolicy.shouldApply("stream.example.com", "http", "stream.example.com", subs))
        assertFalse(HeaderPolicy.shouldApply("subs.example.org", "https", "stream.example.com", subs))
        assertFalse(HeaderPolicy.shouldApply(null, "https", "stream.example.com", subs))
        assertFalse(HeaderPolicy.shouldApply("x", "content", "stream.example.com", subs))
        assertEquals("a.b", HeaderPolicy.hostOf("https://A.b/x?y=1"))
    }
    @Test fun subtitleFormats() {
        assertEquals(SubtitleFormat.SUBRIP, SubtitleFormat.fromExtension("https://x/y.SRT?token=1"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.fromExtension("a.vtt"))
        assertEquals(SubtitleFormat.SSA, SubtitleFormat.fromExtension("a.ass"))
        assertNull(SubtitleFormat.fromExtension("https://x/subtitle/123"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.sniff("﻿WEBVTT\n\n"))
        assertEquals(SubtitleFormat.SSA, SubtitleFormat.sniff("[Script Info]\nTitle"))
        assertEquals(SubtitleFormat.TTML, SubtitleFormat.sniff("<?xml version"))
        assertEquals(SubtitleFormat.SUBRIP, SubtitleFormat.sniff("1\n00:00:01,000 --> 00:00:02,000"))
    }
}
