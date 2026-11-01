package com.myweirdprompts.fm.player.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.myweirdprompts.fm.player.App
import com.myweirdprompts.fm.player.BuildConfig
import com.myweirdprompts.fm.player.data.Episode
import com.myweirdprompts.fm.player.data.Storage
import kotlinx.coroutines.launch

// ---------- shared pieces ----------

@Composable
fun MiniPlayer(ui: PlayerUi, player: PlayerConnection, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            val frac = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outline)) {
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(2.dp).background(MaterialTheme.colorScheme.primary))
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(ui.art, null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Text(
                    ui.title, Modifier.weight(1f).padding(horizontal = 12.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                )
                IconButton(onClick = player::toggle) {
                    Icon(if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (ui.playing) "Pause" else "Play")
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(app: App, ep: Episode, current: Boolean, onPlay: () -> Unit) {
    val onDisk by app.downloads.onDisk.collectAsStateWithLifecycle()
    val progress by app.downloads.progress.collectAsStateWithLifecycle()
    val feed by app.feed.feed.collectAsStateWithLifecycle()
    val notify = notificationPermissionLauncher()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(ep.heroImage, null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val ch = ep.channels.firstOrNull()?.let { feed?.channel(it)?.name }
            Text(
                listOfNotNull(ep.duration.ifEmpty { null }, ch).joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        val p = progress[ep.slug]
        when {
            onDisk.containsKey(ep.slug) -> Icon(Icons.Filled.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            p != null -> Box(Modifier.size(48.dp).clickable { app.downloads.cancel(ep.slug) }, contentAlignment = Alignment.Center) {
                if (p < 0) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else CircularProgressIndicator(progress = { p / 100f }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            else -> IconButton(onClick = { notify(); app.downloads.enqueue(ep) }) { Icon(Icons.Filled.Download, "Download") }
        }
    }
}

/** Android 13+: ask once for notifications so download progress can show. */
@Composable
private fun notificationPermissionLauncher(): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val ctx = LocalContext.current
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun EpisodeList(app: App, player: PlayerConnection, eps: List<Episode>, byDay: Boolean, header: (@Composable () -> Unit)? = null) {
    val ui by player.ui.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        if (header != null) item { header() }
        var lastDay: String? = null
        eps.forEachIndexed { i, ep ->
            if (byDay && ep.date != lastDay) {
                lastDay = ep.date
                item(key = "d-" + ep.date) {
                    Text(
                        prettyDay(ep.date), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = ep.slug) { EpisodeRow(app, ep, ep.slug == ui.slug) { player.play(eps, i) } }
        }
    }
}

private fun prettyDay(iso: String): String = runCatching {
    val d = java.time.LocalDate.parse(iso)
    val today = java.time.LocalDate.now()
    when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))
    }
}.getOrDefault(iso)

@Composable
private fun Title(text: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        else Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun Loading(app: App) {
    val err by app.feed.error.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (err != null) Text(err!!, Modifier.padding(24.dp)) else CircularProgressIndicator()
    }
}

// ---------- screens ----------

@Composable
fun LatestScreen(app: App, player: PlayerConnection) {
    val feed by app.feed.feed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val f = feed ?: return Loading(app)
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; app.feed.refresh(); refreshing = false } },
    ) {
        EpisodeList(app, player, f.episodes, byDay = true) { Title("MWP FM") }
    }
}

@Composable
fun ChannelsScreen(app: App, onOpen: (String) -> Unit) {
    val feed by app.feed.feed.collectAsStateWithLifecycle()
    val f = feed ?: return Loading(app)
    val chs = f.channels.filter { it.count > 0 }.sortedBy { it.name }
    Column {
        Title("Channels")
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(chs, key = { it.id }) { c ->
                Column(Modifier.padding(4.dp).clickable { onOpen(c.id) }) {
                    val tint = runCatching { Color(android.graphics.Color.parseColor(c.color)) }.getOrDefault(Color.Gray)
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(tint)) {
                        AsyncImage(c.art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(c.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text("${c.count} episodes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ChannelScreen(app: App, player: PlayerConnection, id: String, onBack: () -> Unit) {
    val feed by app.feed.feed.collectAsStateWithLifecycle()
    val f = feed ?: return Loading(app)
    val eps = remember(f, id) { f.inChannel(id) }
    EpisodeList(app, player, eps, byDay = false) {
        Column {
            Title(f.channel(id)?.name ?: id, onBack)
            Button(onClick = { player.shuffle(eps) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Shuffle ${eps.size} episodes")
            }
        }
    }
}

@Composable
fun SearchScreen(app: App, player: PlayerConnection) {
    val feed by app.feed.feed.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    val f = feed ?: return Loading(app)
    val results = remember(f, q) {
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) emptyList()
        else f.episodes.filter { e ->
            val hay = (e.title + " " + e.excerpt + " " + e.tags.joinToString(" ")).lowercase()
            words.all { it in hay }
        }.take(300)
    }
    Column {
        Title("Search")
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Titles, descriptions, tags") }, singleLine = true,
        )
        EpisodeList(app, player, results, byDay = false)
    }
}

@Composable
fun DownloadsScreen(app: App, player: PlayerConnection) {
    val feed by app.feed.feed.collectAsStateWithLifecycle()
    val onDisk by app.downloads.onDisk.collectAsStateWithLifecycle()
    val settings by app.prefs.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LifecycleResumeEffect(Unit) { app.downloads.rescan(); onPauseOrDispose { } }
    val f = feed ?: return Loading(app)
    val eps = remember(f, onDisk) { f.episodes.filter { onDisk.containsKey(it.slug) } }
    val ui by player.ui.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Title("Downloads")
            Text(
                "Folder: " + Storage.downloadDir(ctx, settings.downloadDir).path,
                Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (eps.isEmpty()) Text("Nothing downloaded yet. Tap the download icon on any episode.", Modifier.padding(16.dp))
        }
        items(eps.size, key = { eps[it].slug }) { i ->
            val ep = eps[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { EpisodeRow(app, ep, ep.slug == ui.slug) { player.play(eps, i) } }
                IconButton(onClick = { app.downloads.delete(ep.slug) }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
}

@Composable
fun SettingsScreen(app: App) {
    val ctx = LocalContext.current
    val s by app.prefs.settings.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(Storage.hasAllFilesAccess(ctx)) }
    LifecycleResumeEffect(Unit) { granted = Storage.hasAllFilesAccess(ctx); app.downloads.rescan(); onPauseOrDispose { } }
    val legacyPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val path = uri?.let { Storage.treeUriToPath(it) }
        if (path != null) { app.prefs.update { copy(downloadDir = path) }; app.downloads.rescan() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Title("Settings")
        Section("Downloads")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!granted) {
                Text(
                    "To save episodes to a folder of your choice (visible to file managers and other apps), " +
                        "allow All files access. Until then they are kept in the app's private folder, " +
                        "which is deleted if you uninstall.",
                    fontSize = 14.sp,
                )
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Storage.allFilesAccessIntent(ctx))
                    else legacyPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }) { Text("Allow all files access") }
            }
            Text("Saving to", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Storage.downloadDir(ctx, s.downloadDir).path, fontWeight = FontWeight.SemiBold)
            if (granted) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
                if (s.downloadDir != null) OutlinedButton(onClick = {
                    app.prefs.update { copy(downloadDir = null) }; app.downloads.rescan()
                }) { Text("Use default") }
            }
        }

        Section("Playback")
        ChoiceRow("Skip back", listOf(5, 10, 15, 30), s.skipBack) { v -> app.prefs.update { copy(skipBack = v) } }
        ChoiceRow("Skip forward", listOf(10, 15, 30, 45, 60), s.skipForward) { v -> app.prefs.update { copy(skipForward = v) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-play next", fontWeight = FontWeight.SemiBold)
                Text("Carry on to the next episode in the list", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.autoNext, { v -> app.prefs.update { copy(autoNext = v) } })
        }

        Section("Widget")
        Text(
            "Long-press your home screen, choose Widgets, and add \"MWP FM player\". Resize it for more or fewer controls.",
            Modifier.padding(horizontal = 16.dp), fontSize = 14.sp,
        )

        Section("About")
        Text("MWP FM Player ${BuildConfig.VERSION_NAME} — native build", Modifier.padding(horizontal = 16.dp))
        Text(
            "myweirdprompts.com", Modifier.padding(16.dp).clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myweirdprompts.com")))
            },
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Section(text: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(text.uppercase(), Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChoiceRow(label: String, options: List<Int>, value: Int, onPick: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> FilterChip(selected = o == value, onClick = { onPick(o) }, label = { Text("${o}s") }) }
        }
    }
}
