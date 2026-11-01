package com.myweirdprompts.fm.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Thin timeline: a 4dp line with a small thumb that grows while dragged. Dragging
 * previews the position (thumb and elapsed time follow the finger) and seeks once
 * on release — same behaviour as the PWA's scrubber (radio/js/player.js).
 */
@Composable
fun Scrubber(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val thumb by animateDpAsState(if (dragFrac != null) 16.dp else 10.dp, label = "thumb")
    val track by animateDpAsState(if (dragFrac != null) 6.dp else 4.dp, label = "track")
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outline

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp) // touch target; the line itself is 4dp
                .semantics { contentDescription = "Seek" }
                .pointerInput(durationMs) {
                    detectTapGestures { o ->
                        if (durationMs > 0) onSeek((o.x / size.width).coerceIn(0f, 1f).times(durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> if (durationMs > 0) dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ ->
                            if (dragFrac != null) dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = { dragFrac?.let { onSeek((it * durationMs).toLong()) }; dragFrac = null },
                        onDragCancel = { dragFrac = null },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(thumb)) {
                val y = size.height / 2
                val x = size.width * frac
                val w = track.toPx()
                drawLine(inactive, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
                drawLine(active, Offset(0f, y), Offset(x, y), w, StrokeCap.Round)
                drawCircle(active, thumb.toPx() / 2, Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val shown = dragFrac?.let { (it * durationMs).toLong() } ?: positionMs
            Text(formatTime(shown), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f))
            Text(formatTime(durationMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
