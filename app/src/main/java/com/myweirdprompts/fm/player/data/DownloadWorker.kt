package com.myweirdprompts.fm.player.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.myweirdprompts.fm.player.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads one episode to <download dir>/<slug>.<ext>, via a .part file renamed on success. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as App

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val slug = inputData.getString("slug") ?: return@withContext Result.failure()
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val title = inputData.getString("title") ?: slug
        runCatching { setForeground(foregroundInfo(title, 0)) } // best effort; still runs without it

        val dir = Storage.downloadDir(applicationContext, app.prefs.settings.value.downloadDir)
        val target = File(dir, slug + "." + Storage.extensionOf(url))
        val part = File(dir, target.name + ".part")
        try {
            if (!dir.exists() && !dir.mkdirs()) error("Cannot create folder $dir")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) { part.delete(); return@withContext Result.failure() }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                app.downloads.reportProgress(slug, pct)
                                if (pct % 5 == 0) runCatching { setForeground(foregroundInfo(title, pct)) }
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && done != total) error("Short read $done/$total")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) error("Rename failed")
            app.downloads.finished(slug, target)
            Result.success()
        } catch (e: Exception) {
            part.delete()
            if (runAttemptCount < 3) Result.retry()
            else { app.downloads.finished(slug, null); Result.failure() }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString("title") ?: "", 0)

    private fun foregroundInfo(title: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val nid = id.hashCode()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(nid, n)
    }

    companion object { const val CHANNEL = "downloads" }
}
