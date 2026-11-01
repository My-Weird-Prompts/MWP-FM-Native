package com.myweirdprompts.fm.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The catalogue, from the same endpoint the PWA reads:
 * https://stream.myweirdprompts.com/api/radio.json (served by the website,
 * website/src/pages/api/radio.json.ts in My-Weird-Prompts). Cached on disk with
 * its ETag so a cold start shows the last list instantly and a refresh with no
 * new episodes costs a 304.
 */
class FeedRepository(private val ctx: Context) {
    companion object {
        const val FEED_URL = "https://stream.myweirdprompts.com/api/radio.json"
    }

    private val cacheFile = File(ctx.filesDir, "radio.json")
    private val etagFile = File(ctx.filesDir, "radio.etag")
    private val lock = Mutex()

    private val _feed = MutableStateFlow<Feed?>(null)
    val feed: StateFlow<Feed?> = _feed

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Load the disk cache (if not yet loaded), then revalidate over the network. */
    suspend fun refresh() = lock.withLock {
        withContext(Dispatchers.IO) {
            if (_feed.value == null && cacheFile.exists()) {
                runCatching { _feed.value = parse(cacheFile.readText()) }
            }
            try {
                val conn = URL(FEED_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (_feed.value != null && etagFile.exists()) {
                    conn.setRequestProperty("If-None-Match", etagFile.readText())
                }
                when (conn.responseCode) {
                    304 -> Unit
                    200 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        _feed.value = parse(body)
                        cacheFile.writeText(body)
                        conn.getHeaderField("ETag")?.let { etagFile.writeText(it) }
                    }
                    else -> error("HTTP ${conn.responseCode}")
                }
                conn.disconnect()
                _error.value = null
            } catch (e: Exception) {
                _error.value = if (_feed.value == null) "Couldn't load episodes: ${e.message}" else null
            }
        }
    }

    private fun parse(text: String): Feed {
        val root = JSONObject(text)
        val eps = root.getJSONArray("episodes")
        val episodes = ArrayList<Episode>(eps.length())
        for (i in 0 until eps.length()) {
            val o = eps.getJSONObject(i)
            val audio = o.optString("audioUrl")
            if (audio.isEmpty()) continue
            episodes += Episode(
                slug = o.getString("slug"),
                title = o.optString("title"),
                excerpt = o.optString("excerpt"),
                audioUrl = audio,
                duration = o.optString("duration"),
                number = o.optInt("episodeNumber"),
                heroImage = o.optString("heroImage").ifEmpty { null },
                channels = o.optJSONArray("channels").strings().ifEmpty {
                    listOfNotNull(o.optString("channel").ifEmpty { null })
                },
                tags = o.optJSONArray("tags").strings(),
                date = o.optString("date"),
            )
        }
        episodes.sortWith(compareByDescending<Episode> { it.date }.thenByDescending { it.number })
        val chs = root.optJSONArray("channels") ?: JSONArray()
        val channels = (0 until chs.length()).map {
            val o = chs.getJSONObject(it)
            Channel(
                id = o.getString("id"),
                name = o.optString("name"),
                color = o.optString("color").ifEmpty { null },
                tier = o.optString("tier").ifEmpty { null },
                count = o.optInt("count"),
                art = o.optString("art").ifEmpty { null },
            )
        }
        return Feed(episodes, channels)
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
