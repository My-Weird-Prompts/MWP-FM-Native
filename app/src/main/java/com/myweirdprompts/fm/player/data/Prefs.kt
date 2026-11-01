package com.myweirdprompts.fm.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Listener settings plus playback memory (resume positions, last queue). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val pos = ctx.getSharedPreferences("positions", Context.MODE_PRIVATE)

    data class Settings(
        val downloadDir: String?,
        val skipBack: Int,
        val skipForward: Int,
        val autoNext: Boolean,
    )

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings

    private fun read() = Settings(
        downloadDir = sp.getString("downloadDir", null),
        skipBack = sp.getInt("skipBack", 15),
        skipForward = sp.getInt("skipForward", 30),
        autoNext = sp.getBoolean("autoNext", true),
    )

    fun update(block: Settings.() -> Settings) {
        val s = _settings.value.block()
        sp.edit()
            .putString("downloadDir", s.downloadDir)
            .putInt("skipBack", s.skipBack)
            .putInt("skipForward", s.skipForward)
            .putBoolean("autoNext", s.autoNext)
            .apply()
        _settings.value = s
    }

    // ---- resume positions (ms), cleared when an episode is finished ----
    fun position(slug: String): Long = pos.getLong(slug, 0L)
    fun savePosition(slug: String, ms: Long) { pos.edit().putLong(slug, ms).apply() }
    fun clearPosition(slug: String) { pos.edit().remove(slug).apply() }

    // ---- last queue, for the widget / headset play button after the app was closed ----
    fun saveQueue(items: List<QueueEntry>, index: Int) {
        // Keep a window around the current item so the blob stays small.
        val from = (index - 20).coerceAtLeast(0)
        val to = (index + 80).coerceAtMost(items.size)
        val arr = JSONArray()
        items.subList(from, to).forEach { arr.put(it.toJson()) }
        sp.edit().putString("queue", JSONObject().put("i", index - from).put("q", arr).toString()).apply()
    }

    fun lastQueue(): Pair<List<QueueEntry>, Int>? {
        val raw = sp.getString("queue", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("q")
            val items = (0 until arr.length()).map { QueueEntry.fromJson(arr.getJSONObject(it)) }
            if (items.isEmpty()) null else items to o.getInt("i").coerceIn(0, items.size - 1)
        }.getOrNull()
    }

    // ---- what the widget last showed, so it survives reboots ----
    fun saveNowPlaying(title: String?, playing: Boolean) {
        sp.edit().putString("npTitle", title).putBoolean("npPlaying", playing).apply()
    }
    fun nowPlayingTitle(): String? = sp.getString("npTitle", null)
    fun nowPlayingPlaying(): Boolean = sp.getBoolean("npPlaying", false)
}
