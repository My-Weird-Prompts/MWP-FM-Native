package com.myweirdprompts.fm.player.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myweirdprompts.fm.player.widget.PlayerWidget
import java.io.File

/**
 * Episode downloads. Each one is a WorkManager job (DownloadWorker) so it survives the
 * app being closed and retries on a flaky connection. Which episodes are on disk is
 * worked out by scanning the download folders, not stored, so files copied in or
 * deleted by hand are picked up on the next rescan.
 */
class Downloads(private val ctx: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** slug -> file, for every episode found on disk. */
    private val _onDisk = MutableStateFlow<Map<String, File>>(emptyMap())
    val onDisk: StateFlow<Map<String, File>> = _onDisk

    /** slug -> percent (0..100), for downloads in progress; -1 = queued. */
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress

    init { rescan() }

    fun rescan() = scope.launch {
        val found = HashMap<String, File>()
        Storage.searchDirs(ctx, prefs.settings.value.downloadDir).reversed().forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.endsWith(".part") && f.length() > 0) found[f.nameWithoutExtension] = f
            }
        }
        _onDisk.value = found
    }

    /** Local file for an episode, checked on disk (used by the player at queue time). */
    fun fileFor(slug: String): File? {
        _onDisk.value[slug]?.let { if (it.exists()) return it }
        Storage.searchDirs(ctx, prefs.settings.value.downloadDir).forEach { dir ->
            dir.listFiles { f -> f.nameWithoutExtension == slug && !f.name.endsWith(".part") }
                ?.firstOrNull()?.let { return it }
        }
        return null
    }

    fun enqueue(ep: Episode) = enqueue(ep.slug, ep.audioUrl, ep.title)

    fun enqueue(slug: String, audioUrl: String, title: String) {
        if (_onDisk.value.containsKey(slug)) return
        _progress.update { it + (slug to -1) }
        PlayerWidget.redrawAll(ctx)
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("slug" to slug, "url" to audioUrl, "title" to title))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("download")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("dl-" + slug, ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(slug: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork("dl-$slug")
        _progress.update { it - slug }
        PlayerWidget.redrawAll(ctx)
    }

    fun delete(slug: String) = scope.launch {
        fileFor(slug)?.delete()
        rescan().join()
        PlayerWidget.redrawAll(ctx)
    }

    // ---- called from DownloadWorker (same process) ----
    internal fun reportProgress(slug: String, pct: Int) = _progress.update { it + (slug to pct) }
    internal fun finished(slug: String, file: File?) {
        _progress.update { it - slug }
        if (file != null) _onDisk.update { it + (slug to file) }
        PlayerWidget.redrawAll(ctx)
    }
}
