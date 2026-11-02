package com.myweirdprompts.fm.player.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.myweirdprompts.fm.player.App
import com.myweirdprompts.fm.player.playback.MediaItems
import com.myweirdprompts.fm.player.playback.PlaybackService

/**
 * Widget button presses. Connects a MediaController to PlaybackService (binding
 * starts it if needed; a widget tap is a user interaction, so Android allows the
 * service to go foreground when playback starts), runs the command, disconnects.
 * Play with nothing loaded resumes the last queue saved in Prefs.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val TOGGLE = "toggle"
        const val BACK = "back"
        const val FWD = "fwd"
        const val DOWNLOAD = "download"

        /** The widget skips a fixed 15 s both ways, whatever the in-app skip settings are. */
        private const val SKIP_MS = 15_000L

        fun intent(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onReceive(receiverCtx: Context, intent: Intent) {
        val action = intent.action ?: return
        // A receiver's own context may not bind to services; the application context can.
        val ctx = receiverCtx.applicationContext
        val app = ctx.applicationContext as App
        if (action == DOWNLOAD) {
            // No session needed: queue the current episode (a no-op if it is already on disk).
            val cur = PlayerWidget.current(ctx)
            if (cur != null && cur.audioUrl.isNotEmpty() && app.prefs.nowPlayingTitle() != null) {
                app.downloads.enqueue(cur.slug, cur.audioUrl, app.prefs.nowPlayingTitle()!!)
            }
            return
        }
        val pending = goAsync()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            try {
                if (c != null) {
                    if (c.mediaItemCount == 0 && action == TOGGLE) {
                        val last = app.prefs.lastQueue()
                        if (last != null) {
                            val (q, i) = last
                            c.setMediaItems(q.map { MediaItems.request(it) }, i, app.prefs.position(q[i].slug))
                            c.prepare()
                            c.play()
                        }
                    } else when (action) {
                        TOGGLE -> if (c.isPlaying || c.playWhenReady) c.pause() else { c.prepare(); c.play() }
                        BACK -> c.seekTo((c.currentPosition - SKIP_MS).coerceAtLeast(0))
                        FWD -> {
                            val d = c.duration
                            val t = c.currentPosition + SKIP_MS
                            c.seekTo(if (d != androidx.media3.common.C.TIME_UNSET && d > 0) t.coerceAtMost(d - 1000) else t)
                        }
                    }
                }
            } finally {
                // Give the commands a moment to be delivered before unbinding.
                android.os.Handler(ctx.mainLooper).postDelayed({
                    c?.release()
                    pending.finish()
                }, 500)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }
}
