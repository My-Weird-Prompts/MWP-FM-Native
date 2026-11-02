package com.myweirdprompts.fm.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.myweirdprompts.fm.player.App
import com.myweirdprompts.fm.player.R
import com.myweirdprompts.fm.player.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home-screen player: artwork boxed on the left; title, "My Weird Prompts • 18:49", a
 * progress bar and back 15 s / play-pause / forward 15 s / download on the right
 * (redesigned 2026-11-02 after a podcast-app widget Daniel liked). Resizable; the layout
 * steps down as it shrinks (full -> one row of controls -> back 15 + play/pause).
 * PlaybackService pushes state here (and the position every 10 s while playing);
 * Downloads redraws it when a download starts or finishes. Buttons go to
 * WidgetActionReceiver, which drives the session through a MediaController.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(ctx, mgr, it)) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        // An app update cancels every PendingIntent the app issued, which leaves the
        // widget's buttons dead until it is redrawn; redraw as soon as we're replaced.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) redrawAll(ctx) else super.onReceive(ctx, intent)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        mgr.updateAppWidget(id, build(ctx, mgr, id))
    }

    companion object {
        private const val ART_PX = 256
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun artFile(ctx: Context) = File(ctx.filesDir, "widget_art.png")
        private fun meta(ctx: Context) = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)

        /** What the download button and the progress bar act on, kept across process deaths. */
        data class Current(val slug: String, val audioUrl: String, val positionMs: Long, val durationMs: Long)

        fun current(ctx: Context): Current? {
            val m = meta(ctx)
            val slug = m.getString("slug", null) ?: return null
            return Current(slug, m.getString("audioUrl", "") ?: "", m.getLong("pos", 0), m.getLong("dur", 0))
        }

        /**
         * Record new state and redraw. A null title keeps the last one (shown as paused).
         * slug / audioUrl / positionMs / durationMs are kept when not given.
         */
        fun update(
            ctx: Context, playing: Boolean, title: String? = null, artUrl: String? = null,
            slug: String? = null, audioUrl: String? = null, positionMs: Long? = null, durationMs: Long? = null,
        ) {
            val app = ctx.applicationContext as App
            app.prefs.saveNowPlaying(title ?: app.prefs.nowPlayingTitle(), playing)
            meta(ctx).edit().apply {
                if (slug != null) putString("slug", slug)
                if (audioUrl != null) putString("audioUrl", audioUrl)
                if (positionMs != null) putLong("pos", positionMs)
                if (durationMs != null) putLong("dur", durationMs)
            }.commit()
            if (artUrl != null && artUrl != meta(ctx).getString("artUrl", null)) {
                scope.launch {
                    loadArt(ctx, artUrl)
                    meta(ctx).edit().putString("artUrl", artUrl).apply()
                    redrawAll(ctx)
                }
            }
            redrawAll(ctx)
        }

        private suspend fun loadArt(ctx: Context, url: String) {
            val req = ImageRequest.Builder(ctx).data(url).size(ART_PX).allowHardware(false).build()
            val res = ctx.imageLoader.execute(req)
            if (res is SuccessResult) {
                runCatching {
                    artFile(ctx).outputStream().use {
                        res.drawable.toBitmap(ART_PX, ART_PX).compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }

        fun redrawAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidget::class.java)).forEach {
                mgr.updateAppWidget(it, build(ctx, mgr, it))
            }
        }

        private fun build(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+: the launcher picks the layout for the current size itself.
                return RemoteViews(
                    mapOf(
                        SizeF(40f, 40f) to views(ctx, R.layout.widget_player_tiny),
                        SizeF(180f, 40f) to views(ctx, R.layout.widget_player_row),
                        SizeF(220f, 70f) to views(ctx, R.layout.widget_player),
                    )
                )
            }
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
            val layout = when {
                w < 180 -> R.layout.widget_player_tiny
                h < 70 || w < 220 -> R.layout.widget_player_row
                else -> R.layout.widget_player
            }
            return views(ctx, layout)
        }

        private fun views(ctx: Context, layout: Int): RemoteViews {
            val app = ctx.applicationContext as App
            val v = RemoteViews(ctx.packageName, layout)
            val title = app.prefs.nowPlayingTitle()
            val playing = app.prefs.nowPlayingPlaying()
            val cur = if (title != null) current(ctx) else null

            if (layout == R.layout.widget_player) {
                v.setTextViewText(R.id.widget_title, title ?: ctx.getString(R.string.widget_idle_title))
                val show = ctx.getString(R.string.widget_show)
                v.setTextViewText(
                    R.id.widget_sub,
                    when {
                        title == null -> ctx.getString(R.string.widget_idle_sub)
                        cur != null && cur.durationMs > 0 -> "$show • ${clock(cur.durationMs)}"
                        else -> show
                    },
                )
                if (cur != null && cur.durationMs > 0) {
                    v.setViewVisibility(R.id.widget_progress, android.view.View.VISIBLE)
                    v.setProgressBar(
                        R.id.widget_progress, 1000,
                        (cur.positionMs * 1000 / cur.durationMs).toInt().coerceIn(0, 1000), false,
                    )
                } else v.setViewVisibility(R.id.widget_progress, android.view.View.INVISIBLE)
                val f = artFile(ctx)
                val bmp = if (title != null && f.exists()) BitmapFactory.decodeFile(f.path) else null
                if (bmp != null) v.setImageViewBitmap(R.id.widget_art, bmp)
                else v.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
            }

            v.setImageViewResource(R.id.widget_toggle, if (playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
            v.setContentDescription(
                R.id.widget_toggle, ctx.getString(if (playing) R.string.widget_pause else R.string.widget_play),
            )

            v.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            v.setOnClickPendingIntent(R.id.widget_toggle, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.TOGGLE))
            v.setOnClickPendingIntent(R.id.widget_back, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.BACK))
            if (layout != R.layout.widget_player_tiny) {
                v.setOnClickPendingIntent(R.id.widget_fwd, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.FWD))
                v.setOnClickPendingIntent(R.id.widget_download, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.DOWNLOAD))
                // Download button: arrow, dimmed arrow while queued/downloading, tick once on disk.
                val slug = cur?.slug
                val (icon, desc, alpha) = when {
                    slug == null -> Triple(R.drawable.widget_ic_download, R.string.widget_download, 110)
                    app.downloads.fileFor(slug) != null -> Triple(R.drawable.widget_ic_done, R.string.widget_downloaded, 255)
                    slug in app.downloads.progress.value -> Triple(R.drawable.widget_ic_download, R.string.widget_downloading, 110)
                    else -> Triple(R.drawable.widget_ic_download, R.string.widget_download, 255)
                }
                v.setImageViewResource(R.id.widget_download, icon)
                v.setInt(R.id.widget_download, "setImageAlpha", alpha)
                v.setContentDescription(R.id.widget_download, ctx.getString(desc))
            }
            return v
        }

        private fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
