package com.myweirdprompts.fm.player.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * Where episode files live.
 *
 * With "All files access" (MANAGE_EXTERNAL_STORAGE, Android 11+; WRITE_EXTERNAL_STORAGE
 * below that) downloads go to a real folder the listener picks, default
 * /storage/emulated/0/Podcasts/MWP FM, visible to file managers and other players.
 * Without it they go to the app's own external folder, which needs no permission
 * but is deleted with the app.
 *
 * Files are named <slug>.<ext>; the slug is the lookup key, so a folder can be
 * copied between devices and the app still recognises it.
 */
object Storage {
    fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Settings screen for the permission (Android 11+). */
    fun allFilesAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))

    fun defaultPublicDir(): File = File(Environment.getExternalStorageDirectory(), "Podcasts/MWP FM")

    fun privateDir(ctx: Context): File =
        ctx.getExternalFilesDir(Environment.DIRECTORY_PODCASTS) ?: File(ctx.filesDir, "episodes")

    /** The folder new downloads go to. */
    fun downloadDir(ctx: Context, custom: String?): File = when {
        !hasAllFilesAccess(ctx) -> privateDir(ctx)
        !custom.isNullOrBlank() -> File(custom)
        else -> defaultPublicDir()
    }

    /** Every folder that might hold earlier downloads (the current one first). */
    fun searchDirs(ctx: Context, custom: String?): List<File> =
        listOf(downloadDir(ctx, custom), defaultPublicDir(), privateDir(ctx)).distinct()

    fun extensionOf(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "m4a").lowercase().take(5)

    /**
     * A folder chosen with the system picker (ACTION_OPEN_DOCUMENT_TREE) comes back as a
     * tree URI like content://com.android.externalstorage.documents/tree/primary%3APodcasts.
     * With all-files access we want the plain path, so map volume "primary" to internal
     * shared storage and any other volume id to /storage/<id> (SD cards, USB).
     */
    fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().path
        else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}
