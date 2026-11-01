package com.myweirdprompts.fm.player.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.myweirdprompts.fm.player.App

@Composable
fun NowPlayingScreen(app: App, player: PlayerConnection, ui: PlayerUi, onClose: () -> Unit) {
    val s by app.prefs.settings.collectAsStateWithLifecycle()
    val feed by app.feed.feed.collectAsStateWithLifecycle()
    val onDisk by app.downloads.onDisk.collectAsStateWithLifecycle()
    val progress by app.downloads.progress.collectAsStateWithLifecycle()
    val ep = ui.slug?.let { feed?.episode(it) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close", Modifier.size(30.dp)) }
        }
        Spacer(Modifier.weight(0.5f))
        AsyncImage(
            ui.art, null,
            Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(20.dp))
        Text(ui.title.ifEmpty { "Nothing playing" }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        ep?.channels?.firstOrNull()?.let { id ->
            Text(feed?.channel(id)?.name ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Scrubber(ui.positionMs, ui.durationMs, player::seekTo, Modifier.widthIn(max = 420.dp).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = player::prev) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(30.dp)) }
            SkipButton(false, s.skipBack, "Back ${s.skipBack} seconds", player::back)
            FilledIconButton(onClick = player::toggle, modifier = Modifier.size(68.dp), shape = CircleShape) {
                Icon(if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (ui.playing) "Pause" else "Play", Modifier.size(36.dp))
            }
            SkipButton(true, s.skipForward, "Forward ${s.skipForward} seconds", player::forward)
            IconButton(onClick = player::next) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(30.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        if (ep != null) {
            when {
                onDisk.containsKey(ep.slug) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)); Text("  Downloaded", fontSize = 13.sp)
                }
                progress[ep.slug] != null -> Text("Downloading… ${progress[ep.slug]!!.coerceAtLeast(0)}%", fontSize = 13.sp)
                else -> TextButton(onClick = { app.downloads.enqueue(ep) }) {
                    Icon(Icons.Filled.Download, null); Text("  Download")
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SkipButton(forward: Boolean, secs: Int, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Replay, desc, Modifier.size(32.dp).graphicsLayer { if (forward) scaleX = -1f })
            Text("$secs", fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
