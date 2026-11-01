package com.myweirdprompts.fm.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.myweirdprompts.fm.player.data.QueueEntry
import java.io.File

/**
 * MediaItem <-> QueueEntry. A controller (the UI, the widget) sends items whose
 * playable URI travels in requestMetadata.mediaUri, because Media3 strips
 * localConfiguration between controller and session; PlaybackService.resolve()
 * turns them into playable items, preferring a downloaded file.
 */
object MediaItems {
    const val ARTIST = "My Weird Prompts"
    const val ALBUM = "MWP FM"

    fun request(e: QueueEntry): MediaItem = MediaItem.Builder()
        .setMediaId(e.slug)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(e.audioUrl)).build())
        .setMediaMetadata(metadata(e))
        .build()

    fun playable(e: QueueEntry, local: File?): MediaItem = MediaItem.Builder()
        .setMediaId(e.slug)
        .setUri(local?.let { Uri.fromFile(it) } ?: Uri.parse(e.audioUrl))
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(e.audioUrl)).build())
        .setMediaMetadata(metadata(e))
        .build()

    fun entry(item: MediaItem): QueueEntry = QueueEntry(
        slug = item.mediaId,
        title = item.mediaMetadata.title?.toString() ?: item.mediaId,
        audioUrl = (item.requestMetadata.mediaUri ?: item.localConfiguration?.uri)?.toString() ?: "",
        art = item.mediaMetadata.artworkUri?.toString(),
    )

    private fun metadata(e: QueueEntry) = MediaMetadata.Builder()
        .setTitle(e.title)
        .setArtist(ARTIST)
        .setAlbumTitle(ALBUM)
        .setArtworkUri(e.art?.let { Uri.parse(it) })
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
        .build()
}
