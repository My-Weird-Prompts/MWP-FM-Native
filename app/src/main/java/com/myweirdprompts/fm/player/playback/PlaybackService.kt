package com.myweirdprompts.fm.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.myweirdprompts.fm.player.App
import com.myweirdprompts.fm.player.ui.MainActivity
import com.myweirdprompts.fm.player.widget.PlayerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer and the MediaSession. Being a MediaSessionService is what keeps
 * playback alive in the background: Media3 runs it as a mediaPlayback foreground
 * service with the notification and lock-screen controls while something plays.
 * The UI, the widget and Bluetooth/headset buttons are all controllers of this session.
 */
class PlaybackService : MediaSessionService() {
    private lateinit var app: App
    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var saver: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as App
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)
        exo.pauseAtEndOfMediaItems = !app.prefs.settings.value.autoNext

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, SkipPlayer(exo))
            .setCallback(Callback())
            .setSessionActivity(open)
            .build()

        scope.launch { app.prefs.settings.collect { exo.pauseAtEndOfMediaItems = !it.autoNext } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps playing audio going; stop only if paused.
        if (!exo.playWhenReady || exo.mediaItemCount == 0) {
            savePosition()
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePosition()
        scope.cancel()
        session?.release()
        exo.release()
        session = null
        PlayerWidget.update(this, playing = false)
        super.onDestroy()
    }

    /** Skip back/forward use the lengths in Settings at the moment of the press. */
    private inner class SkipPlayer(p: Player) : ForwardingPlayer(p) {
        override fun getSeekBackIncrement() = app.prefs.settings.value.skipBack * 1000L
        override fun getSeekForwardIncrement() = app.prefs.settings.value.skipForward * 1000L
        override fun seekBack() = seekTo((currentPosition - seekBackIncrement).coerceAtLeast(0))
        override fun seekForward() {
            val d = duration
            val t = currentPosition + seekForwardIncrement
            seekTo(if (d != C.TIME_UNSET) t.coerceAtMost(d) else t)
        }
    }

    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(resolve(mediaItems).toMutableList())

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val items = resolve(mediaItems)
            val idx = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            val pos = if (startPositionMs == C.TIME_UNSET && items.isNotEmpty())
                app.prefs.position(items[idx].mediaId) else startPositionMs
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, idx, pos))
        }

        /** Play pressed (widget, headset, system media controls) with nothing loaded. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = app.prefs.lastQueue()
                ?: return Futures.immediateFailedFuture(IllegalStateException("Nothing to resume"))
            val (q, i) = last
            val items = q.map { MediaItems.playable(it, app.downloads.fileFor(it.slug)) }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, i, app.prefs.position(q[i].slug))
            )
        }
    }

    private fun resolve(items: List<MediaItem>): List<MediaItem> = items.map { item ->
        val e = MediaItems.entry(item)
        MediaItems.playable(e, app.downloads.fileFor(e.slug))
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                saveQueue()
                val item = exo.currentMediaItem
                PlayerWidget.update(
                    this@PlaybackService,
                    title = item?.mediaMetadata?.title?.toString(),
                    artUrl = item?.mediaMetadata?.artworkUri?.toString(),
                    playing = exo.isPlaying || (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING),
                )
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (exo.isPlaying) startSaver() else { saver?.cancel(); savePosition() }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The previous item ended naturally: forget its resume point.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val prev = exo.previousMediaItemIndex
                if (prev != C.INDEX_UNSET) app.prefs.clearPosition(exo.getMediaItemAt(prev).mediaId)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) exo.currentMediaItem?.let { app.prefs.clearPosition(it.mediaId) }
        }
    }

    private fun startSaver() {
        saver?.cancel()
        saver = scope.launch {
            while (isActive) { delay(10_000); savePosition() }
        }
    }

    private fun savePosition() {
        val item = exo.currentMediaItem ?: return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur != C.TIME_UNSET && dur > 0 && pos > dur - 30_000) app.prefs.clearPosition(item.mediaId)
        else if (pos > 5_000) app.prefs.savePosition(item.mediaId, pos)
    }

    private fun saveQueue() {
        if (exo.mediaItemCount == 0) return
        val entries = (0 until exo.mediaItemCount).map { MediaItems.entry(exo.getMediaItemAt(it)) }
        app.prefs.saveQueue(entries, exo.currentMediaItemIndex)
    }
}
