package com.liuying.app.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.liuying.app.ui.theme.LiuyingTheme

class PlayerActivity : ComponentActivity() {

    private var controller: PlayerController? = null
    private var finishing = false
    private var resumeOnStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PlayerLauncher.decode(intent)
        if (request == null) { finish(); return }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        val c = PlayerController(this, request)
        controller = c
        c.start()
        setContent {
            LiuyingTheme { PlayerScreen(c, onClose = { onPlayerFinished(PlayerExit.Closed) }) }
        }
        if (Build.VERSION.SDK_INT >= 26) registerPipReceiver()
    }

    private fun hideSystemBars() {
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (resumeOnStart) { controller?.play(); resumeOnStart = false }
    }

    override fun onStop() {
        super.onStop()
        val c = controller ?: return
        resumeOnStart = c.isPlaying && !finishing
        c.onBackgrounded()
        c.pause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = controller ?: return
        if (Build.VERSION.SDK_INT in 26..30 && c.settings.pipOnHome && c.isPlaying && !c.locked && c.phase == UiPhase.READY) {
            enterPip()
        }
    }

    @RequiresApi(26)
    private fun enterPip() {
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    @RequiresApi(26)
    private fun buildPipParams(): PictureInPictureParams {
        val c = controller
        val ratio = (c?.videoAspect ?: (16f / 9f)).coerceIn(0.42f, 2.39f)
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(listOf(pipPlayPauseAction(c?.isPlaying == true)))
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(c?.let { it.isPlaying && it.settings.pipOnHome && !it.locked } == true)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    @RequiresApi(26)
    private fun pipPlayPauseAction(playing: Boolean): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        val title = if (playing) "Pause" else "Play"
        return RemoteAction(icon, title, title, pi)
    }

    /** Called by the screen when play state or video size changes so PiP params stay current. */
    internal fun refreshPipParams() {
        if (Build.VERSION.SDK_INT >= 26) runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE) { controller?.togglePlay(); refreshPipParams() }
        }
    }

    private fun registerPipReceiver() {
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controller?.inPip = isInPictureInPictureMode
    }

    fun requestEnterPip() { if (Build.VERSION.SDK_INT >= 26) enterPip() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = controller
        if (c != null && !c.locked) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { c.seekStep(false); c.controlsVisible = true; return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { c.seekStep(true); c.controlsVisible = true; return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { c.togglePlay(); c.controlsVisible = true; return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { c.controlsVisible = true; c.poke(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Finish with a result for the caller; safe to call repeatedly. */
    internal fun onPlayerFinished(exit: PlayerExit) {
        if (finishing) return
        finishing = true
        val data = Intent()
        when (exit) {
            is PlayerExit.TryAnotherStream -> data.putExtra(PlayerLauncher.EXTRA_EXIT_KIND, "try_another")
                .putExtra(PlayerLauncher.EXTRA_EXIT_MEDIA, exit.mediaId)
                .putExtra(PlayerLauncher.EXTRA_EXIT_EPISODE, exit.episodeId)
                .putExtra(PlayerLauncher.EXTRA_EXIT_POSITION, exit.positionMs)
            is PlayerExit.PlayNext -> data.putExtra(PlayerLauncher.EXTRA_EXIT_KIND, "next")
                .putExtra(PlayerLauncher.EXTRA_EXIT_MEDIA, exit.mediaId)
                .putExtra(PlayerLauncher.EXTRA_EXIT_EPISODE, exit.nextEpisodeId)
            PlayerExit.Closed -> Unit
        }
        setResult(RESULT_OK, data)
        PlayerDependencies.onExit?.invoke(exit)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= 26) runCatching { unregisterReceiver(pipReceiver) }
        controller?.release()
        controller = null
    }

    companion object {
        private const val ACTION_PIP_TOGGLE = "com.liuying.app.player.PIP_TOGGLE"
    }
}
