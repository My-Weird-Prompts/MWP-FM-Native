package com.myweirdprompts.fm.player

import android.content.ComponentName
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myweirdprompts.fm.player.playback.Library
import com.myweirdprompts.fm.player.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Browses PlaybackService the way Android Auto does (MediaBrowser → root → folders →
 * episodes). Needs network: it loads the live catalogue. Run: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LibraryBrowseTest {
    @Test
    fun browseTree() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaBrowser.Builder(ctx, token).setApplicationLooper(ctx.mainLooper).buildAsync()
        val browser = future.get(20, TimeUnit.SECONDS)
        try {
            fun <T> on(block: () -> T): T {
                var r: T? = null
                InstrumentationRegistry.getInstrumentation().runOnMainSync { r = block() }
                return r!!
            }
            val root = on { browser.getLibraryRoot(null) }.get(20, TimeUnit.SECONDS).value!!
            assertEquals(Library.ROOT, root.mediaId)

            val top = on { browser.getChildren(Library.ROOT, 0, 50, null) }.get(30, TimeUnit.SECONDS).value!!
            val ids = top.map { it.mediaId }
            assertTrue("top folders: $ids", ids.containsAll(listOf(Library.LATEST, Library.DOWNLOADS, Library.CHANNELS)))

            val latest = on { browser.getChildren(Library.LATEST, 0, 20, null) }.get(60, TimeUnit.SECONDS).value!!
            assertEquals(20, latest.size)
            assertTrue(latest.all { it.mediaMetadata.isPlayable == true && it.requestMetadata.mediaUri != null })

            val channels = on { browser.getChildren(Library.CHANNELS, 0, 100, null) }.get(30, TimeUnit.SECONDS).value!!
            assertTrue("channels: ${channels.size}", channels.size > 5)
            val firstChannel = on { browser.getChildren(channels[0].mediaId, 0, 10, null) }.get(30, TimeUnit.SECONDS).value!!
            assertTrue(firstChannel.isNotEmpty())

            val search = on { browser.search("benchmark", null) }.get(30, TimeUnit.SECONDS)
            assertTrue(search.resultCode >= 0)
            val results = on { browser.getSearchResult("benchmark", 0, 10, null) }.get(30, TimeUnit.SECONDS).value!!
            assertTrue("search results", results.isNotEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { browser.release() }
        }
    }
}
