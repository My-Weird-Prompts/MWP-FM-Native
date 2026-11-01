package com.myweirdprompts.fm.player.data

import org.json.JSONObject

data class Episode(
    val slug: String,
    val title: String,
    val excerpt: String,
    val audioUrl: String,
    val duration: String,
    val number: Int,
    val heroImage: String?,
    val channels: List<String>,
    val tags: List<String>,
    val date: String,
)

data class Channel(
    val id: String,
    val name: String,
    val color: String?,
    val tier: String?,
    val count: Int,
    val art: String?,
)

data class Feed(val episodes: List<Episode>, val channels: List<Channel>) {
    private val bySlug = episodes.associateBy { it.slug }
    fun episode(slug: String) = bySlug[slug]
    fun channel(id: String) = channels.firstOrNull { it.id == id }
    fun inChannel(id: String) = episodes.filter { id in it.channels }
}

/** One playable item, small enough to persist the whole queue for resumption. */
data class QueueEntry(val slug: String, val title: String, val audioUrl: String, val art: String?) {
    fun toJson(): JSONObject = JSONObject().put("s", slug).put("t", title).put("u", audioUrl).put("a", art ?: "")

    companion object {
        fun of(e: Episode) = QueueEntry(e.slug, e.title, e.audioUrl, e.heroImage)
        fun fromJson(o: JSONObject) = QueueEntry(
            o.getString("s"), o.getString("t"), o.getString("u"), o.optString("a").ifEmpty { null },
        )
    }
}
