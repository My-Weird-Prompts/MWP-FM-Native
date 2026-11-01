package com.myweirdprompts.fm.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.myweirdprompts.fm.player.App
import com.myweirdprompts.fm.player.data.Episode
import com.myweirdprompts.fm.player.data.Feed
import com.myweirdprompts.fm.player.data.QueueEntry

/**
 * The browse tree Android Auto (and any MediaBrowser) sees:
 *
 *   root
 *   ├── continue    Continue listening: the saved queue from the current item on
 *   ├── latest      the newest episodes
 *   ├── downloads   episodes on disk (play offline in the car)
 *   └── channels    one folder per channel ("ch:<id>"), newest first
 *
 * Folders have ids above; playable items use the episode slug as mediaId, with the
 * stream URL in requestMetadata.mediaUri (see MediaItems).
 */
object Library {
    const val ROOT = "root"
    const val CONTINUE = "continue"
    const val LATEST = "latest"
    const val DOWNLOADS = "downloads"
    const val CHANNELS = "channels"
    private const val CH = "ch:"
    const val PAGE = 100 // Auto shows long lists poorly; keep each folder to a screenful of scrolling

    fun root(): MediaItem = folder(ROOT, "MWP FM", null)

    fun children(app: App, feed: Feed?, parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOfNotNull(
            if (app.prefs.lastQueue() != null) folder(CONTINUE, "Continue listening", null) else null,
            folder(LATEST, "Latest", null),
            folder(DOWNLOADS, "Downloads", null),
            folder(CHANNELS, "Channels", null),
        )
        parentId == CONTINUE -> app.prefs.lastQueue()?.let { (q, i) -> q.drop(i).take(PAGE).map { MediaItems.request(it) } }.orEmpty()
        parentId == LATEST -> feed?.episodes.orEmpty().take(PAGE).map(::item)
        parentId == DOWNLOADS -> {
            val onDisk = app.downloads.onDisk.value
            feed?.episodes.orEmpty().filter { onDisk.containsKey(it.slug) }.take(PAGE).map(::item)
        }
        parentId == CHANNELS -> feed?.channels.orEmpty().filter { it.count > 0 }.sortedBy { it.name }
            .map { folder(CH + it.id, it.name, it.art) }
        parentId.startsWith(CH) -> feed?.inChannel(parentId.removePrefix(CH)).orEmpty().take(PAGE).map(::item)
        else -> emptyList()
    }

    /** Everything after [slug] in the folder it was picked from, so the car keeps playing. */
    fun queueFrom(feed: Feed?, slug: String): List<Episode> {
        val eps = feed?.episodes ?: return emptyList()
        val i = eps.indexOfFirst { it.slug == slug }
        return if (i < 0) emptyList() else eps.subList(i, minOf(eps.size, i + PAGE))
    }

    /** Voice search ("play … on MWP FM"): all words in title, excerpt or tags; empty = latest. */
    fun search(feed: Feed?, query: String?): List<Episode> {
        val eps = feed?.episodes ?: return emptyList()
        val words = query.orEmpty().lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return eps.take(PAGE)
        return eps.filter { e ->
            val hay = (e.title + " " + e.excerpt + " " + e.tags.joinToString(" ")).lowercase()
            words.all { it in hay }
        }.take(PAGE)
    }

    fun item(e: Episode): MediaItem = MediaItems.request(QueueEntry.of(e))

    private fun folder(id: String, title: String, art: String?): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(art?.let { Uri.parse(it) })
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()
}
