package com.liuying.app.data.settings

import com.liuying.app.ui.player.SubtitleBackground
import com.liuying.app.ui.player.SubtitleEdge
import com.liuying.app.ui.player.logic.NextEpisodeTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsMapperTest {
    @Test fun subtitleStyleSurvivesAPlayerRoundTrip() {
        val app = AppSettings()
        val p = PlayerSettingsMapper.from(app, "en").copy(
            subtitleBold = true, subtitleColor = 0xFFFFD54F.toInt(), subtitleBackground = SubtitleBackground.BOX,
            subtitleBackgroundOpacityPercent = 35, subtitleEdge = SubtitleEdge.DROP_SHADOW, subtitleEdgeColor = 0xFF112233.toInt(),
        )
        val saved = PlayerSettingsMapper.applyBack(app, p)
        val back = PlayerSettingsMapper.from(saved, "en")
        assertTrue(back.subtitleBold)
        assertEquals(0xFFFFD54F.toInt(), back.subtitleColor)
        assertEquals(SubtitleBackground.BOX, back.subtitleBackground)
        assertEquals(35, back.subtitleBackgroundOpacityPercent)
        assertEquals(SubtitleEdge.DROP_SHADOW, back.subtitleEdge)
        assertEquals(0xFF112233.toInt(), back.subtitleEdgeColor)
    }

    @Test fun olderInstallsWithoutAnEdgeFallBackToTheOutlineFlag() {
        assertEquals(SubtitleEdge.OUTLINE, PlayerSettingsMapper.from(AppSettings(subtitleOutline = true), "en").subtitleEdge)
        assertEquals(SubtitleEdge.NONE, PlayerSettingsMapper.from(AppSettings(subtitleOutline = false), "en").subtitleEdge)
    }

    @Test fun playbackSettingsReachThePlayer() {
        val p = PlayerSettingsMapper.from(
            AppSettings(orientation = OrientationMode.AutoRotate, keepScreenOn = false, pauseOnHeadphoneUnplug = false, nextEpisodeCountdownSec = 20, askBeforeNext = true),
            "en",
        )
        assertTrue(p.autoRotate); assertFalse(p.keepScreenOn); assertFalse(p.pauseOnHeadphoneUnplug)
        assertEquals(20, p.nextCountdownSec); assertTrue(p.askBeforeNext)
    }

    @Test fun subtitleLanguageDefaultsToDeviceThenEnglish() {
        assertEquals(listOf("de", "en"), PlayerSettingsMapper.from(AppSettings(), "de").preferredSubtitleLanguages)
        assertEquals(listOf("en"), PlayerSettingsMapper.from(AppSettings(), "en").preferredSubtitleLanguages)
    }

    @Test fun countdownLengthFollowsTheSetting() {
        val d = 60 * 60_000L
        assertEquals(NextEpisodeTiming.Phase.Offer, NextEpisodeTiming.phase(d - 20_000, d, true, false, true)) // default 10 s
        assertEquals(NextEpisodeTiming.Phase.Countdown(20), NextEpisodeTiming.phase(d - 20_000, d, true, false, true, countdownMs = 20_000))
        assertEquals(NextEpisodeTiming.Phase.Offer, NextEpisodeTiming.phase(d - 5_000, d, true, false, false, countdownMs = 20_000))
    }
}
