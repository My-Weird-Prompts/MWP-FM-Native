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
        const val PREV = "prev"
        const val NEXT = "next"
        const val BACK = "back"
        const val FWD = "fwd"

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
        val pending = goAsync()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            try {
                if (c != null) {
                    if (c.mediaItemCount == 0 && action in setOf(TOGGLE, NEXT, PREV)) {
                        val last = app.prefs.lastQueue()
                        if (last != null) {
                            val (q, i) = last
                            c.setMediaItems(q.map { MediaItems.request(it) }, i, app.prefs.position(q[i].slug))
                            c.prepare()
                            c.play()
                        }
                    } else when (action) {
                        TOGGLE -> if (c.isPlaying || c.playWhenReady) c.pause() else { c.prepare(); c.play() }
                        PREV -> c.seekToPreviousMediaItem()
                        NEXT -> c.seekToNextMediaItem()
                        BACK -> c.seekBack()
                        FWD -> c.seekForward()
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
