package com.liuying.app.ui.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Process-wide handoff between the activity-owned [PlayerController] and [PlayerService]. */
object PlayerSessionHub {
    @Volatile var session: MediaSession? = null
        private set

    fun publish(ctx: Context, s: MediaSession) {
        session = s
        // Started from the foreground player; Media3 promotes the service to foreground while audio plays.
        runCatching { ctx.startService(Intent(ctx, PlayerService::class.java)) }
    }

    fun clear(ctx: Context, s: MediaSession) {
        if (session === s) session = null
        runCatching { ctx.stopService(Intent(ctx, PlayerService::class.java)) }
    }

    /** Playback ended: drop the notification without releasing the session (the controller does that). */
    fun stop(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, PlayerService::class.java)) }
    }
}

/**
 * Foreground (mediaPlayback) service that exposes the player's MediaSession so audio keeps playing with the screen
 * off or the app backgrounded, with a media notification (play/pause/seek). The player itself stays owned by
 * [PlayerController]; this service only hosts the session.
 */
class PlayerService : MediaSessionService() {

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlayerSessionHub.session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sync()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        sync()
    }

    private fun sync() {
        val current = PlayerSessionHub.session
        for (old in sessions.toList()) if (old !== current) removeSession(old)
        if (current == null) { stopSelf(); return }
        if (sessions.none { it === current }) addSession(current)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiping the app away closes the player: do not leave audio running with nothing to control it
        PlayerSessionHub.session?.player?.pause()
        stopSelf()
    }

    companion object {
        fun sessionActivity(ctx: Context): PendingIntent? {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            return PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
