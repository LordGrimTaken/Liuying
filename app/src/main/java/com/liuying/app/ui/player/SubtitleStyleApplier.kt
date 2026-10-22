package com.liuying.app.ui.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/** Applies [PlayerSettings] subtitle styling to a Media3 [SubtitleView]; safe to call repeatedly (live preview). */
@OptIn(UnstableApi::class)
object SubtitleStyleApplier {
    fun apply(view: SubtitleView, s: PlayerSettings, controlsVisible: Boolean) {
        view.setApplyEmbeddedStyles(true)
        view.setApplyEmbeddedFontSizes(false)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.subtitleSizePercent.coerceIn(50, 200) / 100f)
        val extra = if (controlsVisible) 0.14f else 0f
        view.setBottomPaddingFraction((s.subtitleBottomOffsetPercent.coerceIn(0, 40) / 100f + extra).coerceAtMost(0.6f))
        val bg = when (s.subtitleBackground) {
            SubtitleBackground.NONE -> Color.TRANSPARENT
            SubtitleBackground.BOX -> Color.argb(
                (s.subtitleBackgroundOpacityPercent.coerceIn(0, 100) * 255 / 100), 0, 0, 0,
            )
        }
        val edge = when (s.subtitleEdge) {
            SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            SubtitleEdge.RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
        }
        view.setStyle(
            CaptionStyleCompat(
                s.subtitleColor, bg, Color.TRANSPARENT, edge, s.subtitleEdgeColor,
                if (s.subtitleBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
            ),
        )
    }
}
