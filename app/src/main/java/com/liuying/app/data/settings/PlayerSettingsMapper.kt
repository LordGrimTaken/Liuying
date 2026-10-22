package com.liuying.app.data.settings

import com.liuying.app.ui.player.PlayerSettings
import com.liuying.app.ui.player.SubtitleBackground
import com.liuying.app.ui.player.SubtitleEdge
import java.util.Locale
import com.liuying.app.ui.player.AspectMode as PlayerAspect
import com.liuying.app.ui.player.SubtitleMode as PlayerSubMode

/** Maps the app-wide [AppSettings] onto what the player module understands (and back for in-player changes). */
object PlayerSettingsMapper {
    fun from(s: AppSettings, deviceLanguage: String = Locale.getDefault().language.ifBlank { "en" }): PlayerSettings {
        val gestures = s.gestureBrightness || s.gestureVolume || s.gestureSpeedHold || s.gesturePinchZoom
        return PlayerSettings(
            preferredAudioLanguages = s.preferredAudioLanguages.ifEmpty { listOf(deviceLanguage) },
            preferredSubtitleLanguages = s.preferredSubtitleLanguages.ifEmpty { listOf(deviceLanguage, "en").distinct() },
            subtitleMode = when (s.subtitleMode) {
                SubtitleMode.OffUnlessNeeded -> PlayerSubMode.OFF_UNLESS_NEEDED
                SubtitleMode.AlwaysOn -> PlayerSubMode.ALWAYS_ON
                SubtitleMode.ForcedOnly -> PlayerSubMode.FORCED_ONLY
            },
            autoFetchExternalSubtitles = s.autoFetchSubtitles,
            subtitleSizePercent = (s.subtitleSizeScale * 100).toInt().coerceIn(50, 200),
            subtitleColor = s.subtitleColor.toInt(),
            subtitleBackground = if (s.subtitleBackground) SubtitleBackground.BOX else SubtitleBackground.NONE,
            subtitleBold = s.subtitleBold,
            subtitleBackgroundOpacityPercent = s.subtitleBgOpacity.coerceIn(0, 100),
            subtitleEdgeColor = s.subtitleEdgeColor.toInt(),
            subtitleEdge = runCatching { SubtitleEdge.valueOf(s.subtitleEdge) }.getOrNull()
                ?: if (s.subtitleOutline) SubtitleEdge.OUTLINE else SubtitleEdge.NONE,
            subtitleBottomOffsetPercent = s.subtitleBottomPercent.coerceIn(0, 40),
            defaultSpeed = s.defaultSpeed,
            aspectDefault = when (s.defaultAspect) {
                AspectMode.Fill -> PlayerAspect.ZOOM
                AspectMode.Stretch -> PlayerAspect.FILL
                else -> PlayerAspect.FIT
            },
            gesturesEnabled = gestures,
            swipeBrightnessVolumeEnabled = s.gestureBrightness || s.gestureVolume,
            longPressSpeedEnabled = s.gestureSpeedHold,
            seekStepSeconds = s.seekStepSec,
            autoPlayNext = s.autoPlayNext,
            nextCountdownSec = s.nextEpisodeCountdownSec.coerceIn(5, 30),
            askBeforeNext = s.askBeforeNext,
            pauseOnHeadphoneUnplug = s.pauseOnHeadphoneUnplug,
            keepScreenOn = s.keepScreenOn,
            autoRotate = s.orientation == OrientationMode.AutoRotate,
        )
    }

    /** Applies the few values the player can change itself (speed, aspect, subtitle size/position). */
    fun applyBack(s: AppSettings, p: PlayerSettings): AppSettings = s.copy(
        defaultSpeed = p.defaultSpeed,
        defaultAspect = when (p.aspectDefault) {
            PlayerAspect.ZOOM -> AspectMode.Fill
            PlayerAspect.FILL -> AspectMode.Stretch
            PlayerAspect.FIT -> if (s.defaultAspect == AspectMode.Fill || s.defaultAspect == AspectMode.Stretch) AspectMode.Fit else s.defaultAspect
        },
        subtitleSizeScale = p.subtitleSizePercent / 100f,
        subtitleBold = p.subtitleBold,
        subtitleColor = p.subtitleColor.toLong() and 0xFFFFFFFFL,
        subtitleBackground = p.subtitleBackground == SubtitleBackground.BOX,
        subtitleBgOpacity = p.subtitleBackgroundOpacityPercent,
        subtitleEdge = p.subtitleEdge.name,
        subtitleEdgeColor = p.subtitleEdgeColor.toLong() and 0xFFFFFFFFL,
        subtitleOutline = p.subtitleEdge != SubtitleEdge.NONE,
        subtitleBottomPercent = p.subtitleBottomOffsetPercent,
    )
}
