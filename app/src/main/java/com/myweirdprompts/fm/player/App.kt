package com.myweirdprompts.fm.player

import android.app.Application
import com.myweirdprompts.fm.player.data.Downloads
import com.myweirdprompts.fm.player.data.FeedRepository
import com.myweirdprompts.fm.player.data.Prefs
import com.myweirdprompts.fm.player.widget.PlayerWidget

class App : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var feed: FeedRepository; private set
    lateinit var downloads: Downloads; private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        feed = FeedRepository(this)
        downloads = Downloads(this, prefs)
        // A fresh process means nothing is playing, whatever the widget last showed
        // (the process may have been killed without PlaybackService.onDestroy running).
        prefs.saveNowPlaying(prefs.nowPlayingTitle(), playing = false)
        PlayerWidget.redrawAll(this)
    }
}
