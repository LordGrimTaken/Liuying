package com.liuying.app.ui.player.logic

/** Pure decisions for background audio and the notification permission (QA #41). */
object BackgroundAudio {
    /**
     * On activity stop: keep audio running when it is actually playing and the user did not close the player.
     * A paused player stays paused; a finishing activity is released by onDestroy (which also stops the service).
     */
    fun keepPlayingOnStop(isPlaying: Boolean, finishing: Boolean): Boolean = isPlaying && !finishing

    /** Ask for POST_NOTIFICATIONS once, on Android 13+, at first playback; a denial is remembered and never re-asked. */
    fun shouldRequestNotificationPermission(sdkInt: Int, granted: Boolean, askedBefore: Boolean): Boolean =
        sdkInt >= 33 && !granted && !askedBefore
}
