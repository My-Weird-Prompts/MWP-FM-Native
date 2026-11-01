package com.myweirdprompts.fm.player.ui

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.myweirdprompts.fm.player.data.Episode
import com.myweirdprompts.fm.player.data.QueueEntry
import com.myweirdprompts.fm.player.playback.MediaItems
import com.myweirdprompts.fm.player.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUi(
    val slug: String? = null,
    val title: String = "",
    val art: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
)

/** The UI's MediaController onto PlaybackService, as observable state. */
class PlayerConnection(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private var controller: MediaController? = null
    private val future: ListenableFuture<MediaController>
    private var ticker: Job? = null

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
            })
            publish()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun publish() {
        val c = controller ?: return
        val item = c.currentMediaItem
        _ui.value = PlayerUi(
            slug = item?.mediaId,
            title = item?.mediaMetadata?.title?.toString() ?: "",
            art = item?.mediaMetadata?.artworkUri?.toString(),
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            hasNext = c.hasNextMediaItem(),
            hasPrev = c.hasPreviousMediaItem(),
        )
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = viewModelScope.launch { while (isActive) { delay(500); publish() } }
        } else if (!c.isPlaying) ticker?.cancel()
    }

    /** Play [list] starting at [index]; the rest of the list becomes the queue. */
    fun play(list: List<Episode>, index: Int) {
        val c = controller ?: return
        // Cap the queue: a 6,000-episode list would be a needlessly large IPC.
        val from = (index - 50).coerceAtLeast(0)
        val to = (index + 200).coerceAtMost(list.size)
        val items = list.subList(from, to).map { MediaItems.request(QueueEntry.of(it)) }
        c.setMediaItems(items, index - from, C.TIME_UNSET) // service fills in the resume point
        c.prepare()
        c.play()
    }

    fun shuffle(list: List<Episode>) {
        if (list.isEmpty()) return
        play(list.shuffled().take(200), 0)
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { it.prepare(); it.play() } } }
    fun next() { controller?.seekToNextMediaItem() }
    fun prev() { controller?.seekToPreviousMediaItem() }
    fun back() { controller?.seekBack(); publish() }
    fun forward() { controller?.seekForward(); publish() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); publish() }

    override fun onCleared() {
        MediaController.releaseFuture(future)
        super.onCleared()
    }
}
