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
 * Home-screen player: artwork, title, previous / back / play-pause / forward / next.
 * Resizable; the layout steps down as it shrinks (full -> one row of controls ->
 * play/pause + next). PlaybackService pushes state here; buttons go to
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

        /** Record new state and redraw. A null title keeps the last one (shown as paused). */
        fun update(ctx: Context, playing: Boolean, title: String? = null, artUrl: String? = null) {
            val app = ctx.applicationContext as App
            app.prefs.saveNowPlaying(title ?: app.prefs.nowPlayingTitle(), playing)
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
                        SizeF(250f, 100f) to views(ctx, R.layout.widget_player),
                    )
                )
            }
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            val layout = when {
                w < 180 -> R.layout.widget_player_tiny
                h < 100 || w < 250 -> R.layout.widget_player_row
                else -> R.layout.widget_player
            }
            return views(ctx, layout)
        }

        private fun views(ctx: Context, layout: Int): RemoteViews {
            val app = ctx.applicationContext as App
            val v = RemoteViews(ctx.packageName, layout)
            val title = app.prefs.nowPlayingTitle()
            val playing = app.prefs.nowPlayingPlaying()

            if (layout == R.layout.widget_player) {
                v.setTextViewText(R.id.widget_title, title ?: ctx.getString(R.string.widget_idle_title))
                v.setTextViewText(
                    R.id.widget_sub,
                    if (title == null || !playing) ctx.getString(R.string.widget_idle_sub) else "MWP FM",
                )
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
            v.setOnClickPendingIntent(R.id.widget_next, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.NEXT))
            if (layout != R.layout.widget_player_tiny) {
                v.setOnClickPendingIntent(R.id.widget_prev, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.PREV))
                v.setOnClickPendingIntent(R.id.widget_back, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.BACK))
                v.setOnClickPendingIntent(R.id.widget_fwd, WidgetActionReceiver.intent(ctx, WidgetActionReceiver.FWD))
            }
            return v
        }
    }
}
